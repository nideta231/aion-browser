/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream

/** Launcher icon styles offered when installing a site as an app. */
object WebAppIcon {
    const val SIZE = 256

    /** Background behind the site icon. */
    enum class Background { NONE, WHITE, BLACK, CUSTOM }

    /**
     * Renders the launcher icon. [removeWhite] clears the white area touching the icon's edges
     * (a flood fill, so white inside the logo is kept).
     */
    fun render(source: Bitmap, removeWhite: Boolean, background: Background, customColor: Int): Bitmap {
        var art = source.copy(Bitmap.Config.ARGB_8888, true)
        if (removeWhite) art = clearEdgeWhite(art)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val color = when (background) {
            Background.NONE -> null
            Background.WHITE -> Color.WHITE
            Background.BLACK -> Color.BLACK
            Background.CUSTOM -> customColor
        }
        val inset = if (color != null) SIZE * 0.16f else SIZE * 0.04f
        if (color != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.drawRoundRect(RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), SIZE * 0.22f, SIZE * 0.22f, paint)
        }
        val box = SIZE - 2 * inset
        val scale = minOf(box / art.width, box / art.height)
        val w = art.width * scale
        val h = art.height * scale
        val dst = RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2)
        canvas.drawBitmap(art, Rect(0, 0, art.width, art.height), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun toPng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun isNearWhite(pixel: Int): Boolean =
        Color.alpha(pixel) > 0 && Color.red(pixel) > 232 && Color.green(pixel) > 232 && Color.blue(pixel) > 232

    private fun clearEdgeWhite(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val queue = IntArray(w * h)
        val seen = BooleanArray(w * h)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!seen[i] && isNearWhite(pixels[i])) {
                seen[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            pixels[i] = Color.TRANSPARENT
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
}
