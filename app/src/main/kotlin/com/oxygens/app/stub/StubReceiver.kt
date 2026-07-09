package com.oxygens.app.stub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Base for the manifest-declared stub BroadcastReceiver pool (plan §4.2).
 *
 * Guest BroadcastReceiver delegation is Phase 5 work and not implemented yet. Nothing
 * in the current codebase allocates or targets these stubs (StubComponentAllocator is
 * only exercised for ACTIVITY today), so `onReceive` should never fire in practice —
 * if it does, that means something started depending on receiver delegation before it
 * exists. Fail loudly rather than silently accepting the broadcast and doing nothing,
 * per plan §9 ("flag ... don't silently swallow").
 */
open class StubReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        throw UnsupportedOperationException(
            "Guest BroadcastReceiver delegation is not implemented (Phase 5, plan §4.2/§9). " +
                "Received: ${intent.action} — this stub should not have been targeted yet."
        )
    }
}

class StubReceiver0 : StubReceiver()
class StubReceiver1 : StubReceiver()
