package com.oxygens.core.virtual.vams

import android.content.Context
import android.content.Intent
import com.oxygens.core.loader.model.ComponentType
import com.oxygens.core.virtual.vpms.VirtualPackageManagerService
import java.util.concurrent.ConcurrentHashMap

/**
 * Virtual ActivityManagerService (plan §4.2).
 *
 * Intercepts guest `startActivity`/`startService` calls and redirects them onto a
 * free stub component (see [StubComponentAllocator]), tracking the resulting guest
 * Activity lifecycle in a per-clone [TaskStack] since the real AMS has no visibility
 * into any of this.
 *
 * Like VPMS, this does not implement `IActivityManager` directly. The compat layer's
 * `IActivityManagerHookInstaller`-equivalent routes intercepted calls into the methods
 * below; keep that surface area small and grown on demand rather than trying to cover
 * the entire (huge, version-dependent) IActivityManager AIDL surface up front.
 */
class VirtualActivityManagerService(
    private val vpms: VirtualPackageManagerService,
    private val allocator: StubComponentAllocator,
) {

    private val taskStacks = ConcurrentHashMap<Int, TaskStack>()

    private fun stackFor(cloneId: Int): TaskStack =
        taskStacks.getOrPut(cloneId) { TaskStack(cloneId) }

    /**
     * Guest called `startActivity(Intent(this, GuestActivity::class.java))`.
     * @return the stub Intent that should actually be handed to the real
     *   `Context.startActivity`, or null if resolution failed (e.g. guest class not
     *   registered in VPMS for this clone — surface this as a real error to the guest,
     *   don't silently drop the launch).
     */
    fun startGuestActivity(
        context: Context,
        cloneId: Int,
        processGroup: String,
        guestPackageName: String,
        guestClassName: String,
        originalIntent: Intent,
    ): Intent? {
        // Existence/registration check only — resolveActivity's return value isn't
        // consumed yet because the stub Intent below is built from the already-known
        // guestClassName, not from anything on the resolved ParsedManifestComponent.
        // Revisit once StubActivity delegation (Phase 1) needs more than the class name.
        vpms.resolveActivity(cloneId, guestPackageName, guestClassName) ?: return null

        val stubClassName = allocator.allocate(processGroup, ComponentType.ACTIVITY, guestClassName)
        stackFor(cloneId).push(
            VirtualActivityRecord(
                guestClassName = guestClassName,
                stubClassName = stubClassName,
                state = ActivityLifecycleState.CREATED,
                taskId = cloneId,
            )
        )

        return Intent(originalIntent).apply {
            setClassName(context.packageName, stubClassName)
            putExtra(EXTRA_GUEST_CLONE_ID, cloneId)
            putExtra(EXTRA_GUEST_PACKAGE_NAME, guestPackageName)
            putExtra(EXTRA_GUEST_CLASS_NAME, guestClassName)
        }
    }

    fun reportLifecycle(cloneId: Int, guestClassName: String, state: ActivityLifecycleState) {
        stackFor(cloneId).updateState(guestClassName, state)
        if (state == ActivityLifecycleState.DESTROYED) {
            stackFor(cloneId).pop(guestClassName)
            allocator.release(guestClassName)
        }
    }

    fun taskStackSnapshot(cloneId: Int) = stackFor(cloneId).snapshot()

    companion object {
        const val EXTRA_GUEST_CLONE_ID = "com.oxygens.extra.CLONE_ID"
        const val EXTRA_GUEST_PACKAGE_NAME = "com.oxygens.extra.GUEST_PACKAGE_NAME"
        const val EXTRA_GUEST_CLASS_NAME = "com.oxygens.extra.GUEST_CLASS_NAME"
    }
}
