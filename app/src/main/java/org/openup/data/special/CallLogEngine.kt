package org.openup.data.special

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import org.json.JSONObject
import org.openup.data.BackupIO
import org.openup.data.BackupStorage
import org.openup.data.CryptoSession
import org.openup.data.ProgressSink
import org.openup.data.SettingsRepository
import org.openup.data.TaskOutcome
import org.openup.data.model.SpecialBackup
import org.openup.data.model.SpecialType
import java.io.IOException

class CallLogEngine(
    private val context: Context,
    settings: SettingsRepository,
    storage: BackupStorage,
) {
    private val support = SpecialSupport(settings, storage)

    fun backup(crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        progress.step("Backing up call logs")
        val count = support.create(SpecialType.CALL_LOGS, { dir ->
            var n = 0
            val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} ASC")
                ?: throw IOException("Call log is not accessible")
            val stored = cursor.use { c ->
                support.writeFile(dir, "calllogs.jsonl", crypto, compress = true) { out ->
                    val w = out.bufferedWriter(Charsets.UTF_8)
                    while (c.moveToNext()) {
                        w.write(SpecialSupport.rowToJson(c, COLUMNS).toString())
                        w.write("\n")
                        n++
                        progress.bytes(1)
                    }
                    w.flush()
                }
            }
            Triple(listOf(stored), n to "$n calls", n)
        })
        return TaskOutcome("Backed up $count calls")
    }

    fun restore(backup: SpecialBackup, crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        val cr = context.contentResolver
        progress.step("Restoring call logs", backup.meta.itemCount.toLong())
        val target = SpecialSupport.columnsOf(cr.query(CallLog.Calls.CONTENT_URI, null, "${CallLog.Calls._ID} = -1", null, null))
        if (target.isEmpty()) throw IOException("Call log is not accessible")
        val allowed = COLUMNS.intersect(target)
        val existing = HashSet<String>()
        cr.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DATE, CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) existing.add(key(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3)))
        }
        var inserted = 0
        var duplicates = 0
        var voicemails = 0
        var failed = 0
        val batch = ArrayList<ContentValues>()
        fun flush() {
            if (batch.isEmpty()) return
            try {
                inserted += cr.bulkInsert(CallLog.Calls.CONTENT_URI, batch.toTypedArray())
            } catch (_: Exception) {
                for (v in batch) {
                    try {
                        if (cr.insert(CallLog.Calls.CONTENT_URI, v) != null) inserted++ else failed++
                    } catch (_: Exception) {
                        failed++
                    }
                }
            }
            batch.clear()
        }
        val file = backup.meta.files.firstOrNull() ?: throw IOException("Backup is empty")
        BackupIO.open(backup.dir, file, crypto?.key).bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                progress.bytes(1)
                val o = JSONObject(line)
                val type = o.optInt(CallLog.Calls.TYPE)
                if (type == CallLog.Calls.VOICEMAIL_TYPE) {
                    voicemails++
                    continue
                }
                val k = key(o.optLong(CallLog.Calls.DATE), o.optString(CallLog.Calls.NUMBER, ""), o.optLong(CallLog.Calls.DURATION), type)
                if (!existing.add(k)) {
                    duplicates++
                    continue
                }
                batch.add(SpecialSupport.jsonToValues(o, allowed))
                if (batch.size >= 200) flush()
            }
        }
        flush()
        val warnings = buildList {
            if (voicemails > 0) add("$voicemails voicemail entries are not restorable by third-party apps")
            if (failed > 0) add("$failed calls could not be inserted")
        }
        val dup = if (duplicates > 0) " ($duplicates already present)" else ""
        return TaskOutcome("Restored $inserted calls$dup", warnings)
    }

    private fun key(date: Long, number: String?, duration: Long, type: Int) = "$date|${number.orEmpty()}|$duration|$type"

    companion object {
        val COLUMNS = setOf(
            "number", "presentation", "post_dial_digits", "via_number", "date", "duration", "data_usage", "type",
            "features", "subscription_component_name", "subscription_id", "phone_account_address", "new", "name",
            "numbertype", "numberlabel", "countryiso", "geocoded_location", "normalized_number", "formatted_number",
            "matched_number", "is_read", "add_for_all_users", "call_screening_component_name",
            "call_screening_app_name", "block_reason", "missed_reason", "priority", "subject",
        )
    }
}
