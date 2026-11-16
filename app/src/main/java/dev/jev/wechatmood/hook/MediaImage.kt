package dev.jev.wechatmood.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.jev.wechatmood.core.MoodLog
import java.io.ByteArrayOutputStream

/**
 * 从被长按的气泡里取已经渲染好的图片，绕开微信的 .dat 加密文件。
 * 先找 ImageView 的 bitmap；微信表情是自定义 View 时，直接把整个气泡画到 bitmap。
 */
object MediaImage {
    private const val MAX_EDGE = 1024
    private const val QUALITY = 80

    fun capture(view: View): String? = runCatching {
        val imageView = findImageView(view)
        val bitmap = imageView?.let { toBitmap(it) } ?: drawView(view)
        if (bitmap == null) {
            MoodLog.w("MEDIA_CAPTURE none imageView=${imageView != null} size=${view.width}x${view.height}")
            return null
        }
        val scaled = scale(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        val bytes = out.toByteArray()
        MoodLog.w("MEDIA_CAPTURE ok source=${if (imageView != null) "imageview" else "view"} " +
            "${scaled.width}x${scaled.height} bytes=${bytes.size}")
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.onFailure { MoodLog.w("MEDIA_CAPTURE_FAILED ${it.javaClass.simpleName}") }.getOrNull()

    private fun findImageView(view: View): ImageView? {
        if (view is ImageView && view.width > 0 && view.height > 0) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findImageView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun toBitmap(image: ImageView): Bitmap? {
        val drawable = image.drawable
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap
            if (bitmap != null && !bitmap.isRecycled) return bitmap
        }
        if (drawable == null) return null
        val width = image.width.takeIf { it > 0 } ?: drawable.intrinsicWidth
        val height = image.height.takeIf { it > 0 } ?: drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** 表情气泡是自定义 View 时走这里：整块绘制，内容仍然可见。 */
    private fun drawView(view: View): Bitmap? {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
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
