package dev.jev.wechatmood.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.io.ByteArrayOutputStream

/**
 * 从被长按的气泡里取已经渲染好的图片，绕开微信的 .dat 加密文件。
 * 只缩到 1024px 以内的 JPEG，内容只在内存里转成 data URI，不落盘。
 */
object MediaImage {
    private const val MAX_EDGE = 1024
    private const val QUALITY = 80

    fun capture(view: View): String? = runCatching {
        val image = findImageView(view) ?: return null
        val bitmap = toBitmap(image) ?: return null
        val scaled = scale(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun findImageView(view: View): ImageView? {
        if (view is ImageView && view.drawable != null && view.width > 0 && view.height > 0) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findImageView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun toBitmap(image: ImageView): Bitmap? {
        val drawable = image.drawable ?: return null
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap
            if (bitmap != null && !bitmap.isRecycled) return bitmap
        }
        val width = image.width.takeIf { it > 0 } ?: drawable.intrinsicWidth
        val height = image.height.takeIf { it > 0 } ?: drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
