package org.openup.data.special

import android.database.Cursor
import android.os.Build
import org.json.JSONObject
import org.openup.core.io.Compression
import org.openup.data.BackupIO
import org.openup.data.BackupStorage
import org.openup.data.CryptoSession
import org.openup.data.SettingsRepository
import org.openup.data.model.SpecialBackupMeta
import org.openup.data.model.SpecialType
import org.openup.data.model.StoredFile
import java.io.File
import android.content.ContentValues
import java.io.OutputStream

/** Shared helpers for the non-app backups (messages, call logs, wallpapers, Wi-Fi). */
internal class SpecialSupport(private val settings: SettingsRepository, private val storage: BackupStorage) {

    /** Runs [block] inside a new partial backup folder, then writes metadata and commits. */
    fun <T> create(
        type: SpecialType,
        block: (dir: File) -> Triple<List<StoredFile>, Pair<Int, String>, T>,
        extras: (T) -> Map<String, String> = { emptyMap() },
    ): T {
        storage.ensureRoot()
        val (id, partial) = storage.newPartial(storage.specialDir(type))
        try {
            val (files, info, value) = block(partial)
            val meta = SpecialBackupMeta(
                type = type.dirName,
                backupTime = System.currentTimeMillis(),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidSdk = Build.VERSION.SDK_INT,
                itemCount = info.first,
                summary = info.second,
                files = files,
                extras = extras(value),
            )
            storage.writeSpecialMeta(partial, meta)
            storage.commitPartial(partial, id)
            storage.prune(storage.specialDir(type), settings.current.keepVersions)
            return value
        } catch (t: Throwable) {
            partial.deleteRecursively()
            throw t
        }
    }

    fun writeFile(dir: File, baseName: String, crypto: CryptoSession?, compress: Boolean, block: (OutputStream) -> Unit): StoredFile {
        val s = settings.current
        val compression = if (compress) s.compression else Compression.NONE
        val file = File(dir, org.openup.core.io.Codec.fileName(baseName, compression, crypto != null))
        return BackupIO.write(file, compression, s.compressionLevel, crypto?.spec, block)
    }

    companion object {
        /** Copies the allowed, non-null columns of the current cursor row into a JSON object. */
        fun rowToJson(c: Cursor, allowed: Set<String>): JSONObject {
            val o = JSONObject()
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                if (name !in allowed) continue
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                    Cursor.FIELD_TYPE_STRING -> o.put(name, c.getString(i))
                    else -> Unit // NULL and BLOB values are not backed up
                }
            }
            return o
        }

        /** Builds ContentValues from [o], keeping only keys in [allowed]. */
        fun jsonToValues(o: JSONObject, allowed: Set<String>): ContentValues {
            val v = ContentValues()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k !in allowed) continue
                when (val value = o.get(k)) {
                    is Int -> v.put(k, value.toLong())
                    is Long -> v.put(k, value)
                    is Double -> v.put(k, value)
                    is Float -> v.put(k, value.toDouble())
                    is Boolean -> v.put(k, if (value) 1 else 0)
                    is String -> v.put(k, value)
                    else -> Unit
                }
            }
            return v
        }

        fun columnsOf(c: Cursor?): Set<String> = c?.use { it.columnNames.toSet() } ?: emptySet()
    }
}
