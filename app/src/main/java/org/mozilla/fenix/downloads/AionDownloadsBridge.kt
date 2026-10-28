package org.mozilla.fenix.downloads

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.manager.DownloadManager
import mozilla.components.feature.downloads.manager.onDownloadStopped

/**
 * Hands browser downloads to the separate Aion Downloads app (multipart, resume, scheduler, torrents)
 * when it is installed and the "Use Aion Downloads" setting is on. Anything it can't take (blob:,
 * data:, private tabs) and every download when the app is missing goes to the built-in manager.
 */
class AionDownloadsBridge(
    private val context: Context,
    private val fallback: DownloadManager,
) : DownloadManager by fallback {

    override var onDownloadStopped: onDownloadStopped
        get() = fallback.onDownloadStopped
        set(value) { fallback.onDownloadStopped = value }

    override fun download(download: DownloadState, cookie: String): String? {
        if (!shouldForward(context, download)) return fallback.download(download, cookie)
        val intent = Intent(ACTION_ADD, Uri.parse(download.url))
            .setComponent(ComponentName(PACKAGE, "$PACKAGE.AddActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("file_name", download.fileName)
            .putExtra("referer", download.referrerUrl)
            .putExtra("user_agent", download.userAgent)
            .putExtra("mime", download.contentType)
        if (cookie.isNotBlank()) intent.putExtra("cookies", cookie)
        return runCatching { context.startActivity(intent); download.id }
            .getOrElse { fallback.download(download, cookie) }
    }

    companion object {
        const val PACKAGE = "com.nath.aion.downloads"
        const val ACTION_ADD = "com.nath.aion.downloads.ADD"
        const val PREF_KEY = "pref_key_aion_downloads_app"

        fun isInstalled(context: Context) = runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0); true
        }.getOrDefault(false)

        fun isEnabled(context: Context) =
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, true)

        /** Opens the Aion Downloads app if installed and enabled. Returns false to fall back to Aion's list. */
        fun openApp(context: Context): Boolean {
            if (!isEnabled(context) || !isInstalled(context)) return false
            val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
            return runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }

        fun shouldForward(context: Context, download: DownloadState): Boolean {
            val scheme = Uri.parse(download.url).scheme?.lowercase()
            return (scheme == "http" || scheme == "https") && !download.private &&
                isEnabled(context) && isInstalled(context)
        }
    }
}
