package org.openup.data

import android.content.Context
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openup.core.io.Compression

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppParts(
    val apk: Boolean = true,
    val extData: Boolean = true,
    val obb: Boolean = true,
    val media: Boolean = true,
    val permissions: Boolean = true,
) {
    val any get() = apk || extData || obb || media
}

data class ScheduleSettings(
    val enabled: Boolean = false,
    val intervalHours: Int = 24,
    val requireCharging: Boolean = true,
    val apps: Boolean = true,
    val includeSystemApps: Boolean = false,
    val onlyChangedApps: Boolean = true,
    val messages: Boolean = true,
    val callLogs: Boolean = true,
    val wallpapers: Boolean = false,
    val wifi: Boolean = false,
    val folders: Boolean = true,
)

data class AppSettings(
    val backupDir: String,
    val compression: Compression = Compression.ZSTD,
    val compressionLevel: Int = 3,
    val keepVersions: Int = 2,
    val excludeCache: Boolean = true,
    val parts: AppParts = AppParts(),
    val restoreInstaller: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val schedule: ScheduleSettings = ScheduleSettings(),
    /** Folders (absolute paths) that the user wants to back up. */
    val folders: List<String> = emptyList(),
)

/** Persistent user settings, exposed as a [StateFlow]. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun load(): AppSettings {
        val d = AppSettings(backupDir = defaultBackupDir())
        val s = ScheduleSettings()
        val p = AppParts()
        return AppSettings(
            backupDir = prefs.getString("backupDir", null)?.takeIf { it.isNotBlank() } ?: d.backupDir,
            compression = runCatching { Compression.valueOf(prefs.getString("compression", d.compression.name)!!) }
                .getOrDefault(d.compression),
            compressionLevel = prefs.getInt("compressionLevel", d.compressionLevel).coerceIn(1, 19),
            keepVersions = prefs.getInt("keepVersions", d.keepVersions).coerceIn(1, MAX_VERSIONS),
            excludeCache = prefs.getBoolean("excludeCache", d.excludeCache),
            parts = AppParts(
                apk = prefs.getBoolean("part_apk", p.apk),
                extData = prefs.getBoolean("part_extData", p.extData),
                obb = prefs.getBoolean("part_obb", p.obb),
                media = prefs.getBoolean("part_media", p.media),
                permissions = prefs.getBoolean("part_permissions", p.permissions),
            ),
            restoreInstaller = prefs.getBoolean("restoreInstaller", d.restoreInstaller),
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            schedule = ScheduleSettings(
                enabled = prefs.getBoolean("sch_enabled", s.enabled),
                intervalHours = prefs.getInt("sch_interval", s.intervalHours).coerceIn(1, 24 * 30),
                requireCharging = prefs.getBoolean("sch_charging", s.requireCharging),
                apps = prefs.getBoolean("sch_apps", s.apps),
                includeSystemApps = prefs.getBoolean("sch_system", s.includeSystemApps),
                onlyChangedApps = prefs.getBoolean("sch_changed", s.onlyChangedApps),
                messages = prefs.getBoolean("sch_messages", s.messages),
                callLogs = prefs.getBoolean("sch_calllogs", s.callLogs),
                wallpapers = prefs.getBoolean("sch_wallpapers", s.wallpapers),
                wifi = prefs.getBoolean("sch_wifi", s.wifi),
                folders = prefs.getBoolean("sch_folders", s.folders),
            ),
            folders = prefs.getString("folders", null)?.let { raw ->
                runCatching {
                    val arr = org.json.JSONArray(raw)
                    List(arr.length()) { arr.getString(it) }
                }.getOrNull()
            } ?: emptyList(),
        )
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(_state.value)
        prefs.edit()
            .putString("backupDir", n.backupDir)
            .putString("compression", n.compression.name)
            .putInt("compressionLevel", n.compressionLevel.coerceIn(1, 19))
            .putInt("keepVersions", n.keepVersions.coerceIn(1, MAX_VERSIONS))
            .putBoolean("excludeCache", n.excludeCache)
            .putBoolean("part_apk", n.parts.apk)
            .putBoolean("part_extData", n.parts.extData)
            .putBoolean("part_obb", n.parts.obb)
            .putBoolean("part_media", n.parts.media)
            .putBoolean("part_permissions", n.parts.permissions)
            .putBoolean("restoreInstaller", n.restoreInstaller)
            .putString("theme", n.theme.name)
            .putBoolean("dynamicColor", n.dynamicColor)
            .putBoolean("sch_enabled", n.schedule.enabled)
            .putInt("sch_interval", n.schedule.intervalHours)
            .putBoolean("sch_charging", n.schedule.requireCharging)
            .putBoolean("sch_apps", n.schedule.apps)
            .putBoolean("sch_system", n.schedule.includeSystemApps)
            .putBoolean("sch_changed", n.schedule.onlyChangedApps)
            .putBoolean("sch_messages", n.schedule.messages)
            .putBoolean("sch_calllogs", n.schedule.callLogs)
            .putBoolean("sch_wallpapers", n.schedule.wallpapers)
            .putBoolean("sch_wifi", n.schedule.wifi)
            .putBoolean("sch_folders", n.schedule.folders)
            .putString("folders", org.json.JSONArray(n.folders.distinct()).toString())
            .apply()
        _state.value = load()
    }

    companion object {
        const val MAX_VERSIONS = 10

        fun userId(): Int = Process.myUid() / 100_000

        fun defaultBackupDir(): String = "/storage/emulated/${userId()}/OpenUp"
    }
}
