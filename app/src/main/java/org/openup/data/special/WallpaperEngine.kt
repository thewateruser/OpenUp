package org.openup.data.special

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import org.openup.data.BackupIO
import org.openup.data.BackupStorage
import org.openup.data.CryptoSession
import org.openup.data.ProgressSink
import org.openup.data.SettingsRepository
import org.openup.data.TaskOutcome
import org.openup.data.model.SpecialBackup
import org.openup.data.model.SpecialType
import org.openup.data.model.StoredFile
import java.io.IOException

/** Home and lock screen wallpapers, through the public WallpaperManager API. */
class WallpaperEngine(
    private val context: Context,
    settings: SettingsRepository,
    storage: BackupStorage,
) {
    private val support = SpecialSupport(settings, storage)

    private fun file(wm: WallpaperManager, which: Int): ParcelFileDescriptor? = try {
        wm.getWallpaperFile(which)
    } catch (e: SecurityException) {
        throw IOException("Android denied access to the wallpaper. Grant OpenUp storage access in the setup screen.", e)
    }

    fun backup(crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        progress.step("Backing up wallpapers")
        val wm = WallpaperManager.getInstance(context)
        val summary = support.create(SpecialType.WALLPAPERS, crypto, { dir ->
            val files = ArrayList<StoredFile>()
            val parts = ArrayList<String>()
            val extras = HashMap<String, String>()
            val live = wm.wallpaperInfo
            if (live != null) {
                extras[EXTRA_HOME_LIVE] = live.component.flattenToString()
                parts.add("live wallpaper (${live.packageName})")
            } else {
                val home = file(wm, WallpaperManager.FLAG_SYSTEM)
                if (home != null) {
                    files.add(support.writeFile(dir, "home.img", crypto, compress = false) { out ->
                        ParcelFileDescriptor.AutoCloseInputStream(home).use { it.copyTo(out) }
                    })
                } else {
                    // No custom image: store whatever is shown (the built-in default).
                    val bitmap = try {
                        wm.drawable?.toBitmap()
                    } catch (e: SecurityException) {
                        throw IOException("Android denied access to the wallpaper. Grant OpenUp storage access in the setup screen.", e)
                    }
                    if (bitmap != null) {
                        files.add(support.writeFile(dir, "home.img", crypto, compress = false) { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        })
                    }
                }
                if (files.isNotEmpty()) parts.add("home screen")
            }
            val lock = file(wm, WallpaperManager.FLAG_LOCK)
            if (lock != null) {
                files.add(support.writeFile(dir, "lock.img", crypto, compress = false) { out ->
                    ParcelFileDescriptor.AutoCloseInputStream(lock).use { it.copyTo(out) }
                })
                parts.add("lock screen")
            } else {
                extras[EXTRA_LOCK_SAME] = "true"
            }
            if (files.isEmpty() && live == null) throw IOException("No wallpaper could be read")
            val text = parts.joinToString(" + ").replaceFirstChar { it.uppercase() } +
                if (lock == null) " (lock screen uses the same)" else ""
            Triple(files, files.size.coerceAtLeast(1) to text, text to extras)
        }, extras = { it.second })
        return TaskOutcome("Backed up ${summary.first}")
    }

    fun restore(backup: SpecialBackup, crypto: CryptoSession?, progress: ProgressSink): TaskOutcome {
        progress.step("Restoring wallpapers")
        val wm = WallpaperManager.getInstance(context)
        val meta = backup.meta
        val home = meta.files.firstOrNull { it.file.startsWith("home") }
        val lock = meta.files.firstOrNull { it.file.startsWith("lock") }
        val lockSame = meta.extras[EXTRA_LOCK_SAME] == "true"
        val done = ArrayList<String>()
        val warnings = ArrayList<String>()
        if (home != null) {
            val which = if (lockSame && lock == null) WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            val id = BackupIO.open(backup.dir, home, crypto?.key).use { wm.setStream(it, null, true, which) }
            if (id == 0) throw IOException("Android rejected the home screen wallpaper")
            done.add(if (which and WallpaperManager.FLAG_LOCK != 0) "home and lock screen" else "home screen")
        }
        if (lock != null) {
            val id = BackupIO.open(backup.dir, lock, crypto?.key).use { wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK) }
            if (id == 0) throw IOException("Android rejected the lock screen wallpaper")
            done.add("lock screen")
        }
        meta.extras[EXTRA_HOME_LIVE]?.let {
            warnings.add("The home screen used a live wallpaper ($it). Live wallpapers must be chosen again in the wallpaper picker.")
        }
        val summary = if (done.isEmpty()) "No wallpaper image to restore" else "Restored " + done.joinToString(" and ")
        return TaskOutcome(summary, warnings)
    }

    companion object {
        const val EXTRA_HOME_LIVE = "homeLiveComponent"
        const val EXTRA_LOCK_SAME = "lockSameAsHome"
    }
}
