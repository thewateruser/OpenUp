package org.openup

import android.app.Application
import org.openup.data.BackupStorage
import org.openup.data.PasswordStore
import org.openup.data.Privileges
import org.openup.data.SettingsRepository
import org.openup.data.apps.AppBackupEngine
import org.openup.data.apps.AppRestoreEngine
import org.openup.data.apps.InstalledApps
import org.openup.data.special.CallLogEngine
import org.openup.data.special.FolderEngine
import org.openup.data.special.MessagesEngine
import org.openup.data.special.WallpaperEngine
import org.openup.data.special.WifiEngine
import org.openup.jobs.JobManager
import org.openup.jobs.Notifications
import org.openup.jobs.ScheduleManager
import org.openup.shizuku.ShellClient
import org.openup.shizuku.ShizukuManager

class OpenUpApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.shizuku.start()
        Notifications.createChannels(this)
    }
}

/** Manual dependency container; one instance per process. */
class AppContainer(val app: Application) {
    val settings = SettingsRepository(app)
    val passwords = PasswordStore(app)
    val storage = BackupStorage(settings)
    val shizuku = ShizukuManager(app)
    val shell = ShellClient(shizuku, app.packageName)
    val installedApps = InstalledApps(app)
    val privileges = Privileges(app, shell)
    val appBackup = AppBackupEngine(app, settings, storage, installedApps, shell)
    val appRestore = AppRestoreEngine(app, settings, installedApps, shell)
    val messages = MessagesEngine(app, settings, storage)
    val callLogs = CallLogEngine(app, settings, storage)
    val wallpapers = WallpaperEngine(app, settings, storage)
    val wifi = WifiEngine(settings, storage, shell)
    val folders = FolderEngine(settings, storage, shell, shizuku)
    val jobs = JobManager(this)
    val schedule = ScheduleManager(app, settings)
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as OpenUpApp).container
