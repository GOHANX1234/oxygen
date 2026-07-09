package com.oxygens.core.virtual.vams

import com.oxygens.core.loader.model.ComponentType
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps guest components onto the finite pool of stub Activities/Services/Providers/
 * Receivers declared in AndroidManifest.xml (plan §4.2/§5).
 *
 * The pool is hand-declared in the manifest today (StubActivity0/1/2/3, etc, one
 * process group per clone). This class must never allocate a stub class name that
 * isn't actually declared in the manifest, so [poolByProcessGroup] is the single
 * source of truth on the Kotlin side and MUST be kept in sync with the manifest by
 * hand until a codegen step exists.
 */
object StubComponentPool {
    /** processGroup (e.g. ":clone_0") -> stub class names available in that group,
     *  by component type. */
    val poolByProcessGroup: Map<String, Map<ComponentType, List<String>>> = mapOf(
        ":clone_0" to mapOf(
            ComponentType.ACTIVITY to listOf(
                "com.oxygens.app.stub.StubActivity0",
                "com.oxygens.app.stub.StubActivity1",
            ),
            ComponentType.SERVICE to listOf("com.oxygens.app.stub.StubService0"),
            ComponentType.PROVIDER to listOf("com.oxygens.app.stub.StubProvider0"),
            ComponentType.RECEIVER to listOf("com.oxygens.app.stub.StubReceiver0"),
        ),
        ":clone_1" to mapOf(
            ComponentType.ACTIVITY to listOf(
                "com.oxygens.app.stub.StubActivity2",
                "com.oxygens.app.stub.StubActivity3",
            ),
            ComponentType.SERVICE to listOf("com.oxygens.app.stub.StubService1"),
            ComponentType.PROVIDER to listOf("com.oxygens.app.stub.StubProvider1"),
            ComponentType.RECEIVER to listOf("com.oxygens.app.stub.StubReceiver1"),
        ),
    )
}

class StubAllocationException(message: String) : Exception(message)

/**
 * Tracks which stub slots are currently bound to which guest component, per clone
 * process group, and hands out free slots. Thread-safe: allocation can be requested
 * from Binder callback threads.
 */
class StubComponentAllocator {

    private data class SlotKey(val processGroup: String, val stubClassName: String)

    /** stub slot -> guest class name currently delegated to it. */
    private val bindings = ConcurrentHashMap<SlotKey, String>()

    /** guest class name -> stub slot it's bound to, for fast reverse lookup. */
    private val reverse = ConcurrentHashMap<String, SlotKey>()

    fun allocate(processGroup: String, type: ComponentType, guestClassName: String): String {
        reverse[guestClassName]?.let { return it.stubClassName }

        val pool = StubComponentPool.poolByProcessGroup[processGroup]
            ?: throw StubAllocationException("No stub pool declared for process group $processGroup")
        val candidates = pool[type]
            ?: throw StubAllocationException("No $type stubs declared for process group $processGroup")

        val free = candidates.firstOrNull { candidate ->
            bindings[SlotKey(processGroup, candidate)] == null
        } ?: throw StubAllocationException(
            "Stub pool for $processGroup/$type exhausted (${candidates.size} slots). " +
                "Increase the pool size in AndroidManifest.xml and StubComponentPool together."
        )

        val key = SlotKey(processGroup, free)
        bindings[key] = guestClassName
        reverse[guestClassName] = key
        return free
    }

    fun resolveGuestClass(processGroup: String, stubClassName: String): String? =
        bindings[SlotKey(processGroup, stubClassName)]

    fun release(guestClassName: String) {
        reverse.remove(guestClassName)?.let { bindings.remove(it) }
    }
}
