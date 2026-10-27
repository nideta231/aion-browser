/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.downloads

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.fenix.webapp.WebAppInstallReceiver
import org.mozilla.fenix.webapp.WebAppInstaller

/**
 * Installs the bundled Aion Downloads app (download manager with scheduler, torrents, HLS) as its
 * own launcher app, or asks the system to remove it. The APK ships inside Aion's assets.
 */
object AionDownloadsInstaller {
    private const val ASSET = "aion-downloads.apk"
    private const val LABEL = "Aion Downloads"

    suspend fun install(context: Context) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(AionDownloadsBridge.PACKAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            context.assets.open(ASSET).use { input ->
                session.openWrite("base.apk", 0, -1).use { out ->
                    input.copyTo(out, 1 shl 16)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, WebAppInstallReceiver::class.java).putExtra(WebAppInstaller.EXTRA_LABEL, LABEL)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    fun uninstall(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:${AionDownloadsBridge.PACKAGE}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
