package com.oxygens.core.virtual.vams

import java.util.concurrent.CopyOnWriteArrayList

/** Lifecycle states VAMS tracks for a guest Activity instance it knows about. */
enum class ActivityLifecycleState { CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED }

data class VirtualActivityRecord(
    val guestClassName: String,
    val stubClassName: String,
    var state: ActivityLifecycleState,
    val taskId: Int,
)

/**
 * Per-clone virtual Activity task stack (plan §4.2). The real ActivityManagerService
 * has no idea these Activities exist — they're real stub Activities from the real
 * AMS's point of view, but VAMS is the only thing that knows which guest class each
 * stub is currently standing in for, and in what order they were pushed.
 */
class TaskStack(val cloneId: Int) {

    private val records = CopyOnWriteArrayList<VirtualActivityRecord>()

    fun push(record: VirtualActivityRecord) {
        records.add(record)
    }

    fun updateState(guestClassName: String, state: ActivityLifecycleState) {
        records.lastOrNull { it.guestClassName == guestClassName }?.state = state
    }

    fun pop(guestClassName: String): VirtualActivityRecord? {
        val record = records.lastOrNull { it.guestClassName == guestClassName } ?: return null
        records.remove(record)
        return record
    }

    fun top(): VirtualActivityRecord? = records.lastOrNull()

    fun snapshot(): List<VirtualActivityRecord> = records.toList()

    fun isEmpty(): Boolean = records.isEmpty()
}
