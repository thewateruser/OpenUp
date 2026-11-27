package org.openup.jobs

import org.openup.data.AppParts
import org.openup.data.model.SpecialType

sealed interface JobTask {
    val title: String
    val needsShizuku: Boolean

    data class BackupApp(val packageName: String, val label: String, val parts: AppParts) : JobTask {
        override val title get() = label
        override val needsShizuku get() = true
    }

    /** Restores [backupDir] (a version folder) or, if null, the newest backup of the package. */
    data class RestoreApp(val packageName: String, val label: String, val backupDir: String?, val parts: AppParts) : JobTask {
        override val title get() = label
        override val needsShizuku get() = true
    }

    data class BackupSpecial(val type: SpecialType) : JobTask {
        override val title get() = type.title
        override val needsShizuku get() = type == SpecialType.WIFI
    }

    data class RestoreSpecial(val type: SpecialType, val backupDir: String) : JobTask {
        override val title get() = type.title
        override val needsShizuku get() = type == SpecialType.WIFI
    }
}

enum class JobKind { BACKUP, RESTORE }

data class JobRequest(
    val title: String,
    val kind: JobKind,
    val tasks: List<JobTask>,
    val scheduled: Boolean = false,
)

enum class TaskStatus { PENDING, RUNNING, DONE, WARNING, FAILED, CANCELLED }

data class TaskState(
    val task: JobTask,
    val status: TaskStatus = TaskStatus.PENDING,
    val step: String = "",
    /** 0..1, or -1 when indeterminate. */
    val progress: Float = -1f,
    val message: String = "",
    val warnings: List<String> = emptyList(),
)

data class JobState(
    val id: Long,
    val title: String,
    val kind: JobKind,
    val tasks: List<TaskState>,
    val running: Boolean,
    val cancelRequested: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    val total get() = tasks.size
    val finished get() = tasks.count { it.status != TaskStatus.PENDING && it.status != TaskStatus.RUNNING }
    val failed get() = tasks.count { it.status == TaskStatus.FAILED }
    val warnings get() = tasks.count { it.status == TaskStatus.WARNING }
    val succeeded get() = tasks.count { it.status == TaskStatus.DONE || it.status == TaskStatus.WARNING }
    val cancelled get() = tasks.count { it.status == TaskStatus.CANCELLED }
    val current get() = tasks.firstOrNull { it.status == TaskStatus.RUNNING }

    val overallProgress: Float
        get() {
            if (tasks.isEmpty()) return 1f
            val cur = current?.progress?.takeIf { it >= 0 } ?: 0f
            return ((finished + cur) / total).coerceIn(0f, 1f)
        }

    fun summaryText(): String = buildString {
        append("$succeeded of $total succeeded")
        if (warnings > 0) append(", $warnings with warnings")
        if (failed > 0) append(", $failed failed")
        if (cancelled > 0) append(", $cancelled cancelled")
    }
}
