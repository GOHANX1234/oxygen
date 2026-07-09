#ifndef OXYGENS_INLINE_HOOK_ENGINE_H
#define OXYGENS_INLINE_HOOK_ENGINE_H

#include <cstdint>

namespace oxygens {

// Thin wrapper interface around a vetted third-party inline-hook engine
// (Dobby/whale-style — plan §3, §4.6: "Don't write your own inline hooker from
// scratch — that's a project by itself").
//
// NOT IMPLEMENTED YET. No inline-hook library is vendored into this module. Wiring
// one in (via CMake FetchContent or a prebuilt static lib) and implementing these
// functions in terms of it is explicit Phase 1 research-spike work — see plan §4.6
// hook type 2 ("native inline hooks ... only needed where a guest app calls into
// native code paths that bypass the Java proxy layer entirely"). Do not implement a
// custom inline hooker here even as a stopgap.
struct HookResult {
    bool success;
    const char *error_message; // nullptr on success
};

// Installs an inline hook at `target_address`, redirecting to `replacement_address`,
// and writes the address of a trampoline that calls the original function into
// `out_original_trampoline`.
HookResult InstallInlineHook(void *target_address, void *replacement_address, void **out_original_trampoline);

// Reverts a previously installed inline hook.
HookResult RemoveInlineHook(void *target_address);

} // namespace oxygens

#endif // OXYGENS_INLINE_HOOK_ENGINE_H
