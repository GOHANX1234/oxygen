package com.oxygens.app.stub

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Base for the manifest-declared stub Service pool (plan §4.2). Same delegation
 * pattern as StubActivity: Android only starts Service classes declared in our own
 * manifest, so this loads and forwards to the guest's real Service class.
 *
 * TODO(Phase 1+): guest Service delegation is out of scope for the Phase 1 "single
 * guest app PoC" definition of done (plan §6, which only requires Activity launch +
 * navigation), so this intentionally only prevents a crash for now rather than
 * implementing full delegation. Implement alongside broader component support in
 * Phase 5.
 */
open class StubService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

class StubService0 : StubService()
class StubService1 : StubService()
