#include "inline_hook_engine.h"

namespace oxygens {

// Placeholder implementation — intentionally fails loudly instead of pretending to
// hook anything, per the "fail explicit, never silent fallback" principle. Replace
// the bodies below once a real inline-hook engine is vendored in (see header doc).

HookResult InstallInlineHook(void *target_address, void *replacement_address, void **out_original_trampoline) {
    (void) target_address;
    (void) replacement_address;
    if (out_original_trampoline != nullptr) {
        *out_original_trampoline = nullptr;
    }
    return HookResult{false, "InstallInlineHook: no inline-hook engine vendored yet (native-hook/CMakeLists.txt)"};
}

HookResult RemoveInlineHook(void *target_address) {
    (void) target_address;
    return HookResult{false, "RemoveInlineHook: no inline-hook engine vendored yet (native-hook/CMakeLists.txt)"};
}

} // namespace oxygens
