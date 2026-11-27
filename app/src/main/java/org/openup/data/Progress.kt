package org.openup.data

import org.openup.core.io.ByteProgress

/** Receives progress from the backup engines. Implementations may throw to cancel the work. */
interface ProgressSink {
    /** A new step started (e.g. "Backing up external data"). [totalBytes] is -1 if unknown. */
    fun step(label: String, totalBytes: Long = -1)

    /** Bytes processed in the current step. Throws a CancellationException when cancelled. */
    fun bytes(count: Long)

    fun asByteProgress(): ByteProgress = ByteProgress { bytes(it) }

    companion object {
        val NONE = object : ProgressSink {
            override fun step(label: String, totalBytes: Long) {}
            override fun bytes(count: Long) {}
        }
    }
}

/** Outcome of one task. Warnings describe partial problems that did not fail the task. */
data class TaskOutcome(val summary: String, val warnings: List<String> = emptyList())
