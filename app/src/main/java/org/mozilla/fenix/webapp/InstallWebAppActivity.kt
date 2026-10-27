/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.Icon
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.icons.IconRequest
import mozilla.components.browser.icons.extension.toIconRequest
import mozilla.components.concept.engine.manifest.WebAppManifest
import org.mozilla.fenix.ext.components

/**
 * "Install as app": turns the current site into a real Android app with its own name, icon and
 * Gecko engine. The user picks the name and how the site's icon is drawn.
 */
class InstallWebAppActivity : AppCompatActivity() {
    private lateinit var nameInput: EditText
    private lateinit var preview: ImageView
    private lateinit var removeWhite: CheckBox
    private lateinit var backgrounds: RadioGroup
    private lateinit var colorInput: EditText
    private lateinit var installButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var siteIcon: Bitmap? = null
    private var manifest: WebAppManifest? = null
    private var startUrl: String = ""

    private val swatches = intArrayOf(
        Color.parseColor("#FF0033"), Color.parseColor("#1E88E5"), Color.parseColor("#43A047"),
        Color.parseColor("#FB8C00"), Color.parseColor("#8E24AA"), Color.parseColor("#212121"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tab = components.core.store.state.selectedTab
        if (tab == null) {
            finish()
            return
        }
        val content = tab.content
        manifest = content.webAppManifest
        startUrl = manifest?.startUrl ?: content.url
        val defaultName = manifest?.shortName ?: manifest?.name ?: content.title.ifBlank { Uri.parse(startUrl).host ?: "App" }
        buildLayout(defaultName)
        loadIcon(tab.content.icon)
    }

    override fun onResume() {
        super.onResume()
        if (::installButton.isInitialized) checkConflict()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildLayout(defaultName: String) {
        title = "Install as app"
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        column.addView(TextView(this).apply {
            text = "Install as app"
            textSize = 22f
        })
        column.addView(TextView(this).apply {
            text = startUrl
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(16))
        })

        val previewFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#808080"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        preview = ImageView(this)
        previewFrame.addView(preview, FrameLayout.LayoutParams(dp(96), dp(96)))
        column.addView(previewFrame, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        column.addView(TextView(this).apply { text = "Name" })
        nameInput = EditText(this).apply {
            setText(defaultName)
            setSingleLine()
        }
        column.addView(nameInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        removeWhite = CheckBox(this).apply {
            text = "Remove white background from the site icon"
            setOnCheckedChangeListener { _, _ -> renderPreview() }
        }
        column.addView(removeWhite)

        column.addView(TextView(this).apply {
            text = "Background behind the icon"
            setPadding(0, dp(12), 0, 0)
        })
        backgrounds = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("None", "White", "Black", "Color").forEachIndexed { index, label ->
            backgrounds.addView(RadioButton(this).apply {
                id = 1000 + index
                text = label
            })
        }
        backgrounds.check(1000)
        backgrounds.setOnCheckedChangeListener { _, _ -> renderPreview() }
        column.addView(backgrounds)

        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        swatches.forEach { color ->
            swatchRow.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                setOnClickListener {
                    colorInput.setText(String.format("#%06X", 0xFFFFFF and color))
                    backgrounds.check(1003)
                    renderPreview()
                }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(8) })
        }
        colorInput = EditText(this).apply {
            hint = "#RRGGBB"
            setSingleLine()
            setText("#FF0033")
            setOnFocusChangeListener { _, focused -> if (!focused) renderPreview() }
        }
        swatchRow.addView(colorInput, LinearLayout.LayoutParams(dp(120), WRAP_CONTENT))
        column.addView(swatchRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        status = TextView(this).apply {
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        column.addView(status)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        column.addView(progress)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Shortcut only"
            setOnClickListener { addShortcut() }
        })
        buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        installButton = Button(this).apply {
            text = "Install"
            setOnClickListener { install() }
        }
        buttons.addView(installButton)
        column.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(column) })
        checkConflict()
    }

    private fun loadIcon(tabIcon: Bitmap?) {
        lifecycleScope.launch {
            val icons = components.core.icons
            val origin = Uri.parse(startUrl).let { "${it.scheme}://${it.host}" }
            val requests = buildList {
                manifest?.let { add(it.toIconRequest().copy(size = IconRequest.Size.LAUNCHER_ADAPTIVE)) }
                add(
                    IconRequest(
                        url = startUrl,
                        size = IconRequest.Size.LAUNCHER,
                        resources = listOf(
                            IconRequest.Resource("$origin/apple-touch-icon.png", IconRequest.Resource.Type.APPLE_TOUCH_ICON),
                        ),
                    ),
                )
            }
            for (request in requests) {
                val icon = runCatching { icons.loadIcon(request).await() }.getOrNull() ?: continue
                if (icon.source != Icon.Source.GENERATOR && !icon.bitmap.isRecycled) {
                    siteIcon = icon.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    break
                }
            }
            if (siteIcon == null && tabIcon != null) siteIcon = tabIcon.copy(Bitmap.Config.ARGB_8888, false)
            if (siteIcon == null) {
                val generated = runCatching { icons.loadIcon(IconRequest(url = startUrl, size = IconRequest.Size.LAUNCHER)).await() }.getOrNull()
                siteIcon = generated?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
            }
            renderPreview()
        }
    }

    private fun selectedBackground() = when (backgrounds.checkedRadioButtonId) {
        1001 -> WebAppIcon.Background.WHITE
        1002 -> WebAppIcon.Background.BLACK
        1003 -> WebAppIcon.Background.CUSTOM
        else -> WebAppIcon.Background.NONE
    }

    private fun customColor(): Int =
        runCatching { Color.parseColor(colorInput.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }
            .getOrDefault(Color.parseColor("#FF0033"))

    private fun renderedIcon(): Bitmap? = siteIcon?.let {
        WebAppIcon.render(it, removeWhite.isChecked, selectedBackground(), customColor())
    }

    private fun renderPreview() {
        renderedIcon()?.let { preview.setImageBitmap(it) }
    }

    private fun checkConflict() {
        val packageName = WebAppInstaller.packageNameFor(startUrl)
        if (WebAppInstaller.hasConflictingCopy(this, packageName)) {
            showStatus("An older copy of this app is installed and must be removed first.")
            installButton.text = "Remove old copy"
            installButton.setOnClickListener {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            }
        } else {
            installButton.text = "Install"
            installButton.setOnClickListener { install() }
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun addShortcut() {
        lifecycleScope.launch {
            components.useCases.webAppUseCases.addToHomescreen(nameInput.text.toString().trim())
            finish()
        }
    }

    private fun install() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            showStatus("Allow Aion to install apps, then come back and tap Install.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val label = nameInput.text.toString().trim().ifEmpty { "App" }
        val icon = renderedIcon()
        installButton.isEnabled = false
        progress.visibility = View.VISIBLE
        showStatus("Building $label (about 200 MB, this takes a little while)...")
        lifecycleScope.launch {
            val result = runCatching {
                // Keep the manifest so Aion's own shortcut path still recognises the site.
                manifest?.let { components.core.webAppManifestStorage.saveManifest(it) }
                WebAppInstaller.install(applicationContext, label, startUrl, icon?.let { WebAppIcon.toPng(it) } ?: ByteArray(0))
            }
            progress.visibility = View.GONE
            result.onSuccess {
                Toast.makeText(applicationContext, "Confirm the install to finish", Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure {
                installButton.isEnabled = true
                showStatus("Install failed: ${it.message}")
            }
        }
    }
}
