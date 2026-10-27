/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import mozilla.components.browser.icons.Icon
import mozilla.components.browser.icons.IconRequest
import mozilla.components.browser.icons.extension.toIconRequest
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.manifest.WebAppManifest
import org.mozilla.fenix.ext.components
import com.google.android.material.R as MaterialR

/**
 * "Install as app": turns the current site into a real Android app with its own name, icon and
 * Gecko engine. The user picks the name and how the site's icon is drawn. The Install button is
 * pinned below the scrolling content so it is visible in any window size.
 */
@Suppress("TooManyFunctions", "LargeClass")
class InstallWebAppActivity : AppCompatActivity() {
    private lateinit var nameInput: EditText
    private lateinit var preview: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var removeWhite: MaterialSwitch
    private lateinit var backgrounds: MaterialButtonToggleGroup
    private lateinit var colorSection: LinearLayout
    private lateinit var colorInput: EditText
    private lateinit var installButton: MaterialButton
    private lateinit var shortcutButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private val swatchViews = mutableListOf<Pair<Int, View>>()
    private val backgroundIds = IntArray(4) { View.generateViewId() }

    private var siteIcon: Bitmap? = null
    private var manifest: WebAppManifest? = null
    private var startUrl: String = ""
    private var busy = false

    private val swatches = intArrayOf(
        Color.parseColor("#FF0033"), Color.parseColor("#FF6D00"), Color.parseColor("#FFC400"),
        Color.parseColor("#00C853"), Color.parseColor("#00B0FF"), Color.parseColor("#2962FF"),
        Color.parseColor("#7C4DFF"), Color.parseColor("#F50057"), Color.parseColor("#263238"),
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
        if (::installButton.isInitialized && !busy) checkConflict()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int, fallback: Int) = MaterialColors.getColor(this, attr, fallback)

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun outlinedField(hintText: String?): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this, null, MaterialR.attr.textInputOutlinedStyle).apply {
            hint = hintText
            val r = dp(12).toFloat()
            setBoxCornerRadii(r, r, r, r)
        }
        val input = TextInputEditText(layout.context).apply { setSingleLine() }
        layout.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return layout to input
    }

    private fun afterChange(block: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = block()
    }

    @Suppress("LongMethod")
    private fun buildLayout(defaultName: String) {
        val surface = themeColor(MaterialR.attr.colorSurface, Color.WHITE)
        val onSurface = themeColor(MaterialR.attr.colorOnSurface, Color.BLACK)
        val onSurfaceVariant = themeColor(MaterialR.attr.colorOnSurfaceVariant, Color.DKGRAY)
        val container = themeColor(MaterialR.attr.colorSurfaceContainerHigh, Color.LTGRAY)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }

        // Scrolling content, capped at 520dp wide and centred so it reads well in wide windows.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }
        val holder = FrameLayout(this)
        holder.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        val scroll = ScrollView(this).apply { addView(holder) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        scroll.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val wanted = if (right - left > dp(560)) dp(520) else MATCH_PARENT
            val lp = column.layoutParams as FrameLayout.LayoutParams
            if (lp.width != wanted) column.post {
                lp.width = wanted
                column.layoutParams = lp
            }
        }

        column.addView(TextView(this).apply {
            text = "Install as app"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
        })
        column.addView(TextView(this).apply {
            text = "Opens in its own window with its own icon, like any other app."
            textSize = 14f
            setTextColor(onSurfaceVariant)
            setPadding(0, dp(4), 0, 0)
        })

        // Live preview card: the icon as the launcher will show it, plus the name.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(28), dp(16), dp(20))
            background = GradientDrawable().apply {
                setColor(container)
                cornerRadius = dp(24).toFloat()
            }
        }
        preview = ImageView(this)
        card.addView(preview, LinearLayout.LayoutParams(dp(104), dp(104)))
        previewLabel = TextView(this).apply {
            text = defaultName
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(previewLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(TextView(this).apply {
            text = Uri.parse(startUrl).host ?: startUrl
            textSize = 12f
            setTextColor(onSurfaceVariant)
            gravity = Gravity.CENTER
            maxLines = 1
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })

        column.addView(sectionTitle("NAME"))
        val (nameLayout, name) = outlinedField(null)
        nameInput = name.apply {
            setText(defaultName)
            addTextChangedListener(afterChange {
                previewLabel.text = text.toString().trim().ifEmpty { "App" }
            })
        }
        column.addView(nameLayout, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(sectionTitle("ICON"))
        val whiteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        whiteRow.addView(TextView(this).apply {
            text = "Remove white background"
            textSize = 16f
            setTextColor(onSurface)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        removeWhite = MaterialSwitch(this).apply { setOnCheckedChangeListener { _, _ -> renderPreview() } }
        whiteRow.addView(removeWhite)
        column.addView(whiteRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(TextView(this).apply {
            text = "Background"
            textSize = 16f
            setTextColor(onSurface)
            setPadding(0, dp(16), 0, dp(8))
        })
        backgrounds = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        listOf("None", "White", "Black", "Color").forEachIndexed { index, label ->
            backgrounds.addView(
                MaterialButton(this, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    id = backgroundIds[index]
                    text = label
                    isAllCaps = false
                    minWidth = 0
                    setPadding(dp(4), paddingTop, dp(4), paddingBottom)
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
        }
        backgrounds.check(backgroundIds[0])
        backgrounds.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                colorSection.visibility =
                    if (selectedBackground() == WebAppIcon.Background.CUSTOM) View.VISIBLE else View.GONE
                renderPreview()
            }
        }
        column.addView(backgrounds, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Custom colour: shown only when "Color" is selected.
        colorSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(16), 0, 0)
        }
        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        swatches.forEachIndexed { index, swatch ->
            val view = View(this).apply {
                setOnClickListener { colorInput.setText(String.format("#%06X", 0xFFFFFF and swatch)) }
            }
            swatchViews.add(swatch to view)
            swatchRow.addView(view, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        colorSection.addView(swatchRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val (colorLayout, colorField) = outlinedField("Hex color, e.g. #FF0033")
        colorInput = colorField.apply {
            setText("#FF0033")
            addTextChangedListener(afterChange { renderPreview() })
        }
        colorSection.addView(colorLayout, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        column.addView(colorSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Pinned footer.
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(12))
            setBackgroundColor(surface)
        }
        footer.addView(View(this).apply { setBackgroundColor(themeColor(MaterialR.attr.colorOutlineVariant, Color.LTGRAY)) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { bottomMargin = dp(8) })
        progress = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        footer.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            visibility = View.GONE
        }
        footer.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        installButton = MaterialButton(this).apply {
            text = "Install app"
            isAllCaps = false
            textSize = 16f
            minHeight = dp(52)
            cornerRadius = dp(26)
            setOnClickListener { install() }
        }
        footer.addView(installButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val secondary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        secondary.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        shortcutButton = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Shortcut instead"
            isAllCaps = false
            setOnClickListener { addShortcut() }
        }
        secondary.addView(shortcutButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        footer.addView(secondary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
        updateSwatches()
        checkConflict()
    }

    private fun updateSwatches() {
        val selected = customColor()
        val ring = themeColor(MaterialR.attr.colorOnSurface, Color.BLACK)
        swatchViews.forEach { (swatch, view) ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(swatch)
                if (swatch == selected) setStroke(dp(3), ring)
            }
        }
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
                val generated = runCatching {
                    icons.loadIcon(IconRequest(url = startUrl, size = IconRequest.Size.LAUNCHER)).await()
                }.getOrNull()
                siteIcon = generated?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
            }
            renderPreview()
        }
    }

    private fun selectedBackground() = when (backgrounds.checkedButtonId) {
        backgroundIds[1] -> WebAppIcon.Background.WHITE
        backgroundIds[2] -> WebAppIcon.Background.BLACK
        backgroundIds[3] -> WebAppIcon.Background.CUSTOM
        else -> WebAppIcon.Background.NONE
    }

    private fun customColor(): Int =
        runCatching { Color.parseColor(colorInput.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }
            .getOrDefault(Color.parseColor("#FF0033"))

    private fun renderedIcon(): Bitmap? = siteIcon?.let {
        WebAppIcon.render(it, removeWhite.isChecked, selectedBackground(), customColor())
    }

    private fun renderPreview() {
        if (!::preview.isInitialized || !::colorInput.isInitialized) return
        updateSwatches()
        renderedIcon()?.let { preview.setImageBitmap(it) }
    }

    private fun checkConflict() {
        val packageName = WebAppInstaller.packageNameFor(startUrl)
        if (WebAppInstaller.hasConflictingCopy(this, packageName)) {
            showStatus("An older copy of this app is installed. Remove it, then install again.")
            installButton.text = "Remove old copy"
            installButton.setOnClickListener {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            }
        } else {
            if (status.text.startsWith("An older copy")) status.visibility = View.GONE
            installButton.text = "Install app"
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
            showStatus("Allow Aion to install apps, then come back and tap Install app.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val label = nameInput.text.toString().trim().ifEmpty { "App" }
        val icon = renderedIcon()
        busy = true
        installButton.isEnabled = false
        shortcutButton.isEnabled = false
        installButton.text = "Building…"
        progress.visibility = View.VISIBLE
        showStatus("Building $label. This takes about half a minute.")
        lifecycleScope.launch {
            val result = runCatching {
                // Keep the manifest so Aion's own shortcut path still recognises the site.
                manifest?.let { components.core.webAppManifestStorage.saveManifest(it) }
                WebAppInstaller.install(applicationContext, label, startUrl, icon?.let { WebAppIcon.toPng(it) } ?: ByteArray(0))
            }
            progress.visibility = View.GONE
            busy = false
            result.onSuccess {
                Toast.makeText(applicationContext, "Confirm the install to finish", Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure {
                installButton.isEnabled = true
                shortcutButton.isEnabled = true
                installButton.text = "Install app"
                showStatus("Install failed: ${it.message}")
            }
        }
    }
}
