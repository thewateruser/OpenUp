package org.openup.shell

import android.content.AttributionSource
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.BitSet

/**
 * Reads and writes saved Wi-Fi networks through the hidden IWifiManager binder interface.
 *
 * The shell user holds READ_WIFI_CREDENTIAL and NETWORK_SETTINGS, which is what makes this possible
 * without root. Method signatures differ between Android releases, so methods are resolved by name
 * and their arguments are filled in by parameter type.
 */
@Suppress("DEPRECATION")
internal object WifiOps {
    // WifiConfiguration.KeyMgmt bit indexes (stable across releases).
    private const val KM_NONE = 0
    private const val KM_WPA_PSK = 1
    private const val KM_WPA_EAP = 2
    private const val KM_IEEE8021X = 3
    private const val KM_WPA2_PSK = 4
    private const val KM_SAE = 8
    private const val KM_OWE = 9
    private const val KM_SUITE_B_192 = 10
    private const val KM_WPA_EAP_SHA256 = 12

    private fun wifiService(): Any {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "wifi") as? IBinder
            ?: throw IllegalStateException("Wi-Fi service is not available")
        val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw IllegalStateException("Wi-Fi service is not available")
    }

    private fun callerPackage(uid: Int) = if (uid == 0) "root" else "com.android.shell"

    private fun extras(uid: Int): Bundle {
        val b = Bundle()
        if (Build.VERSION.SDK_INT >= 31) {
            val key = try {
                Class.forName("android.net.wifi.WifiManager").getField("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE").get(null) as String
            } catch (_: Throwable) {
                "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE"
            }
            val source = AttributionSource.Builder(uid).setPackageName(callerPackage(uid)).build()
            b.putParcelable(key, source)
        }
        return b
    }

    private fun buildArgs(m: Method, uid: Int, config: WifiConfiguration? = null): Array<Any?> {
        var stringIndex = 0
        return m.parameterTypes.map { type ->
            when {
                type == String::class.java -> if (stringIndex++ == 0) callerPackage(uid) else null
                type == Bundle::class.java -> extras(uid)
                type == WifiConfiguration::class.java -> config
                else -> throw UnsupportedOperationException("Unsupported signature for ${m.name}: ${m.parameterTypes.joinToString { it.simpleName }}")
            }
        }.toTypedArray()
    }

    private fun findMethod(target: Any, name: String): Method =
        target.javaClass.methods.filter { it.name == name }.minByOrNull { it.parameterTypes.size }
            ?: throw UnsupportedOperationException("$name is not available on this Android version")

    fun backup(uid: Int): String {
        val wifi = wifiService()
        val m = findMethod(wifi, "getPrivilegedConfiguredNetworks")
        val result = m.invoke(wifi, *buildArgs(m, uid))
            ?: throw SecurityException("Android refused to share saved Wi-Fi networks")
        val list: List<*> = when (result) {
            is List<*> -> result
            else -> result.javaClass.getMethod("getList").invoke(result) as List<*>
        }
        val arr = JSONArray()
        for (item in list) {
            val c = item as? WifiConfiguration ?: continue
            toJson(c)?.let { arr.put(it) }
        }
        return arr.toString()
    }

    private fun unquote(s: String?): String? =
        if (s != null && s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else null

    private fun toJson(c: WifiConfiguration): JSONObject? {
        val rawSsid = c.SSID ?: return null
        val km: BitSet = c.allowedKeyManagement
        val psk = c.preSharedKey?.takeIf { it.isNotEmpty() && it != "*" }
        val wepIndex = c.wepTxKeyIndex
        val wepKeys = c.wepKeys?.map { it?.takeIf { k -> k.isNotEmpty() && k != "*" } }
        val security = when {
            km.get(KM_WPA_EAP) || km.get(KM_IEEE8021X) || km.get(KM_SUITE_B_192) || km.get(KM_WPA_EAP_SHA256) -> "eap"
            psk != null && km.get(KM_SAE) && !km.get(KM_WPA_PSK) && !km.get(KM_WPA2_PSK) -> "wpa3"
            psk != null -> "wpa2"
            km.get(KM_OWE) -> "owe"
            wepKeys != null && wepKeys.any { it != null } -> "wep"
            else -> "open"
        }
        return JSONObject().apply {
            put("rawSsid", rawSsid)
            put("ssid", unquote(rawSsid) ?: rawSsid)
            put("security", security)
            put("hidden", c.hiddenSSID)
            if (psk != null) put("rawPsk", psk)
            if (security == "wep" && wepKeys != null) {
                put("wepKeys", JSONArray(wepKeys.map { it ?: JSONObject.NULL }))
                put("wepTxKeyIndex", wepIndex)
            }
        }
    }

    /** Returns (restored, messages). Each message explains a network that could not be restored. */
    fun restore(uid: Int, json: String): Pair<Int, List<String>> {
        val arr = JSONArray(json)
        var restored = 0
        val messages = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ssid = o.optString("ssid")
            try {
                when (o.optString("security")) {
                    "eap" -> {
                        messages.add("$ssid: enterprise (EAP) networks need certificates and cannot be restored automatically")
                        continue
                    }
                }
                if (Build.VERSION.SDK_INT >= 31 && tryShellCommand(o)) {
                    restored++
                    continue
                }
                addViaBinder(uid, o)
                restored++
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                messages.add("$ssid: ${cause.message ?: cause.javaClass.simpleName}")
            }
        }
        return restored to messages
    }

    /** Uses `cmd wifi add-network`, available since Android 12, for the common network types. */
    private fun tryShellCommand(o: JSONObject): Boolean {
        val raw = o.getString("rawSsid")
        val ssid = unquote(raw) ?: return false // hex SSIDs are not supported by the shell command
        val type = o.getString("security")
        val psk = o.optString("rawPsk", "")
        val args = ArrayList(listOf("/system/bin/cmd", "wifi", "add-network", ssid))
        when (type) {
            "open", "owe" -> args.add(type)
            "wpa2", "wpa3" -> {
                val pass = unquote(psk) ?: return false // raw hex PSKs need the binder path
                args.add(type)
                args.add(pass)
            }
            else -> return false
        }
        val hidden = o.optBoolean("hidden")
        if (hidden) args.add("-h")
        var result = ShellService.runCommand(args.toTypedArray(), null)
        if (result.exitCode != 0 && hidden) {
            // Older releases do not know -h; retry without it.
            args.removeAt(args.size - 1)
            result = ShellService.runCommand(args.toTypedArray(), null)
        }
        val output = (result.stdout + result.stderr).lowercase()
        return result.exitCode == 0 && !output.contains("fail") && !output.contains("error") && !output.contains("exception")
    }

    private fun addViaBinder(uid: Int, o: JSONObject) {
        val c = WifiConfiguration()
        c.SSID = o.getString("rawSsid")
        c.hiddenSSID = o.optBoolean("hidden")
        val psk = if (o.has("rawPsk")) o.getString("rawPsk") else null
        when (val type = o.getString("security")) {
            "open" -> setSecurity(c, 0 /* SECURITY_TYPE_OPEN */, KM_NONE)
            "owe" -> setSecurity(c, 6 /* SECURITY_TYPE_OWE */, KM_OWE)
            "wpa2" -> {
                setSecurity(c, 2 /* SECURITY_TYPE_PSK */, KM_WPA_PSK)
                c.preSharedKey = psk ?: throw IllegalArgumentException("missing password")
            }
            "wpa3" -> {
                setSecurity(c, 4 /* SECURITY_TYPE_SAE */, KM_SAE)
                c.preSharedKey = psk ?: throw IllegalArgumentException("missing password")
            }
            "wep" -> {
                setSecurity(c, 1 /* SECURITY_TYPE_WEP */, KM_NONE)
                val keys = o.optJSONArray("wepKeys") ?: throw IllegalArgumentException("missing WEP keys")
                val target = c.wepKeys
                for (k in 0 until minOf(keys.length(), target.size)) {
                    target[k] = if (keys.isNull(k)) null else keys.getString(k)
                }
                c.wepTxKeyIndex = o.optInt("wepTxKeyIndex", 0)
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
            }
            else -> throw IllegalArgumentException("unknown security type $type")
        }
        val wifi = wifiService()
        val m = findMethod(wifi, "addOrUpdateNetwork")
        val netId = m.invoke(wifi, *buildArgs(m, uid, c))
        val id = (netId as? Int) ?: -1
        if (id < 0) throw IllegalStateException("Android rejected the network")
    }

    private fun setSecurity(c: WifiConfiguration, securityType: Int, keyMgmtBit: Int) {
        if (Build.VERSION.SDK_INT >= 30) {
            c.setSecurityParams(securityType)
        } else {
            c.allowedKeyManagement.clear()
            c.allowedKeyManagement.set(keyMgmtBit)
        }
    }
}
