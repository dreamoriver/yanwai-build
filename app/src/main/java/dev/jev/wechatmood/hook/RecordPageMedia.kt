package dev.jev.wechatmood.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.RecordMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 合并转发「聊天记录」详情页：抓每行的图片／视频封面，交给视觉模型写一句话。
 *
 * 实测（一加 15 / 微信 4.x）：记录页是 com.tencent.mm.feature.appmsg.ui.RecordDetailUI，
 * 容器 WxRecyclerView，一行一条记录；onResume 后 600ms 位图已经挂好
 * （显示 301x636 的内容图背后是 244x508 的位图，1.5s、3.5s 三次采样不变），
 * 所以固定延迟 600ms 抓一次，不监听滚动。
 *
 * 关联键来自 intent extras：
 * - KEY_CLICK_MSG_ID：这条转发的本地 msgId（与聊天里那条、与 record2 目录名一致）
 * - KEY_CLICK_MSG_TALKER：所在会话
 *
 * 取图只认「行内已经解好的 BitmapDrawable」。这里刻意不走 MediaImage.capture：
 * 它的兜底路径会把整行画成位图，文本行、占位灰块、表情自绘 View 都会被当成图片
 * 送去模型，结果是对着一片空白或一行字做描述。
 */
internal object RecordPageMedia {
    private const val UI_MARK = "RecordDetailUI"
    private const val KEY_MSG_ID = "KEY_CLICK_MSG_ID"
    private const val KEY_TALKER = "KEY_CLICK_MSG_TALKER"
    /** 一条记录最多描述这么多张：再多就是替用户刷一遍图，token 也不划算。 */
    private const val MAX_SHOTS = 3
    /**
     * 抓图时刻：下一帧先试一次（图已缓存时这一下就有），再补 250ms、700ms。
     * 抓到就停——很多用户点开记录页 100ms 就返回，固定 600ms 会一张都抓不到。
     */
    private val ATTEMPTS = longArrayOf(16L, 250L, 700L, 1500L, 3000L)
    /** 内容图的判定下限（dp）：再小就是头像、状态图标。 */
    private const val CONTENT_MIN_DP = 120
    /** 视频行右侧的时长标签，形如 0:34 / 12:08。 */
    private val DURATION = Regex("^\\d{1,2}:\\d{2}$")
    /** 嵌套记录页没有 msgId：用刚刚打开过的那条外层记录挂上去，窗口取 15 秒。 */
    private const val NESTED_WINDOW_MS = 30_000L
    /** 内层行号从 100 起，避免和外层 0/1/2 撞键。 */
    private const val NESTED_INDEX_BASE = 100

    /** 最近一次带 msgId 的外层记录页。嵌套页靠它认亲。 */
    private var outerId = 0L
    private var outerTalker = ""
    private var outerAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun onResume(activity: Activity) {
        if (!activity.javaClass.name.contains(UI_MARK)) return
        val extras = runCatching { activity.intent?.extras }.getOrNull() ?: return
        val rawId = extras.getLong(KEY_MSG_ID, 0L)
        val rawTalker = extras.getString(KEY_TALKER).orEmpty()
        val now = android.os.SystemClock.elapsedRealtime()
        var messageId = rawId
        var talker = rawTalker
        var base = 0
        if (rawId > 0 && rawTalker.isNotBlank()) {
            outerId = rawId
            outerTalker = rawTalker
            outerAt = now
        } else {
            // 嵌套记录页（从记录里再点进去）不带 KEY_CLICK_MSG_ID。
            // params 里其实有 msgId / msgTalker，能用就用；证明不了是自己人时退回「刚打开的那条外层记录」。
            val (paramId, paramTalker) = idsFromParams(extras)
            val age = now - outerAt
            when {
                paramId > 0 && (outerId <= 0 || paramId == outerId) -> {
                    messageId = paramId
                    talker = paramTalker.ifBlank { outerTalker }
                    base = NESTED_INDEX_BASE
                    MoodLog.i("RECORD_MEDIA_NESTED source=params id=" + paramId + " age=" + age +
                        "ms talkerLen=" + talker.length)
                }
                outerId > 0 && age <= NESTED_WINDOW_MS -> {
                    messageId = outerId
                    talker = outerTalker
                    base = NESTED_INDEX_BASE
                    MoodLog.i("RECORD_MEDIA_NESTED source=outer attach=" + outerId + " paramId=" + paramId +
                        " age=" + age + "ms")
                }
                else -> {
                    MoodLog.w("RECORD_MEDIA_NO_KEY extras=[" + describeExtras(extras) + "] " + describeParams(extras))
                    return
                }
            }
        }
        val root = activity.window?.decorView ?: return
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        for (delay in ATTEMPTS) {
            root.postDelayed({
                if (done.get()) return@postDelayed
                val captured = runCatching { capture(activity, root, talker, messageId, base) }
                    .onFailure { MoodLog.w("RECORD_MEDIA_CAPTURE_FAILED " + it.javaClass.simpleName) }
                    .getOrDefault(0)
                if (captured > 0) done.set(true)
            }, delay)
        }
    }

    /** 主线程：只取位图（借微信缓存里的那张），编码与请求都丢到后台。 */
    /** 抓一次；返回抓到的张数，0 表示这一帧还没就绪（页面也还在）。 */
    private fun capture(activity: Activity, root: View, talker: String, messageId: Long, base: Int): Int {
        if (activity.isFinishing || activity.isDestroyed) return 0
        val list = findList(root) ?: return 0
        val rows = minOf(list.childCount, MAX_SHOTS + 4)
        val shots = mutableListOf<Pair<Int, Pair<String, Shot>>>()
        var miss = 0
        var blank = 0
        for (index in 0 until rows) {
            // 硬上限：行数放宽是为了跳过文本行，实际描述的张数不能跟着放宽。
            if (shots.size >= MAX_SHOTS) break
            val row = list.getChildAt(index) ?: continue
            val shot = contentShot(row)
            if (shot == null) {
                miss++
                if (hasImageWithoutDrawable(row)) blank++
                // 没抓到的行只记结构（文字长度、图片数、最大边长、drawable 类型），不记正文。
                if (miss <= 3) MoodLog.i("RECORD_MEDIA_ROW row=" + index + " " + rowShape(row))
                continue
            }
            val kind = if (hasDuration(row)) RecordMedia.KIND_VIDEO else RecordMedia.KIND_IMAGE
            shots += (base + index) to (kind to shot)
        }
        MoodLog.i("RECORD_MEDIA_SHOT msg=" + messageId + " rows=" + list.childCount +
            " shot=" + shots.size + " miss=" + miss + " blank=" + blank + " talkerLen=" + talker.length)
        if (shots.isEmpty()) return 0
        val app = activity.applicationContext
        scope.launch {
            val settings = runCatching { ModulePrefs.replySettings() }.getOrNull()
            if (settings == null || !settings.isConfigured) {
                MoodLog.i("RECORD_MEDIA_NO_SETTINGS msg=" + messageId)
                return@launch
            }
            var ok = 0
            for (entry in shots) {
                val index = entry.first
                val kind = entry.second.first
                val dataUri = withContext(Dispatchers.Default) { entry.second.second.encode() }
                    ?: continue
                val text = RecordMedia.describe(app, settings, talker, messageId, index, kind, dataUri)
                if (text != null) ok++
            }
            MoodLog.i("RECORD_MEDIA_DONE msg=" + messageId + " shot=" + shots.size + " ok=" + ok)
        }
        return shots.size
    }

    /**
     * 行内最大的一张「已经解好的位图」，没有就返回 null。
     *
     * 排除：头像与状态图标（小于 120dp）、占位（drawable 不是 BitmapDrawable）、
     * 已经回收的位图。文本行本来就没有内容 ImageView，自然走 null 分支。
     */
    private fun contentShot(row: View): Shot? {
        val threshold = (row.resources.displayMetrics.density * CONTENT_MIN_DP).toInt()
        var best: Shot? = null
        var bestArea = 0
        fun walk(view: View) {
            if (view is ImageView) {
                if (view.width > 0 && view.height > 0 && maxOf(view.width, view.height) > threshold) {
                    val shot = shotOf(view)
                    if (shot != null) {
                        val area = shot.bitmap.width * shot.bitmap.height
                        if (area > bestArea) {
                            bestArea = area
                            best = shot
                        }
                    }
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(row)
        return best
    }

    /**
     * 一张图的位图：已经解好的是 BitmapDrawable，直接借。
     * 视频封面、部分占位走微信自己的 Drawable（不是 BitmapDrawable），
     * 这时只把这一个 ImageView 画成位图——**不画整行**，所以不会把发送人、时间和正文带进画面。
     */
    private fun shotOf(image: ImageView): Shot? {
        val drawable = image.drawable ?: return null
        val borrowed = (drawable as? BitmapDrawable)?.bitmap
        if (borrowed != null && !borrowed.isRecycled) return Shot(borrowed, owned = false)
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            Shot(bitmap, owned = true)
        }.getOrNull()
    }

    /** 诊断：只记 key 与类型，不记值。 */
    private fun describeExtras(extras: android.os.Bundle?): String {
        if (extras == null) return "null"
        return extras.keySet().sorted().joinToString(",") { key ->
            key + ":" + when (val value = extras.get(key)) {
                is Long -> "long"
                is Int -> "int"
                is Boolean -> "bool"
                is String -> "str"
                null -> "null"
                else -> value.javaClass.simpleName
            }
        }
    }

    /**
     * 从嵌套页的 params 里取 msgId / msgTalker。
     * params 是 URL/JSON 形式的字符串，这里只取值、不写日志值。
     */
    private fun idsFromParams(extras: android.os.Bundle?): Pair<Long, String> {
        val raw = runCatching { extras?.getString("params") }.getOrNull().orEmpty()
        if (raw.isEmpty()) return 0L to ""
        val id = Regex("""msgId["']?\s*[:=]\s*["']?(\d{5,})""").find(raw)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val talker = Regex("""msgTalker["']?\s*[:=]\s*["']?([A-Za-z0-9_@.]{3,40})""").find(raw)
            ?.groupValues?.get(1).orEmpty()
        return id to talker
    }

    /** 诊断：params 是字符串，只记长度和里面的字段名，不记值。 */
    private fun describeParams(extras: android.os.Bundle?): String {
        val raw = runCatching { extras?.getString("params") }.getOrNull().orEmpty()
        if (raw.isEmpty()) return "params=empty"
        val names = Regex("[A-Za-z_]{3,24}(?=[=:\"])").findAll(raw).map { it.value }.distinct().take(12).toList()
        return "paramsLen=" + raw.length + " fields=[" + names.joinToString(",") + "]"
    }

    /** 记录页的列表：类名带 RecyclerView 且已经挂了行。 */
    private fun findList(view: View): ViewGroup? {
        if (view is ViewGroup) {
            if (view.childCount > 0 && view.javaClass.name.contains("RecyclerView")) return view
            for (index in 0 until view.childCount) {
                findList(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /** 视频行：行内有 0:34 这样的时长标签。 */
    private fun hasDuration(view: View): Boolean {
        if (view is TextView) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (DURATION.matches(text)) return true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasDuration(view.getChildAt(index))) return true
            }
        }
        return false
    }

    /**
     * 只记结构：行内最长文字的长度、ImageView 个数、最大边长（dp）、drawable 类型。
     * 用来判断「这一行到底是文本、还是封面没加载的视频、还是嵌套记录卡片」。
     */
    private fun rowShape(row: View): String {
        var textLen = 0
        var images = 0
        var maxSide = 0
        val kinds = StringBuilder()
        fun walk(view: View) {
            if (view is TextView) textLen = maxOf(textLen, view.text?.length ?: 0)
            if (view is ImageView) {
                images++
                maxSide = maxOf(maxSide, maxOf(view.width, view.height))
                val mark = when {
                    view.drawable == null -> "null"
                    view.drawable is BitmapDrawable -> "bd"
                    else -> view.drawable.javaClass.simpleName.take(12)
                }
                if (kinds.isNotEmpty()) kinds.append(',')
                kinds.append(mark)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(row)
        val density = row.resources.displayMetrics.density
        return "text=" + textLen + " images=" + images +
            " maxSide=" + (maxSide / density).toInt() + "dp drawables=[" + kinds + "]"
    }

    /** 有图片位但没挂 drawable 的行：封面还没下载的视频/图片。 */
    private fun hasImageWithoutDrawable(view: View): Boolean {
        if (view is ImageView) {
            val side = maxOf(view.width, view.height)
            val threshold = (view.resources.displayMetrics.density * CONTENT_MIN_DP).toInt()
            if (side > threshold && view.drawable == null) return true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasImageWithoutDrawable(view.getChildAt(index))) return true
            }
        }
        return false
    }

    /**
     * 借来的位图：所有权在微信的图片缓存，编码只读、绝不回收；
     * 主线程取图到后台编码之间隔了一次线程切换，编码前必须再确认没被回收。
     */
    private class Shot(val bitmap: Bitmap, private val owned: Boolean) {
        fun encode(): String? {
            val data = if (bitmap.isRecycled) null
            else runCatching { MediaImage.encode(bitmap) }.getOrNull()
            // 自己画的那张编完就还；借来的所有权在微信图片缓存，绝不回收。
            if (owned) runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
            return data
        }
    }
}
