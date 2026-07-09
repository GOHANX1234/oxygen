# Oxygen S

## Overview
Oxygen S is a native Android app-virtualization host ("multi-space" / app cloning
container). It runs unmodified guest APKs inside its own process space by
re-implementing core system services (PackageManager, ActivityManager, WindowManager)
as in-process fakes and substituting them for the real Binder proxies the guest app
would otherwise talk to.

Full architecture, phased roadmap, and known limitations: see
`attached_assets/oxygen-s-technical-plan_1783512537674.md` (source of truth — do not
contradict it without updating it first).

## Build & test environment
This is a **native Android + NDK project** (Kotlin, Jetpack Compose, C/C++, CMake). It
cannot be compiled, run, or debugged inside this Replit container: there is no Android
SDK/AVD/device here, and the hook layer specifically requires validation on real
devices/OEM ROMs. The user builds and tests this project in **Codemagic** (see
`codemagic.yaml` at the repo root). Do not attempt to install an Android SDK or spin up
an emulator here — author source/build files only, and rely on the user's Codemagic
runs for build/test signal.

## Module structure
- `app/` — Compose UI (launcher grid, clone list), manifest with the stub
  Activity/Service/Provider/Receiver pool, `OxygenApplication` hook-install entry point.
- `core-virtual/` — VPMS (virtual PackageManager), VAMS (virtual ActivityManager),
  VWMS/display delegate, `CloneManager` facade.
- `core-storage/` — per-clone storage path redirection (`VirtualStorage`).
- `core-loader/` — guest APK manifest/dex parsing, per-clone `DexClassLoader` + native
  library setup.
- `native-hook/` — NDK/CMake module: JNI bridge + inline-hook engine integration point.
- `compat/` — per-`Build.VERSION.SDK_INT` (and OEM, where documented) compat shims
  behind a single `SystemServiceCompat` interface. This is the module most likely to
  need rework on every Android major version — see plan §4.6 and §9.
- `clone-registry-db/` — Room database: clone registry (package name, install source,
  storage path, display overrides).

## Status
Phase 0 (scaffolding) + start of Phase 1 (single guest app PoC groundwork): module
structure, build config, stub component pool, VPMS/VAMS/VWMS skeletons with real
reflection-based Java-level hook installation where the target fields/methods are
well-documented AOSP internals. Native inline-hook engine integration and the
hidden-API JNI exemption path are stubbed with explicit TODOs — the plan (§9) calls
these out as research-spike work that must be validated per API level/OEM on a real
device, not guessed at from here.

## User preferences
- Build/test happens in Codemagic, not in this Replit environment.
