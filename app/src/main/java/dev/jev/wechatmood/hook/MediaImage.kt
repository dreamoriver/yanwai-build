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
 * 先找内容 ImageView 的 bitmap；微信表情是自定义 View 时，把整块气泡画到 bitmap，
 * 再裁掉左侧头像区域。
 *
 * 头像也是 ImageView，而且经常是气泡里唯一或面积靠前的 ImageView。取图必须先把
 * 头像排除掉——把人像传给第三方模型既没帮助，也是不必要的隐私外泄。
 *
 * 分成两步：`capture` 必须在主线程碰 View，返回的 [MediaImage.Capture] 再交给后台线程
 * `encode`，免得在大图上压 JPEG 卡住聊天界面。
 */
object MediaImage {
    private const val MAX_EDGE = 1024
    private const val QUALITY = 80
    /** 微信头像约 40dp，留一点余量按 56dp 判定。 */
    private const val AVATAR_DP = 56

    /**
     * 主线程只做取图：返回一个还没编码的 [Capture]，编码交给后台线程。
     *
     * 以前这里是 `captureBitmap(view)?.let(::encode)`，缩放、JPEG 压缩和 base64 全在微信主线程跑，
     * 一张大图就能卡住聊天界面几百毫秒。取图又必须在气泡还挂着的时候完成，所以两件事必须拆开。
     */
    fun capture(view: View): Capture? = captureTaken(view)?.let { Capture(it.bitmap, it.owned) }

    /**
     * 取图结果：位图 + 这张图是不是我们自己新建的。
     *
     * `BitmapDrawable.bitmap` 那张是**借的**（微信图片缓存里，我们没所有权，回收会让宿主崩）；
     * 只有 createBitmap / createScaledBitmap 出来的才是**自己的**，用完必须回收。
     */
    private class Taken(val bitmap: Bitmap, val owned: Boolean)

    /** 已经从气泡上取到、还没编码的图片。取图在主线程，[encode] 只能在后台线程调。 */
    class Capture internal constructor(private val bitmap: Bitmap, private val owned: Boolean) {
        /** 缩放 + JPEG + base64，纯计算；失败只记日志返回 null，不把异常带回调用方线程。 */
        fun encode(): String? {
            val data = runCatching {
                // 取图和编码之间隔了一次线程切换，微信的图片缓存可能已经把这张位图回收了。
                if (bitmap.isRecycled) return@runCatching null
                MediaImage.encode(bitmap)
            }
                .onFailure { MoodLog.w("MEDIA_ENCODE_FAILED " + it.javaClass.simpleName) }
                .getOrNull()
            // 编码只读这张图：自己新建的用完就还给系统，借来的那张所有权在微信，绝不能碰。
            if (owned) recycleOwned(bitmap)
            return data
        }
    }

    /** 主线程：从气泡里取出一张干净的 bitmap，取不到返回 null。 */
    private fun captureTaken(view: View): Taken? = runCatching {
        val avatar = findAvatar(view)
        val imageView = findContentImage(view, avatar)
        imageView?.let { toBitmap(it) }
            ?: drawView(view)?.let { drawn -> avatar?.let { cropAfterAvatar(drawn, it, view) } ?: drawn }
    }.onFailure { MoodLog.w("MEDIA_CAPTURE_FAILED ${it.javaClass.simpleName}") }.getOrNull()

    /** 纯计算：缩放 + JPEG 压缩 + base64，可以放在任意后台线程。 */
    fun encode(bitmap: Bitmap): String {
        val scaled = scale(bitmap)
        val out = ByteArrayOutputStream()
        try {
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        } finally {
            // scale 只在超过 MAX_EDGE 时新建一张；编码完那张就没用了，回收掉。
            // 传进来的 bitmap 不归这里管（可能是借来的），不回收。
            if (scaled !== bitmap) recycleOwned(scaled)
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** 内容图：排除头像后按面积取最大的那个。找不到就返回 null，宁可发不出去也不发头像。 */
    private fun findContentImage(view: View, avatar: ImageView?): ImageView? =
        imageViews(view)
            .filter { it !== avatar && it.width > 0 && it.height > 0 }
            .filterNot { looksLikeAvatar(it, view) }
            .maxByOrNull { it.width.toLong() * it.height }

    /** 头像判定：接近方形、尺寸不超过 56dp、并且位于气泡一侧。 */
    private fun findAvatar(view: View): ImageView? {
        val limit = dp(view, AVATAR_DP)
        return imageViews(view)
            .filter { it.width > 0 && it.height > 0 }
            .filter { looksLikeAvatar(it, view) }
            .filter { maxOf(it.width, it.height) <= limit }
            .minByOrNull { it.left }
    }

    private fun looksLikeAvatar(image: ImageView, root: View): Boolean {
        val limit = dp(root, AVATAR_DP)
        if (maxOf(image.width, image.height) > limit) return false
        if (image.width <= 0 || image.height <= 0) return false
        val ratio = image.width.toFloat() / image.height
        if (ratio < 0.75f || ratio > 1.33f) return false
        // 头像不会在气泡正中：靠左或靠右一侧。
        val center = image.left + image.width / 2f
        return center < root.width * 0.25f || center > root.width * 0.75f
    }

    private fun imageViews(view: View): List<ImageView> {
        val found = mutableListOf<ImageView>()
        collect(view, found)
        return found
    }

    private fun collect(view: View, out: MutableList<ImageView>) {
        if (view is ImageView) out += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index), out)
        }
    }

    /** 取内容图：BitmapDrawable 里那张是借的（微信缓存）；其余情况现画一张新的，归自己。 */
    private fun toBitmap(image: ImageView): Taken? {
        val drawable = image.drawable
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap
            // 借的：所有权在微信的图片缓存，绝不能 recycle。
            if (bitmap != null && !bitmap.isRecycled) return Taken(bitmap, owned = false)
        }
        if (drawable == null) return null
        val width = image.width.takeIf { it > 0 } ?: drawable.intrinsicWidth
        val height = image.height.takeIf { it > 0 } ?: drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        // 自己的：这张是 createBitmap 新建的，编码完由 Capture 回收。
        return Taken(bitmap, owned = true)
    }

    /** 表情气泡是自定义 View 时走这里：整块绘制，内容仍然可见。 */
    private fun drawView(view: View): Taken? {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        // 自己的：新建的整块图，裁图后（或被 Capture 编码后）必须回收。
        return Taken(bitmap, owned = true)
    }

    /**
     * 裁掉头像占掉的那一侧，只留气泡内容。坐标按屏幕位置换算，避免父子视图偏移算错。
     *
     * [taken] 一定是 [drawView] 自己画的那张，裁出新位图后原图就没用了，就地回收。
     */
    private fun cropAfterAvatar(taken: Taken, avatar: ImageView, root: View): Taken {
        val bitmap = taken.bitmap
        val cropped = runCatching {
            val rootAt = IntArray(2).also { root.getLocationOnScreen(it) }
            val avatarAt = IntArray(2).also { avatar.getLocationOnScreen(it) }
            val avatarLeft = avatarAt[0] - rootAt[0]
            val avatarRight = avatarLeft + avatar.width
            val gap = dp(root, 4)
            val avatarOnLeft = avatarLeft < root.width / 2
            val start = if (avatarOnLeft) avatarRight + gap else 0
            val end = if (avatarOnLeft) bitmap.width else avatarLeft - gap
            // 裁完只剩一条边或坐标越界，就当没判出头像，整块留着。
            if (start < 0 || end > bitmap.width || end - start < bitmap.width / 4) return@runCatching null
            Bitmap.createBitmap(bitmap, start, 0, end - start, bitmap.height)
        }.getOrNull() ?: return taken
        // 区域正好是整张时 createBitmap 会原样返回同一张，那就别回收了。
        if (cropped === bitmap) return taken
        // 裁出来这张是新的（归自己）；原来整块那张也是自己画的，回收掉省一份 ARGB_8888。
        recycleOwned(bitmap)
        return Taken(cropped, owned = true)
    }

    private fun dp(view: View, value: Int): Int = (view.resources.displayMetrics.density * value).toInt()

    private fun scale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}

/**
 * 只回收**自己新建**的位图。
 *
 * `BitmapDrawable.bitmap` 那种是借微信图片缓存里的，回收会让宿主后面画到它时崩掉；
 * 本文件里只有 createBitmap / createScaledBitmap 出来的图会走到这里。
 */
private fun recycleOwned(bitmap: Bitmap) {
    runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
}
