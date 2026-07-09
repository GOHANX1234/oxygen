# Oxygen S — Technical Architecture & Implementation Plan

**Category:** Android app-level virtualization ("multi-space" / app cloning container)
**Approach:** Full virtualization (Option 1) — host app emulates core system services and runs unmodified guest APKs inside itself, without installing them via `PackageInstaller`.
**Audience for this doc:** engineering agent implementing the codebase. Written to be actionable, not just conceptual.

---

## 1. What we're actually building

Oxygen S is a **host app** that:
1. Accepts a guest APK (either picked from the device's installed apps, or a raw `.apk` file).
2. Loads and executes that APK's code **inside Oxygen S's own process space** — without it appearing in the device's real app list.
3. Presents the guest app's UI as if it were a normal running app, inside an Oxygen S–managed Activity stack.
4. Gives each "clone" isolated storage, so two instances of the same app (or two clones) don't collide.

This works by re-implementing three Android system services as fakes ("Virtual services") that live inside the host process, and intercepting the guest app's calls to the real services so they get routed to our fakes instead.

**Core insight to keep in the agent's context at all times:** Android apps never talk to system services directly. They talk to a local Binder proxy object (e.g. `IActivityManager`, `IPackageManager`) obtained via `ServiceManager`. If we replace that proxy object with our own object implementing the same interface, the app has no way to tell the difference — as long as our fake behaves correctly. This is the entire trick. Everything else in this doc is detail in service of that one substitution.

---

## 2. High-level architecture

```
┌─────────────────────────────────────────────────────────┐
│                      Oxygen S Host App                    │
│                                                             │
│  ┌───────────────┐   ┌──────────────────┐                 │
│  │ Compose UI     │   │ Clone Manager     │                 │
│  │ (launcher grid,│   │ (install/remove/  │                 │
│  │ settings, per- │   │  list clones,     │                 │
│  │ clone controls)│   │  metadata DB)     │                 │
│  └───────┬───────┘   └─────────┬─────────┘                 │
│          │                     │                            │
│  ┌───────▼─────────────────────▼──────────┐                 │
│  │           Virtual Core (Kotlin)          │                 │
│  │  ┌────────┐ ┌────────┐ ┌─────────────┐  │                 │
│  │  │ VPMS   │ │ VAMS   │ │ VWMS/Display │  │                 │
│  │  │(pkg mgr│ │(activity│ │  management  │  │                 │
│  │  │ fake)  │ │ mgr fake│ │              │  │                 │
│  │  └────────┘ └────────┘ └─────────────┘  │                 │
│  │  ┌──────────────┐ ┌────────────────────┐│                 │
│  │  │ VirtualStorage│ │ DexClassLoader mgr ││                 │
│  │  │ (path redirect│ │ (loads guest code) ││                 │
│  │  └──────────────┘ └────────────────────┘│                 │
│  └───────────────────┬──────────────────────┘                 │
│                       │                                       │
│  ┌────────────────────▼──────────────────────┐                │
│  │        Native Hook Layer (NDK, C++)        │                │
│  │  - Java dynamic-proxy install for Binder    │                │
│  │    interfaces (IActivityManager etc.)       │                │
│  │  - Inline hooks on libart.so entry points    │                │
│  │    (ClassLoader, JNI resolution)             │                │
│  │  - Hidden-API gate handling per OS version   │                │
│  └───────────────────┬──────────────────────┘                │
│                       │                                       │
│  ┌────────────────────▼──────────────────────┐                │
│  │      Guest process(es) — one per clone      │                │
│  │      Guest app's real, unmodified code       │                │
│  │      running with our hooks installed        │                │
│  └─────────────────────────────────────────────┘               │
└─────────────────────────────────────────────────────────┘
```

**Key architectural decision: process-per-clone.** Don't run all guest apps in the host's main process. Fork a dedicated process per clone (via a manifest-declared set of placeholder `<service>`/`<activity>` entries with `android:process=":clone_N"`, selected at runtime). This means:
- A crashing guest app doesn't kill Oxygen S or other clones.
- Each clone gets a distinct Linux-level process, which makes storage isolation and memory limits easier to reason about.
- You pay a `Zygote` fork cost per clone, and IPC (Binder) overhead between the clone process and the host's virtual services — this is the same tradeoff VirtualApp/DroidPlugin make.

---

## 3. Tech stack

| Layer | Choice | Why |
|---|---|---|
| UI | Kotlin + Jetpack Compose | Modern, matches your spec |
| App/business logic | Kotlin | Coroutines for async IPC handling, Flow for clone state |
| Virtual service layer | Kotlin (JVM side) | Needs to implement Binder `Stub`/`Proxy`-compatible interfaces — doable in pure Kotlin via `aidl`-generated stubs |
| Hook layer | C/C++ via NDK | Needed for inline hooking of native ART entry points and JNI-level interception where Java-level proxying isn't enough |
| Native hooking primitives | A vetted inline-hook library (e.g. an Android-ported Dobby/whale-style hook engine) | Don't write your own inline hooker from scratch — that's a project by itself |
| Dex loading | Custom `ClassLoader` extending `PathClassLoader`/`BaseDexClassLoader` | For loading guest APKs' `classes.dex` without installation |
| Local metadata store | Room (SQLite) | Clone registry: package name, install source, storage path, display name/icon override |
| Build | Gradle (Kotlin DSL), CMake for the native module | Standard |

---

## 4. Core subsystems — what each one has to do

### 4.1 Virtual PackageManagerService (VPMS)
Responsibilities:
- Maintain an internal registry of "installed" guest apps (parsed from the APK's `AndroidManifest.xml` — components, permissions, intent filters) **without calling the real `PackageInstaller`**.
- Answer `PackageManager` queries the guest app or host UI makes: `getPackageInfo`, `getApplicationInfo`, `queryIntentActivities`, `resolveActivity`, `checkPermission`, etc. — using the internal registry instead of the real one.
- Parsing: use `android.content.pm.PackageParser`-equivalent logic (this class is restricted on modern API levels, so budget time to reimplement manifest/dex parsing using `ApkParser`-style third-party libraries or AOSP source directly rather than relying on the hidden framework class).

Agent task breakdown: parser module → in-memory registry → AIDL-compatible `Stub` implementation → hook installation point (see §4.6).

### 4.2 Virtual ActivityManagerService (VAMS)
Responsibilities:
- Intercept `startActivity`, `startService`, lifecycle callbacks (`onCreate`/`onResume`/`onPause`/`onDestroy` reporting back to the "system").
- Maintain a virtual Activity task stack **per clone**, since the real AMS doesn't know these Activities exist.
- Translate a guest app's `startActivity(new Intent(this, GuestActivity.class))` into actually launching one of Oxygen S's own **stub/proxy Activities** (pre-declared in the host's manifest) that then loads and delegates to the guest's real Activity class at runtime via reflection/class swapping.

This "stub Activity" trick is the standard workaround for the fact that Android will only actually launch Activity classes that are declared in *your own* manifest — so Oxygen S needs to declare a pool of generic placeholder Activities/Services/Providers/Receivers (e.g. `StubActivity0..N`) up front, and the VAMS maps guest components onto free stubs dynamically.

### 4.3 Virtual WindowManagerService (VWMS) / display
- Mostly can piggyback on the stub Activity's real window — you don't need to reimplement window management from scratch, since the guest's UI is actually drawn inside a real Android Activity (the stub). The main work here is making sure the guest's `Context`, `Resources`, and `LayoutInflater` resolve correctly against the *guest's* resources/assets rather than the host's.

### 4.4 DexClassLoader manager
- For each clone: extract the guest APK to a private directory, create a `DexClassLoader` pointing at it, and set its parent appropriately so guest classes resolve against guest resources but shared framework classes still resolve normally.
- Handle native libraries (`.so` files) bundled in the guest APK — these need to be extracted to a path passed via `nativeLibraryDir` and loaded with correct ABI matching (arm64-v8a almost exclusively on modern devices — budget for dropping 32-bit support entirely, matching current Play Store requirements).

### 4.5 VirtualStorage
- Redirect `Context.getFilesDir()`, `getCacheDir()`, `getDatabasePath()`, `SharedPreferences` paths, and scoped storage access to a per-clone subdirectory under Oxygen S's own app-private storage (`/data/data/com.yourcompany.oxygens/virtual/clone_N/...`).
- This is done by hooking `Context`/`ContextImpl` path-resolution methods at the Java level (not native) — intercept the getters, don't intercept the filesystem.
- Scoped storage (Android 10+) makes touching *shared* storage from a guest app much harder than in older virtualization implementations — plan for guest apps that need `MediaStore`/shared storage access to be a known limitation category, not something silently supported.

### 4.6 Native hook layer
Two distinct hook types, don't conflate them:
1. **Java-level substitution** (majority of the work): replace the static Binder proxy singleton (`IActivityManager.Stub.asInterface`, `ActivityManagerNative.getDefault()`-equivalent, `IPackageManager` singleton, etc.) with your VAMS/VPMS Kotlin objects, using Java reflection at process-attach time (in a custom `Application.attachBaseContext` or an injected entry point in the clone process). This is what most of DroidPlugin/VirtualApp's "hooking" actually is — replacing a cached singleton field, not patching machine code.
2. **Native inline hooks** (smaller, targeted use): only needed where a guest app calls into native code paths that bypass the Java proxy layer entirely, or where you need to intercept `dlopen`/`dlsym` to control native library loading per clone. Use this sparingly — it's the most fragile, OS-version-sensitive part of the system.

**Hidden API handling:** Every one of the above touches non-SDK interfaces. On modern Android, this means:
- Explicitly test which access tier (light-greylist / max-target-N / blocklist) each interface falls under for your target API levels, per Android version, before relying on it.
- Where possible, use the JNI-based `art::JNIEnvExt` hidden-API exemption technique (works because the OS only vets *reflection-based* Java calls, not calls a native library makes into ART internals directly) as your primary access path rather than double-reflection, since it's the more durable of the two workarounds and is what current-generation virtualization apps rely on.
- **This is the subsystem most likely to require rework on each new Android major version.** Treat it as a pluggable "compat layer" with a per-API-level implementation, not hardcoded logic (see §6, compatibility strategy).

---

## 5. Suggested repo/module structure

```
oxygen-s/
├── app/                      # Compose UI, entry point, manifest w/ stub component pool
├── core-virtual/             # VPMS, VAMS, VWMS, clone registry (pure Kotlin)
├── core-storage/             # VirtualStorage redirection
├── core-loader/              # DexClassLoader mgmt, APK parsing
├── native-hook/              # NDK/CMake module: inline hook engine + JNI bridge
├── compat/                   # Per-Android-version compat shims (compat/api28, compat/api31, compat/api34, compat/api35 ...)
└── clone-registry-db/        # Room database module
```

The `compat/` module split is the single most important structural decision for long-term maintainability — isolate every OS-version-specific hack behind an interface (`SystemServiceCompat`) so a new Android release means adding `compat/apiNN/`, not touching core logic.

---

## 6. Phased roadmap

**Phase 0 — Scaffolding (1–2 weeks)**
Repo structure above, CI, empty Compose shell, NDK build pipeline verified working (a trivial JNI "hello world" hook compiling and linking).

**Phase 1 — Single guest app PoC**
Goal: pick one simple, non-integrity-protected guest app (e.g. a basic open-source Android app), get it launching inside a stub Activity with VPMS/VAMS answering its calls correctly. This phase alone typically takes the longest — it's where the Binder-proxy-substitution trick either works or reveals what's blocking it on your target API level.
Definition of done: guest app launches, its main Activity renders, basic navigation works, no crash on backgrounding/foregrounding.

**Phase 2 — Storage isolation + multi-instance**
Two clones of the same guest app running simultaneously with genuinely separate data (login state, local DB). This validates VirtualStorage end-to-end.

**Phase 3 — Compose UI shell**
Launcher grid, add/remove clone flows, per-clone naming/icon, settings (clear data, force stop, storage usage per clone).

**Phase 4 — Compatibility hardening**
Build out the `compat/` matrix across target API levels and at least 2–3 major OEM skins (Samsung One UI, Xiaomi HyperOS at minimum — these diverge from AOSP in ways that break hook assumptions). Establish an automated device-farm test matrix (Firebase Test Lab or similar) that runs the Phase 1–3 flows against every supported OS version/OEM combination on every build.

**Phase 5 — Broader app compatibility + known-limitation handling**
Expand guest-app support beyond the PoC target, and explicitly detect and gracefully message (rather than silently crash on) guest apps protected by Play Integrity / SafetyNet-successor checks, since those are expected to be out of scope for this technique — don't try to defeat them, flag them.

**Phase 6 — Distribution decision**
Play Store submission (accept policy risk, plan for possible takedown/appeal cycles) vs. direct APK distribution with a self-update mechanism.

---

## 7. Known hard limitations (set expectations now, not later)

- Apps using Play Integrity API, strong root/tamper detection, or SafetyNet-successor attestation will very likely detect the virtualized environment and refuse to run or degrade functionality. This is expected and not a bug to chase.
- Full shared/scoped storage access (photos, downloads) from guest apps is harder than pre-Android-10 virtualization implementations and may need a deliberate, narrower feature (e.g. a mediated "share into clone" flow) rather than transparent passthrough.
- 32-bit-only guest APKs are increasingly rare but will need to be explicitly unsupported or handled with a compatibility warning.
- Every Android major version bump is a maintenance event, not a one-time cost — budget ongoing engineering time for this, not just the initial build.

---

## 8. Reference implementations worth studying (not copying wholesale)

These are the standard, well-documented open-source starting points for this category — study their architecture, particularly how they structure the compat layer across API levels, before writing the hook layer from scratch:
- **VirtualApp** — the most-referenced implementation of this pattern; good reference for VPMS/VAMS/VWMS structure.
- **VirtualXposed** — builds on VirtualApp, useful for seeing how a second layer (Xposed module support) was bolted onto the same core.
- **DroidPlugin** — earlier, simpler implementation; useful for understanding the Java-dynamic-proxy hooking approach in isolation before native hooks are added.

Forking one of these as a base (rather than building the hook layer from zero) is the realistic path to a working PoC in a reasonable timeframe — the ground-up hook engine is the single highest-risk, highest-effort component of this entire project.

---

## 9. Notes for the coding agent specifically

- Treat §4.6 (hidden API / hook layer) as the critical path. Everything else in the architecture is conventional Android engineering; this is the part that needs research spikes before implementation, not straight-line coding.
- Do not hardcode assumptions about which non-SDK interfaces are accessible — always branch through the `compat/` layer described in §5, keyed on `Build.VERSION.SDK_INT` and, where OEM divergence is known to matter, `Build.MANUFACTURER`.
- Build the automated compatibility test matrix (Phase 4) earlier than it might feel natural to — this category of app fails silently on specific devices far more often than it throws a catchable exception, so manual testing on one device (as you've been doing) will systematically overstate how done the project is.
- Flag to the user (not silently swallow) any guest app that fails Phase 1–style validation, categorized as "integrity-protected" vs. "hook-incompatible on this OS version" vs. "genuine bug" — these need different fixes and conflating them wastes debugging time.
