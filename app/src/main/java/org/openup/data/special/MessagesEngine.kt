package org.openup.data.special

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import org.openup.core.archive.TarEntry
import org.openup.core.archive.TarReader
import org.openup.core.archive.TarWriter
import org.openup.data.BackupIO
import org.openup.data.BackupStorage
import org.openup.data.CryptoSession
import org.openup.data.ProgressSink
import org.openup.data.SettingsRepository
import org.openup.data.TaskOutcome
import org.openup.data.model.SpecialBackup
import org.openup.data.model.SpecialType
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

/**
 * SMS and MMS backup/restore through the telephony content providers.
 *
 * Archive layout (tar, then compressed/encrypted like every other backup file):
 * `sms.jsonl`, `mms.jsonl` and `parts/<n>` with the binary MMS attachments.
 * Writing requires the WRITE_SMS app-op, which OpenUp grants itself through Shizuku.
 */
class MessagesEngine(
    private val context: Context,
    settings: SettingsRepository,
    storage: BackupStorage,
) {
    private val support = SpecialSupport(settings, storage)
    private val cr get() = context.contentResolver

    private fun threadRecipients(): Map<Long, List<String>> {
        val canonical = HashMap<Long, String>()
        val threads = HashMap<Long, List<String>>()
        try {
            cr.query(CANONICAL_URI, arrayOf("_id", "address"), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(1)?.let { canonical[c.getLong(0)] = it }
            }
            cr.query(Telephony.Threads.CONTENT_URI, arrayOf("_id", Telephony.Threads.RECIPIENT_IDS), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val ids = c.getString(1)?.split(' ')?.mapNotNull { it.trim().toLongOrNull() } ?: continue
                    threads[c.getLong(0)] = ids.mapNotNull { canonical[it] }
                }
            }
        } catch (_: Exception) {
            // Some vendors restrict these tables; restore then falls back to message addresses.
        }
        return threads
    }

    fun backup(crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        progress.step("Backing up messages")
        val work = File(context.cacheDir, "messages-backup").apply { deleteRecursively(); mkdirs() }
        try {
            val threads = threadRecipients()
            val smsFile = File(work, "sms.jsonl")
            val smsCount = smsFile.bufferedWriter().use { w -> dumpSms(w, threads, progress) }
            val partsDir = File(work, "parts").apply { mkdirs() }
            val mmsFile = File(work, "mms.jsonl")
            val mmsCount = mmsFile.bufferedWriter().use { w -> dumpMms(w, threads, partsDir, progress) }
            val summary = "$smsCount SMS, $mmsCount MMS"
            support.create(SpecialType.MESSAGES, { dir ->
                val stored = support.writeFile(dir, "messages.tar", crypto, compress = true) { out ->
                    val tar = TarWriter(out)
                    for (f in listOf(smsFile, mmsFile)) f.inputStream().use { tar.putFile(f.name, f.length(), 0x1A4, 0, it) }
                    partsDir.listFiles()?.sortedBy { it.name }?.forEach { p ->
                        p.inputStream().use { tar.putFile("parts/${p.name}", p.length(), 0x1A4, 0, it) }
                    }
                    tar.finish()
                }
                Triple(listOf(stored), (smsCount + mmsCount) to summary, Unit)
            })
            return TaskOutcome("Backed up $summary")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun dumpSms(w: BufferedWriter, threads: Map<Long, List<String>>, progress: ProgressSink): Int {
        var n = 0
        val c = cr.query(Telephony.Sms.CONTENT_URI, null, null, null, "${Telephony.Sms.DATE} ASC")
            ?: throw IOException("SMS are not accessible")
        c.use {
            val threadIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            while (c.moveToNext()) {
                val o = SpecialSupport.rowToJson(c, SMS_COLUMNS)
                if (threadIdx >= 0) threads[c.getLong(threadIdx)]?.let { o.put(KEY_RECIPIENTS, JSONArray(it)) }
                w.write(o.toString())
                w.write("\n")
                n++
                progress.bytes(1)
            }
        }
        return n
    }

    private fun dumpMms(w: BufferedWriter, threads: Map<Long, List<String>>, partsDir: File, progress: ProgressSink): Int {
        var n = 0
        var partNo = 0
        val c = cr.query(Telephony.Mms.CONTENT_URI, null, null, null, "${Telephony.Mms.DATE} ASC")
            ?: throw IOException("MMS are not accessible")
        c.use {
            val idIdx = c.getColumnIndexOrThrow(Telephony.Mms._ID)
            val threadIdx = c.getColumnIndex(Telephony.Mms.THREAD_ID)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val o = SpecialSupport.rowToJson(c, MMS_COLUMNS)
                if (threadIdx >= 0) threads[c.getLong(threadIdx)]?.let { o.put(KEY_RECIPIENTS, JSONArray(it)) }
                val addrs = JSONArray()
                cr.query(Uri.parse("content://mms/$id/addr"), null, null, null, null)?.use { a ->
                    while (a.moveToNext()) addrs.put(SpecialSupport.rowToJson(a, ADDR_COLUMNS))
                }
                o.put(KEY_ADDRESSES, addrs)
                val parts = JSONArray()
                cr.query(Uri.parse("content://mms/$id/part"), null, null, null, null)?.use { p ->
                    val pid = p.getColumnIndexOrThrow("_id")
                    val dataIdx = p.getColumnIndex("_data")
                    while (p.moveToNext()) {
                        val part = SpecialSupport.rowToJson(p, PART_COLUMNS)
                        if (dataIdx >= 0 && !p.isNull(dataIdx)) {
                            val name = (partNo++).toString()
                            val ok = try {
                                cr.openInputStream(Uri.parse("content://mms/part/${p.getLong(pid)}"))?.use { input ->
                                    File(partsDir, name).outputStream().use { input.copyTo(it) }
                                    true
                                } ?: false
                            } catch (_: Exception) {
                                false
                            }
                            if (ok) part.put(KEY_DATA_FILE, name)
                        }
                        parts.put(part)
                    }
                }
                o.put(KEY_PARTS, parts)
                w.write(o.toString())
                w.write("\n")
                n++
                progress.bytes(1)
            }
        }
        return n
    }

    fun restore(backup: SpecialBackup, crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        progress.step("Restoring messages", backup.meta.itemCount.toLong())
        val work = File(context.cacheDir, "messages-restore").apply { deleteRecursively(); mkdirs() }
        try {
            val file = backup.meta.files.firstOrNull() ?: throw IOException("Backup is empty")
            BackupIO.open(backup.dir, file, crypto?.key).use { input ->
                val reader = TarReader(input)
                while (true) {
                    val e = reader.nextEntry()
                    if (e == null) {
                        reader.readToEnd() // runs the checksum verification at end of stream
                        break
                    }
                    if (e.type != TarEntry.Type.FILE) continue
                    val safe = when {
                        e.name == "sms.jsonl" || e.name == "mms.jsonl" -> e.name
                        e.name.startsWith("parts/") && e.name.substringAfter("parts/").all { it.isDigit() } -> e.name
                        else -> null
                    } ?: continue
                    val out = File(work, safe)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { reader.entryStream().copyTo(it) }
                }
            }
            val threadCache = HashMap<Set<String>, Long>()
            fun threadId(recipients: Set<String>): Long? {
                if (recipients.isEmpty()) return null
                threadCache[recipients]?.let { return it }
                val id = try {
                    Telephony.Threads.getOrCreateThreadId(context, recipients)
                } catch (_: Exception) {
                    return null
                }
                threadCache[recipients] = id
                return id
            }
            val sms = restoreSms(File(work, "sms.jsonl"), ::threadId, progress)
            val mms = restoreMms(File(work, "mms.jsonl"), File(work, "parts"), ::threadId, progress)
            val warnings = buildList {
                if (sms.failed > 0) add("${sms.failed} SMS could not be restored")
                if (mms.failed > 0) add("${mms.failed} MMS could not be restored")
            }
            val dup = sms.duplicates + mms.duplicates
            val dupText = if (dup > 0) " ($dup already present)" else ""
            return TaskOutcome("Restored ${sms.inserted} SMS and ${mms.inserted} MMS$dupText", warnings)
        } finally {
            work.deleteRecursively()
        }
    }

    private var lastBinaryPartTime = 0L

    private data class Counts(var inserted: Int = 0, var duplicates: Int = 0, var failed: Int = 0)

    private fun recipientsOf(o: JSONObject, fallback: String?): Set<String> {
        val arr = o.optJSONArray(KEY_RECIPIENTS)
        val set = LinkedHashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
        if (set.isEmpty() && !fallback.isNullOrBlank()) set.add(fallback)
        return set
    }

    private fun restoreSms(file: File, threadId: (Set<String>) -> Long?, progress: ProgressSink): Counts {
        val counts = Counts()
        if (!file.isFile) return counts
        val target = SpecialSupport.columnsOf(cr.query(Telephony.Sms.CONTENT_URI, null, "${Telephony.Sms._ID} = -1", null, null))
        if (target.isEmpty()) throw IOException("SMS are not accessible")
        val allowed = SMS_COLUMNS.intersect(target)
        val existing = HashSet<String>()
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, null)?.use { c ->
            while (c.moveToNext()) existing.add(smsKey(c.getLong(0), c.getInt(1), c.getString(2), c.getString(3)))
        }
        val batch = ArrayList<ContentValues>()
        fun flush() {
            if (batch.isEmpty()) return
            try {
                val n = cr.bulkInsert(Telephony.Sms.CONTENT_URI, batch.toTypedArray())
                counts.inserted += n
                counts.failed += batch.size - n
            } catch (_: Exception) {
                for (v in batch) {
                    try {
                        val uri = cr.insert(Telephony.Sms.CONTENT_URI, v)
                        if (uri != null && ContentUris.parseId(uri) > 0) counts.inserted++ else counts.failed++
                    } catch (_: Exception) {
                        counts.failed++
                    }
                }
            }
            batch.clear()
        }
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                progress.bytes(1)
                val o = JSONObject(line)
                val address = if (o.has(Telephony.Sms.ADDRESS)) o.getString(Telephony.Sms.ADDRESS) else null
                val body = if (o.has(Telephony.Sms.BODY)) o.getString(Telephony.Sms.BODY) else null
                val k = smsKey(o.optLong(Telephony.Sms.DATE), o.optInt(Telephony.Sms.TYPE), address, body)
                if (!existing.add(k)) {
                    counts.duplicates++
                    continue
                }
                val v = SpecialSupport.jsonToValues(o, allowed)
                threadId(recipientsOf(o, address))?.let { v.put(Telephony.Sms.THREAD_ID, it) }
                batch.add(v)
                if (batch.size >= 200) flush()
            }
        }
        flush()
        if (counts.inserted == 0 && counts.failed > 0) {
            throw IOException("Android refused to write messages. Allow OpenUp to write SMS in the setup screen (or make it the default SMS app).")
        }
        return counts
    }

    private fun restoreMms(file: File, partsDir: File, threadId: (Set<String>) -> Long?, progress: ProgressSink): Counts {
        val counts = Counts()
        if (!file.isFile) return counts
        val pduCols = SpecialSupport.columnsOf(cr.query(Telephony.Mms.CONTENT_URI, null, "${Telephony.Mms._ID} = -1", null, null))
        if (pduCols.isEmpty()) throw IOException("MMS are not accessible")
        val allowed = MMS_COLUMNS.intersect(pduCols)
        val existing = HashSet<String>()
        cr.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_ID, Telephony.Mms.TRANSACTION_ID),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mid = c.getString(3)?.takeIf { it.isNotEmpty() } ?: c.getString(4)?.takeIf { it.isNotEmpty() }
                // Messages without a message id (e.g. RCS chats stored as MMS, drafts) are told apart
                // by their content.
                val content = if (mid == null) mmsContentKey(existingParts(c.getLong(0))) else null
                existing.add(mmsKey(c.getLong(1), c.getInt(2), mid, content))
            }
        }
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                progress.bytes(1)
                val o = JSONObject(line)
                val mid = o.optString(Telephony.Mms.MESSAGE_ID, "").takeIf { it.isNotEmpty() }
                    ?: o.optString(Telephony.Mms.TRANSACTION_ID, "").takeIf { it.isNotEmpty() }
                val content = if (mid == null) mmsContentKey(jsonParts(o)) else null
                if (!existing.add(mmsKey(o.optLong(Telephony.Mms.DATE), o.optInt(Telephony.Mms.MESSAGE_BOX), mid, content))) {
                    counts.duplicates++
                    continue
                }
                if (insertMms(o, allowed, partsDir, threadId)) counts.inserted++ else counts.failed++
            }
        }
        return counts
    }

    private fun insertMms(o: JSONObject, allowed: Set<String>, partsDir: File, threadId: (Set<String>) -> Long?): Boolean {
        val addrs = o.optJSONArray(KEY_ADDRESSES) ?: JSONArray()
        val addressList = (0 until addrs.length()).mapNotNull { addrs.optJSONObject(it)?.optString("address") }
            .filter { it.isNotBlank() && it != "insert-address-token" }
        val values = SpecialSupport.jsonToValues(o, allowed)
        val tid = threadId(recipientsOf(o, null).ifEmpty { addressList.toSet() })
        if (tid != null) values.put(Telephony.Mms.THREAD_ID, tid)
        val uri = try {
            cr.insert(Telephony.Mms.CONTENT_URI, values)
        } catch (_: Exception) {
            null
        } ?: return false
        val id = try {
            ContentUris.parseId(uri)
        } catch (_: Exception) {
            -1L
        }
        if (id <= 0) return false
        try {
            for (i in 0 until addrs.length()) {
                val a = addrs.optJSONObject(i) ?: continue
                cr.insert(Uri.parse("content://mms/$id/addr"), SpecialSupport.jsonToValues(a, ADDR_COLUMNS))
            }
            val parts = o.optJSONArray(KEY_PARTS) ?: JSONArray()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val values = SpecialSupport.jsonToValues(p, PART_COLUMNS)
                val dataName = p.optString(KEY_DATA_FILE, "")
                val data = if (dataName.isNotEmpty()) File(partsDir, dataName).takeIf { it.isFile } else null
                // The provider keeps text and SMIL parts inline and never creates a file for them.
                val inline = p.optString("ct") == "text/plain" || p.optString("ct") == "application/smil"
                if (inline) {
                    if (!values.containsKey("text") && data != null) values.put("text", data.readText(Charsets.UTF_8))
                } else {
                    // The provider names part files after the current millisecond; make sure two
                    // attachments never get the same name.
                    while (System.currentTimeMillis() == lastBinaryPartTime) Thread.sleep(1)
                    lastBinaryPartTime = System.currentTimeMillis()
                }
                val partUri = cr.insert(Uri.parse("content://mms/$id/part"), values)
                    ?: throw IOException("part insert failed")
                if (!inline && data != null) {
                    val out = cr.openOutputStream(partUri) ?: throw IOException("cannot write part")
                    out.use { o2 -> data.inputStream().use { it.copyTo(o2) } }
                }
            }
            return true
        } catch (_: Exception) {
            // Do not leave a half restored MMS behind.
            try {
                cr.delete(uri, null, null)
            } catch (_: Exception) {
            }
            return false
        }
    }

    private fun smsKey(date: Long, type: Int, address: String?, body: String?) =
        "$date|$type|${address.orEmpty()}|${body?.hashCode() ?: 0}|${body?.length ?: -1}"

    private fun mmsKey(date: Long, box: Int, mid: String?, content: String?) =
        if (mid != null) "id|$date|$box|$mid" else "content|$date|$box|${content.orEmpty()}"

    private fun existingParts(mmsId: Long): List<MmsPartKey> {
        val result = ArrayList<MmsPartKey>()
        try {
            cr.query(Uri.parse("content://mms/$mmsId/part"), arrayOf("seq", "ct", "text", "cl", "name"), null, null, null)?.use { p ->
                while (p.moveToNext()) {
                    result.add(MmsPartKey(p.getInt(0), p.getString(1), p.getString(2), p.getString(3), p.getString(4)))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun jsonParts(o: JSONObject): List<MmsPartKey> {
        val parts = o.optJSONArray(KEY_PARTS) ?: return emptyList()
        return (0 until parts.length()).mapNotNull { i ->
            val p = parts.optJSONObject(i) ?: return@mapNotNull null
            fun str(k: String) = if (p.has(k) && !p.isNull(k)) p.getString(k) else null
            MmsPartKey(p.optInt("seq"), str("ct"), str("text"), str("cl"), str("name"))
        }
    }

    companion object {
        private val CANONICAL_URI: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        private const val KEY_RECIPIENTS = "__recipients"
        private const val KEY_ADDRESSES = "__addresses"
        private const val KEY_PARTS = "__parts"
        private const val KEY_DATA_FILE = "__data"

        val SMS_COLUMNS = setOf(
            "address", "date", "date_sent", "protocol", "read", "status", "type", "reply_path_present",
            "subject", "body", "service_center", "locked", "error_code", "seen",
        )
        val MMS_COLUMNS = setOf(
            "date", "date_sent", "msg_box", "read", "m_id", "sub", "sub_cs", "ct_t", "ct_l", "exp", "m_cls",
            "m_type", "v", "m_size", "pri", "rr", "rpt_a", "resp_st", "st", "tr_id", "retr_st", "retr_txt",
            "retr_txt_cs", "read_status", "ct_cls", "resp_txt", "d_tm", "d_rpt", "locked", "seen", "text_only",
        )
        val ADDR_COLUMNS = setOf("address", "type", "charset")
        val PART_COLUMNS = setOf("seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text")
    }
}
