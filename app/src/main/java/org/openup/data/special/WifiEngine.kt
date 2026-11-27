package org.openup.data.special

import org.json.JSONArray
import org.openup.data.BackupIO
import org.openup.data.BackupStorage
import org.openup.data.CryptoSession
import org.openup.data.ProgressSink
import org.openup.data.SettingsRepository
import org.openup.data.TaskOutcome
import org.openup.data.model.SpecialBackup
import org.openup.data.model.SpecialType
import org.openup.shizuku.ShellClient
import java.io.IOException

/** Saved Wi-Fi networks including passwords, via the Shizuku shell service. */
class WifiEngine(
    settings: SettingsRepository,
    storage: BackupStorage,
    private val shell: ShellClient,
) {
    private val support = SpecialSupport(settings, storage)

    suspend fun backup(crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        progress.step("Backing up Wi-Fi networks")
        val json = shell.wifiBackup()
        val count = JSONArray(json).length()
        if (count == 0) throw IOException("No saved Wi-Fi networks were found")
        support.create(SpecialType.WIFI, crypto, { dir ->
            val stored = support.writeFile(dir, "wifi.json", crypto, compress = true) { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            Triple(listOf(stored), count to "$count networks", Unit)
        })
        val warnings = if (crypto == null) {
            listOf("Wi-Fi passwords are stored unencrypted. Set a backup password in Settings to protect them.")
        } else {
            emptyList()
        }
        return TaskOutcome("Backed up $count Wi-Fi networks", warnings)
    }

    suspend fun restore(backup: SpecialBackup, crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        progress.step("Restoring Wi-Fi networks")
        val file = backup.meta.files.firstOrNull() ?: throw IOException("Backup is empty")
        val json = BackupIO.open(backup.dir, file, crypto?.key).use { it.readBytes().toString(Charsets.UTF_8) }
        val (restored, messages) = shell.wifiRestore(json)
        return TaskOutcome("Restored $restored Wi-Fi networks", messages)
    }
}
