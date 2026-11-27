package org.openup.data.apps

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openup.data.model.AppOpMode

class AppSettingsOpsTest {
    @Test
    fun parsesPerOpAppopsOutput() {
        val out = """
            ##openup-op:GET_USAGE_STATS
            GET_USAGE_STATS: allow; time=+3d2h ago
            ##openup-op:RUN_ANY_IN_BACKGROUND
            No operations.
            Default mode: allow
            ##openup-op:SYSTEM_ALERT_WINDOW
            Uid mode: SYSTEM_ALERT_WINDOW: allow
            SYSTEM_ALERT_WINDOW: ignore; rejectTime=+1m ago
            ##openup-op:MANAGE_EXTERNAL_STORAGE
            Uid mode: MANAGE_EXTERNAL_STORAGE: default
            ##openup-op:WRITE_SETTINGS
            WRITE_SETTINGS: deny (running)
            ##openup-op:NOT_AN_OP
            NOT_AN_OP: allow
        """.trimIndent()
        assertEquals(
            listOf(
                AppOpMode("GET_USAGE_STATS", "allow", false),
                AppOpMode("SYSTEM_ALERT_WINDOW", "allow", true),
                AppOpMode("SYSTEM_ALERT_WINDOW", "ignore", false),
                AppOpMode("WRITE_SETTINGS", "deny", false),
            ),
            AppSettingsOps.parseAppOps(out),
        )
    }

    @Test
    fun onlyTheAppsOwnAccessibilityServicesAreRestored() {
        val services = listOf("com.a/.Svc", "com.evil/.Spy", "com.a/com.a.Other", "com.a.fake/.X", "garbage", "com.a/../x")
        assertEquals(listOf("com.a/.Svc", "com.a/com.a.Other"), AppSettingsOps.ownServices("com.a", services))
    }

    @Test
    fun parsesAccessibilityList() {
        assertEquals(emptyList<String>(), AppSettingsOps.parseServiceList("null\n"))
        assertEquals(emptyList<String>(), AppSettingsOps.parseServiceList(""))
        assertEquals(
            listOf("com.a/.Svc", "com.b/com.b.x.Svc"),
            AppSettingsOps.parseServiceList("com.a/.Svc:com.b/com.b.x.Svc\n"),
        )
    }
}
