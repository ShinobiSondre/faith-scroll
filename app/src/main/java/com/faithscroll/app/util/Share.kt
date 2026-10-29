package com.faithscroll.app.util

import com.faithscroll.app.util.tr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.faithscroll.app.data.Message
import com.faithscroll.app.data.Verse
import com.faithscroll.app.data.Video
import java.io.File

object Share {

    fun verse(ctx: Context, verse: Verse, colors: IntArray) {
        val text = "“${verse.text}”\n— ${verse.ref} (KJV)"
        val uri = runCatching { renderImage(ctx, verse, colors) }.getOrNull()
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (uri != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(intent, tr("Share verse", "Del verset")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun message(ctx: Context, m: Message, image: Bitmap) {
        val uri = runCatching {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "message.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        }.getOrNull()
        val text = m.text + if (m.ref.isNotBlank()) " — ${m.ref}" else ""
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (uri != null) {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(intent, tr("Share", "Del")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun video(ctx: Context, video: Video) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${video.title}\n${video.url}")
        }
        ctx.startActivity(Intent.createChooser(intent, tr("Share story", "Del historien")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) { }
    }

    private fun renderImage(ctx: Context, verse: Verse, colors: IntArray): Uri {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val glow = Paint().apply {
            shader = RadialGradient(w * 0.75f, h * 0.2f, w * 0.7f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
        c.drawColor(0x33000000)

        val margin = 110
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            setShadowLayer(8f, 0f, 3f, 0x66000000)
        }
        val body = "“${verse.text}”"
        var size = 72f
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(body, 0, body.length, tp, w - margin * 2)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.18f)
                .build()
            if (layout.height < h * 0.62f || size <= 34f) break
            size -= 3f
        }
        val top = (h - layout.height) / 2f - 40f
        c.save(); c.translate(margin.toFloat(), top); layout.draw(c); c.restore()

        val refPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF3D27A.toInt()
            textSize = 44f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.06f
        }
        c.drawText("— ${verse.ref}", w / 2f, top + layout.height + 90f, refPaint)

        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            textSize = 30f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.2f
        }
        c.drawText("FAITHSCROLL", w / 2f, h - 70f, foot)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "verse.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    }
}
