package org.openup.data.model

import kotlinx.serialization.Serializable
import org.openup.core.io.Compression

const val FORMAT_VERSION = 1

/** Where a backup file came from and how it is stored. */
@Serializable
data class StoredFile(
    /** File name relative to the backup directory. */
    val file: String,
    /** Size of the original (decoded) data in bytes. */
    val originalSize: Long,
    /** Size of the file on disk. */
    val storedSize: Long,
    val compression: Compression,
    val encrypted: Boolean,
    /** SHA-256 of the decoded data, if it was computed. */
    val sha256: String? = null,
)

@Serializable
data class ApkFile(
    /** "base" or the split name (e.g. "config.arm64_v8a"). */
    val split: String,
    val stored: StoredFile,
)

@Serializable
data class DataPart(
    val stored: StoredFile,
    val files: Int,
    val dirs: Int,
    /** Files that could not be read and were therefore not backed up. */
    val skipped: Int = 0,
)

@Serializable
data class AppBackupMeta(
    val formatVersion: Int = FORMAT_VERSION,
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val systemApp: Boolean = false,
    val installer: String? = null,
    val signingCertSha256: List<String> = emptyList(),
    val backupTime: Long,
    val deviceModel: String = "",
    val androidSdk: Int = 0,
    val apks: List<ApkFile> = emptyList(),
    val extData: DataPart? = null,
    val obb: DataPart? = null,
    val media: DataPart? = null,
    val permissions: List<String> = emptyList(),
) {
    val totalStoredSize: Long
        get() = apks.sumOf { it.stored.storedSize } + listOfNotNull(extData, obb, media).sumOf { it.stored.storedSize }
    val encrypted: Boolean
        get() = apks.any { it.stored.encrypted } || listOfNotNull(extData, obb, media).any { it.stored.encrypted }
}

enum class SpecialType(val dirName: String, val title: String) {
    MESSAGES("messages", "Messages"),
    CALL_LOGS("calllogs", "Call logs"),
    WALLPAPERS("wallpapers", "Wallpapers"),
    WIFI("wifi", "Wi-Fi networks"),
}

@Serializable
data class SpecialBackupMeta(
    val formatVersion: Int = FORMAT_VERSION,
    val type: String,
    val backupTime: Long,
    val deviceModel: String = "",
    val androidSdk: Int = 0,
    /** Number of items (messages, calls, networks, wallpapers). */
    val itemCount: Int,
    /** Human readable details, e.g. "120 SMS, 4 MMS". */
    val summary: String,
    val files: List<StoredFile>,
    /** Extra values that some types need (e.g. live wallpaper component). */
    val extras: Map<String, String> = emptyMap(),
) {
    val totalStoredSize get() = files.sumOf { it.storedSize }
    val encrypted get() = files.any { it.encrypted }
}

/** An app backup version found on disk. */
data class AppBackup(val id: String, val dir: java.io.File, val meta: AppBackupMeta)

/** A special (non app) backup found on disk. */
data class SpecialBackup(val type: SpecialType, val id: String, val dir: java.io.File, val meta: SpecialBackupMeta)
