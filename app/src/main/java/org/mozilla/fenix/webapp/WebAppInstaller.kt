/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Builds a per-site APK from the bundled template and hands it to the system installer. */
object WebAppInstaller {
    private const val KEY_ALIAS = "aion_webapp_signing"
    private const val TEMPLATE_ASSET = "webapp-template.apk"
    const val EXTRA_LABEL = "org.mozilla.fenix.webapp.LABEL"

    /** music.youtube.com -> com.nath.aion.app.music_youtube_com */
    fun packageNameFor(url: String): String {
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: "site"
        val parts = host.split('.', '-')
            .map { part -> part.filter { it in 'a'..'z' || it in '0'..'9' } }
            .filter { it.isNotEmpty() }
            .map { if (it[0].isDigit()) "s$it" else it }
        return "com.nath.aion.app." + parts.ifEmpty { listOf("site") }.joinToString("_")
    }

    /** True when an app with this package exists but was signed by a different key (an old copy). */
    fun hasConflictingCopy(context: Context, packageName: String): Boolean {
        val info = try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val ours = signingKey().second.encoded
        val signers = info.signingInfo?.apkContentsSigners ?: return true
        return signers.none { it.toByteArray().contentEquals(ours) }
    }

    /** Builds, signs and commits the APK. The result arrives in [WebAppInstallReceiver]. */
    suspend fun install(context: Context, label: String, startUrl: String, iconPng: ByteArray) =
        withContext(Dispatchers.IO) {
            val packageName = packageNameFor(startUrl)
            val workDir = File(context.cacheDir, "webapp").apply { deleteRecursively(); mkdirs() }
            val apk = File(workDir, "site.apk")
            val (key, certificate) = signingKey()
            context.assets.open(TEMPLATE_ASSET).use { template ->
                WebAppApkBuilder.build(
                    template,
                    File(context.applicationInfo.sourceDir),
                    apk,
                    workDir,
                    packageName,
                    label,
                    startUrl,
                    iconPng,
                    key,
                    certificate,
                )
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
                val intent = Intent(context, WebAppInstallReceiver::class.java).putExtra(EXTRA_LABEL, label)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            workDir.deleteRecursively()
        }

    /** One signing key per Aion install, kept in the Android Keystore, so site apps can be updated. */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Aion Web App"))
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4102444800000L))
                    .build(),
            )
            generator.generateKeyPair()
        }
        val entry = store.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to entry.certificate as X509Certificate
    }
}

/** Receives the system installer's result for a site app. */
class WebAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(WebAppInstaller.EXTRA_LABEL) ?: "App"
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "$label installed", Toast.LENGTH_LONG).show()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Could not install $label: $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
