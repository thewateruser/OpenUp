package org.openup.data.apps

import org.openup.data.SettingsRepository
import org.openup.data.model.AppOpMode
import org.openup.data.model.AppSettingsBackup
import org.openup.shizuku.ShellClient

/**
 * Reads and restores per-app settings that live outside the app's own data and that the shell
 * user may change: special app access (app-ops), accessibility services and battery optimisation.
 * Notification settings and the SSAID are stored in system files that need root.
 */
class AppSettingsOps(private val shell: ShellClient) {
    private val user get() = SettingsRepository.userId().toString()

    suspend fun capture(pkg: String): AppSettingsBackup {
        require(PACKAGE_REGEX.matches(pkg)) { "invalid package name" }
        // One shell invocation for all ops. Each op is queried on its own because `appops get`
        // only labels the first uid-wide entry of a listing.
        val script = OPS.joinToString("; ") { op -> "echo '$MARKER$op'; /system/bin/cmd appops get --user $user $pkg $op 2>/dev/null" }
        val opsOut = shell.exec("/system/bin/sh", "-c", script).stdout
        val modes = parseAppOps(opsOut)

        val enabled = shell.exec(CMD, "settings", "--user", user, "get", "secure", ACCESSIBILITY_KEY).stdout
        val services = parseServiceList(enabled).filter { it.substringBefore('/') == pkg }

        val whitelist = shell.exec(CMD, "deviceidle", "whitelist").stdout
        val exempt = whitelist.lineSequence().any { it.trim().startsWith("user,$pkg,") }

        return AppSettingsBackup(modes, services, exempt)
    }

    /** Returns (applied, failed). */
    suspend fun restore(pkg: String, settings: AppSettingsBackup): Pair<Int, Int> {
        var applied = 0
        var failed = 0
        for (m in settings.appOps) {
            if (m.op !in OPS || m.mode !in MODES) continue
            val args = arrayListOf(CMD, "appops", "set", "--user", user)
            if (m.uid) args.add("--uid")
            args += listOf(pkg, m.op, m.mode)
            val r = shell.exec(*args.toTypedArray())
            if (r.isSuccess && !r.output.contains("Error", ignoreCase = true)) applied++ else failed++
        }
        if (settings.accessibilityServices.isNotEmpty()) {
            val current = parseServiceList(
                shell.exec(CMD, "settings", "--user", user, "get", "secure", ACCESSIBILITY_KEY).stdout
            )
            val merged = LinkedHashSet(current).apply { addAll(settings.accessibilityServices) }
            val put = shell.exec(CMD, "settings", "--user", user, "put", "secure", ACCESSIBILITY_KEY, merged.joinToString(":"))
            val on = shell.exec(CMD, "settings", "--user", user, "put", "secure", "accessibility_enabled", "1")
            if (put.isSuccess && on.isSuccess) applied += settings.accessibilityServices.size else failed += settings.accessibilityServices.size
        }
        if (settings.batteryOptimizationExempt) {
            val r = shell.exec(CMD, "deviceidle", "whitelist", "+$pkg")
            if (r.isSuccess && r.stdout.contains("Added")) applied++ else failed++
        }
        return applied to failed
    }

    companion object {
        private const val CMD = "/system/bin/cmd"
        private const val MARKER = "##openup-op:"
        const val ACCESSIBILITY_KEY = "enabled_accessibility_services"
        private val PACKAGE_REGEX = Regex("^[A-Za-z0-9_.]+$")
        private val MODES = setOf("allow", "ignore", "deny", "foreground")

        /** Special app access that is stored as app-ops. */
        val OPS = listOf(
            "GET_USAGE_STATS", // usage access
            "RUN_ANY_IN_BACKGROUND", // battery: "Restricted"
            "RUN_IN_BACKGROUND",
            "SYSTEM_ALERT_WINDOW", // display over other apps
            "WRITE_SETTINGS", // modify system settings
            "REQUEST_INSTALL_PACKAGES", // install unknown apps
            "MANAGE_EXTERNAL_STORAGE", // all files access
            "SCHEDULE_EXACT_ALARM", // alarms & reminders
            "PICTURE_IN_PICTURE",
            "MANAGE_MEDIA",
            "USE_FULL_SCREEN_INTENT",
        )

        /** Parses the output of the per-op `appops get` script into explicitly set modes. */
        fun parseAppOps(output: String): List<AppOpMode> {
            val result = ArrayList<AppOpMode>()
            var op: String? = null
            for (raw in output.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith(MARKER)) {
                    op = line.removePrefix(MARKER).takeIf { it in OPS }
                    continue
                }
                val current = op ?: continue
                val uid = line.startsWith("Uid mode: ")
                val body = line.removePrefix("Uid mode: ")
                if (!body.startsWith("$current: ")) continue
                val mode = body.removePrefix("$current: ").substringBefore(';').substringBefore(' ').trim()
                if (mode in MODES && result.none { it.op == current && it.uid == uid }) {
                    result.add(AppOpMode(current, mode, uid))
                }
            }
            return result
        }

        fun parseServiceList(value: String): List<String> {
            val v = value.trim()
            if (v.isEmpty() || v == "null") return emptyList()
            return v.split(':').map { it.trim() }.filter { it.contains('/') }
        }
    }
}
