package dev.jev.wechatmood.reply

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import dev.jev.wechatmood.core.MoodLog
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 微信视频消息的「看一眼画面」：定位本体、抽关键帧、生成一句描述并缓存。
 *
 * 视频消息（type 43 视频 / 62 小视频）在库里只有 imgPath，封面图与视频本体同名：
 * MicroMsg/<账号 hash>/video/<同名>.mp4，是明文 MP4，不涉及 .dat 解密。
 * 抽帧用系统 MediaMetadataRetriever，不往 APK 里塞 ffmpeg，也不上传整段视频——
 * 发出去的只有几张缩到 640 宽的 JPEG。
 */
object VideoDigest {
    /** 与 ImageDigest 共用同一个 prefs，键前缀分开：图片是 d:，视频是 v:。 */
    private const val PREFS = "yanwai_image_digest"
    private const val KEY_PREFIX = "v:"
    private const val MAX_ENTRIES = 200
    private const val MAX_CHARS = 160
    private const val FRAMES = 4
    private const val FRAMES_LONG = 6
    private const val LONG_SECONDS = 60
    private const val MAX_WIDTH = 640
    private const val QUALITY = 70
    /** 两帧的感知哈希只差这么多位就算同一张画面：关键帧稀疏时会把同一个 I 帧取两次。 */
    private const val SIMILAR_BITS = 3

    /** 模型说「看不清 / 没有视频」时那不是描述，不能进缓存也不能进提示词。 */
    private val REJECT_PREFIX = listOf("抱歉", "我无法", "我不能", "没有收到", "未收到", "没有视频",
        "无法描述", "无法查看", "看不到", "无法识别", "没有画面")

    fun usable(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.length < 2) return false
        return REJECT_PREFIX.none { value.startsWith(it) }
    }

    fun normalize(raw: String?): String? = raw.orEmpty()
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .trim('：', ':', '"', '“', '”', '「', '」', '《', '》', '。', '.', ' ')
        .take(MAX_CHARS)
        .ifBlank { null }

    fun cached(context: Context, talker: String, messageId: Long): String? {
        if (messageId <= 0) return null
        return runCatching {
            val name = key(talker, messageId)
            val raw = prefs(context).getString(name, null) ?: return null
            val text = JSONObject(raw).optString("text")
            if (!usable(text)) { prefs(context).edit().remove(name).apply(); return null }
            text.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun store(context: Context, talker: String, messageId: Long, text: String): String? {
        val clean = normalize(text)?.takeIf(::usable) ?: return null
        if (messageId <= 0) return clean
        runCatching {
            val storage = prefs(context)
            val editor = storage.edit().putString(key(talker, messageId),
                JSONObject().put("text", clean).put("at", System.currentTimeMillis()).toString())
            // 只清自己这一族；图片的 d: 条目归 ImageDigest 管，跨过去会白删别人的缓存。
            val mine = storage.all.filterKeys { it.startsWith(KEY_PREFIX) }
            if (mine.size >= MAX_ENTRIES) {
                mine.entries.mapNotNull { (name, value) ->
                    name to runCatching { JSONObject(value as String).optLong("at") }.getOrDefault(0L)
                }.sortedBy { it.second }.take(mine.size - MAX_ENTRIES + 1).forEach { editor.remove(it.first) }
            }
            editor.apply()
        }
        return clean
    }

    /**
     * 从视频消息的 imgPath 找本体。微信把封面与视频放同一目录、用同一个基名，
     * 所以拿基名去每个账号目录的 video/ 下找 <基名>.mp4 即可，不需要读数据库。
     */
    fun locate(context: Context, imagePath: String): File? {
        val name = baseName(imagePath) ?: run {
            MoodLog.w("VIDEO_LOCATE_SKIP imgPath=" + imagePath.take(40))
            return null
        }
        val root = File(context.applicationInfo.dataDir, "MicroMsg")
        val accounts = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        val found = accounts.asSequence()
            .mapNotNull { account -> File(account, "video/" + name + ".mp4").takeIf { it.isFile && it.length() > 0L } }
            .firstOrNull()
            ?: accounts.asSequence()
                .mapNotNull { account -> File(account, "video/" + name).takeIf { it.isFile && it.length() > 0L } }
                .firstOrNull()
        // 没命中时记下基名与账号数：视频和消息对不上时靠这行定位字段。
        if (found == null) MoodLog.i("VIDEO_LOCATE_MISS base=" + name.take(32) + " accounts=" + accounts.size)
        return found
    }

    /**
     * 一次遍历同时找本体与封面。
     *
     * 对方发来但没点开过的视频只在本地留一张封面 jpg（实测就有这种：只有 2611251401492831.jpg，
     * 没有同名 mp4）。群总结拿不到气泡 View，只能从这个目录找封面兜底，
     * 否则那条视频在总结里永远只是一句「【视频】」。
     */
    fun locateMedia(context: Context, imagePath: String): Pair<File?, File?> {
        val name = baseName(imagePath) ?: run {
            MoodLog.w("VIDEO_LOCATE_SKIP imgPath=" + imagePath.take(40))
            return null to null
        }
        val accounts = File(context.applicationInfo.dataDir, "MicroMsg")
            .listFiles()?.filter { it.isDirectory }.orEmpty()
        var video: File? = null
        var cover: File? = null
        for (account in accounts) {
            if (video == null) {
                File(account, "video/" + name + ".mp4").takeIf { it.isFile && it.length() > 0L }?.let { video = it }
            }
            if (cover == null) {
                File(account, "video/" + name + ".jpg").takeIf { it.isFile && it.length() > 0L }?.let { cover = it }
            }
            if (video != null && cover != null) break
        }
        if (video == null && cover == null) {
            MoodLog.i("VIDEO_LOCATE_MISS base=" + name.take(32) + " accounts=" + accounts.size)
        }
        return video to cover
    }

    /** 只有封面时的一句话描述：封面就是第一帧，能给出场景与主体，只是看不到后续动作。 */
    suspend fun describeCover(context: Context, settings: ReplySettings, talker: String, messageId: Long,
        cover: File, onProgress: (() -> Unit)? = null): String? {
        ImageDigest.cached(context, talker, messageId)?.let { return it }
        onProgress?.invoke()
        val dataUri = runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeFile(cover.absolutePath) ?: return null
            // encode 里会把传进来的位图还回去，这里不需要再回收。
            encode(bitmap, 0)
        }.getOrNull() ?: return null
        val text = try {
            ReplyHttpClient().describeImage(settings, dataUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MoodLog.w("VIDEO_COVER_FAILED " + e.javaClass.simpleName + ": " + e.message.orEmpty().take(80))
            return null
        }
        // 存进图片描述那一族（d:）：它就是一张图，和聊天里长按封面拿到的是同一条缓存。
        val saved = ImageDigest.store(context, talker, messageId, text)
        MoodLog.i("VIDEO_COVER_OK len=" + text.length + " saved=" + (saved != null))
        return saved
    }

    /** 基名必须像微信生成的媒体名：纯字母数字，带扩展名就去掉。 */
    private fun baseName(raw: String): String? {
        if (raw.isBlank()) return null
        val last = raw.substringBefore('?').substringAfterLast('/')
        val name = if (last.contains('.')) last.substringBeforeLast('.') else last
        val clean = name.trim()
        if (clean.length < 6 || clean.length > 64) return null
        if (!clean.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return clean
    }

    /** 抽帧：均匀取每段时间的中点，避开首尾黑帧；返回 data URI 与时长秒数。 */
    fun frames(file: File): Pair<List<String>, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            // 竖屏视频的朝向记在元数据里，getFrameAtTime 不会自己转；不转就把躺倒的画面喂给模型。
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val seconds = (durationMs / 1000L).toInt().coerceAtLeast(1)
            val count = if (seconds >= LONG_SECONDS) FRAMES_LONG else FRAMES
            // 关键帧稀疏时（微信小视频常见只有一两个 I 帧）几个时间点会落在同一张画面上：
            // 按感知哈希去重，重复的丢掉，别拿四份 token 换四张一样的图。
            val seen = ArrayList<Long>(count)
            val uris = (0 until count).mapNotNull { index ->
                val atUs = durationMs * 1000L * (2L * index + 1L) / (2L * count)
                val frame = runCatching {
                    retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: return@mapNotNull null
                val hash = fingerprint(frame)
                if (seen.any { java.lang.Long.bitCount(it xor hash) <= SIMILAR_BITS }) {
                    // 这张是重复帧，encode 不会被调用，得在这里自己还回去。
                    runCatching { frame.recycle() }
                    return@mapNotNull null
                }
                seen += hash
                encode(frame, rotation)
            }
            if (uris.size < count) {
                MoodLog.i("VIDEO_FRAMES_PARTIAL asked=" + count + " got=" + uris.size +
                    " seconds=" + seconds + " rotate=" + rotation)
            }
            if (uris.isEmpty()) null else uris to seconds
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MoodLog.w("VIDEO_FRAMES_FAILED " + e.javaClass.simpleName)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 8x8 灰度均值哈希：只用来判断两帧是不是同一张画面，不参与描述。 */
    private fun fingerprint(frame: Bitmap): Long {
        val small = runCatching { Bitmap.createScaledBitmap(frame, 8, 8, true) }.getOrNull() ?: return 0L
        val values = IntArray(64)
        for (y in 0 until 8) for (x in 0 until 8) {
            val color = small.getPixel(x, y)
            values[y * 8 + x] = ((color shr 16 and 0xFF) * 299 + (color shr 8 and 0xFF) * 587 +
                (color and 0xFF) * 114) / 1000
        }
        if (small !== frame) runCatching { small.recycle() }
        val average = values.sum() / values.size
        var bits = 0L
        values.forEachIndexed { index, value -> if (value >= average) bits = bits or (1L shl index) }
        return bits
    }

    /**
     * 旋转 + 缩放 + JPEG + base64。
     *
     * 传进来的位图一律由这里负责回收：抽帧时它来自 getFrameAtTime（归调用方，retriever 不会回收），
     * 封面兜底时它来自 BitmapFactory。一张 1080p 帧是 8MB 级 ARGB_8888，
     * 所以无论成败都要还回去：旋转、缩放各建一张也各还一张。
     */
    private fun encode(frame: Bitmap, rotation: Int): String? {
        var work: Bitmap = frame
        var scaled: Bitmap = frame
        return try {
            if (rotation % 360 != 0) {
                work = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height,
                    android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
            }
            scaled = if (work.width > MAX_WIDTH)
                Bitmap.createScaledBitmap(work, MAX_WIDTH, work.height * MAX_WIDTH / work.width, true)
            else work
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                .toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            if (scaled !== work) runCatching { scaled.recycle() }
            if (work !== frame) runCatching { work.recycle() }
            runCatching { frame.recycle() }
        }
    }

    /** 缓存命中直接用；否则抽帧请求一次视觉模型。失败返回 null，不影响这次回复。 */
    suspend fun describe(context: Context, settings: ReplySettings, talker: String, messageId: Long,
        file: File, onProgress: (() -> Unit)? = null): String? {
        cached(context, talker, messageId)?.let { return it }
        onProgress?.invoke()
        val shot = frames(file) ?: run {
            MoodLog.w("VIDEO_FRAMES_EMPTY name=" + file.name.take(32) + " size=" + file.length())
            return null
        }
        val text = try {
            ReplyHttpClient().describeVideo(settings, shot.first, shot.second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MoodLog.w("VIDEO_DIGEST_FAILED " + e.javaClass.simpleName + ": " + e.message.orEmpty().take(80))
            return null
        }
        val saved = store(context, talker, messageId, text)
        MoodLog.i("VIDEO_DIGEST_OK frames=" + shot.first.size + " seconds=" + shot.second +
            " len=" + text.length + " saved=" + (saved != null))
        return saved
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 键用 talker 的 SHA-256 前 8 字节，和图片同理：32 位哈希撞车会把 A 的视频说给 B 听。 */
    private fun key(talker: String, messageId: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(talker.toByteArray(Charsets.UTF_8)).take(8)
            .joinToString("") { "%02x".format(it) }
        return KEY_PREFIX + digest + ":" + messageId
    }
}
