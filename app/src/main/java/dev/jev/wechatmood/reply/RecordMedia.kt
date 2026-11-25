package dev.jev.wechatmood.reply

import android.content.Context
import dev.jev.wechatmood.core.MoodLog
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 合并转发「聊天记录」里图片与视频封面的内容描述。
 *
 * 为什么单独一份缓存：记录页里的图不是聊天里的消息，没有自己的 msgId；
 * 唯一稳定的关联是记录页 intent 里的 KEY_CLICK_MSG_ID（＝聊天里那条转发的 msgId），
 * 加上它在记录里的行号。所以键是 r:<talker 摘要>:<msgId>:<行号>。
 *
 * 写入方是 hook/RecordPageMedia（用户打开记录页时抓到位图再描述），
 * 读取方是摘要、帮回上下文、依据弹窗、群总结：把 [图片]/[视频] 占位补成带内容的一句话。
 */
object RecordMedia {
    private const val PREFS = "yanwai_record_media"
    private const val MAX_ENTRIES = 400
    private const val MAX_CHARS = 120
    private const val PREFIX = "r:"

    const val KIND_IMAGE = "image"
    const val KIND_VIDEO = "video"

    /** 只替换这几种媒体占位：语音、聊天记录不在这里补，避免张冠李戴。 */
    private val MEDIA_TOKEN = Regex("\\[(图片|表情|动画表情|视频|小视频)]")

    data class Shot(val index: Int, val kind: String, val text: String)

    /** 进程内持有 applicationContext；由 HookEntry 在初始化时注入。 */
    private var app: Context? = null

    /**
     * 进程内缓存。ReplyContext.collect 与 evidenceBody 会反复问同一批 msgId，
     * 每次去解析几百条 prefs 是浪费；记录页写入时整体失效。
     */
    private val memo = HashMap<String, List<Shot>>()

    fun install(context: Context) {
        app = context.applicationContext
    }

    /** 这条转发在记录页里已经描述过的媒体，按行号升序。 */
    fun read(talker: String, messageId: Long): List<Shot> {
        if (messageId <= 0) return emptyList()
        val name = head(talker, messageId)
        synchronized(memo) { memo[name]?.let { return it } }
        val shots = load(talker, messageId)
        synchronized(memo) { memo[name] = shots }
        return shots
    }

    private fun load(talker: String, messageId: Long): List<Shot> {
        val context = app ?: return emptyList()
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prefix = head(talker, messageId) + ":"
            prefs.all.mapNotNull { (name, raw) ->
                if (!name.startsWith(prefix)) return@mapNotNull null
                val index = name.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
                val value = runCatching { JSONObject(raw as String) }.getOrNull() ?: return@mapNotNull null
                val text = value.optString("text")
                if (!ImageDigest.usable(text)) return@mapNotNull null
                Shot(index, value.optString("kind").ifBlank { KIND_IMAGE }, text)
            }.sortedBy { it.index }
        }.getOrElse { emptyList() }
    }

    /** 缓存命中直接返回；否则请求一次视觉模型。失败返回 null，调用方原样保留占位。 */
    suspend fun describe(context: Context, settings: ReplySettings, talker: String, messageId: Long,
        index: Int, kind: String, dataUri: String): String? {
        if (messageId <= 0) return null
        cached(context, talker, messageId, index)?.let { return it }
        val text = try {
            ReplyHttpClient().describeImage(settings, dataUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MoodLog.w("RECORD_MEDIA_FAILED ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
            return null
        }
        val saved = store(context, talker, messageId, index, kind, text)
        MoodLog.i("RECORD_MEDIA_OK msg=$messageId row=$index kind=$kind len=${text.length} saved=${saved != null}")
        return saved
    }

    fun cached(context: Context, talker: String, messageId: Long, index: Int): String? = runCatching {
        val name = key(talker, messageId, index)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        val text = JSONObject(raw).optString("text")
        text.takeIf { ImageDigest.usable(it) }
    }.getOrNull()

    fun store(context: Context, talker: String, messageId: Long, index: Int, kind: String, text: String): String? {
        val clean = ImageDigest.normalize(text)?.takeIf { ImageDigest.usable(it) } ?: return null
        if (messageId <= 0) return clean
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val value = JSONObject().put("text", clean).put("kind", kind)
                .put("at", System.currentTimeMillis()).toString()
            val editor = prefs.edit().putString(key(talker, messageId, index), value)
            // 超上限时按写入时间丢掉最旧的：记录页会持续产生新条目。
            val all = prefs.all.filterKeys { it.startsWith(PREFIX) }
            if (all.size >= MAX_ENTRIES) {
                all.entries.mapNotNull { (name, raw) ->
                    val at = runCatching { JSONObject(raw as String).optLong("at") }.getOrDefault(0L)
                    name to at
                }.sortedBy { it.second }.take(all.size - MAX_ENTRIES + 1).forEach { editor.remove(it.first) }
            }
            editor.apply()
        }
        // 刚写入的这条必须立刻对摘要可见，整份进程内缓存作废。
        synchronized(memo) { memo.clear() }
        return clean
    }

    /**
     * 纯函数核心：按出现顺序把媒体占位补上描述。
     *
     * [cursor] 跨多次调用共享：一条消息里有多个占位、或者转录是分段拼出来的，
     * 都要继续用下一张图，不能每段都从第一张开始。
     */
    internal fun applyToText(text: String, shots: List<Shot>, cursor: IntArray): String {
        if (text.isEmpty() || shots.isEmpty()) return text
        return MEDIA_TOKEN.replace(text) { match ->
            val shot = shots.getOrNull(cursor[0]++) ?: return@replace match.value
            match.value + "（" + label(shot.kind) + "：" + shot.text.take(MAX_CHARS) + "）"
        }
    }

    /**
     * 转录里还有多少条媒体占位没拿到描述——摘要面板用它提示「点开记录页一次就能补上」。
     * 描述够数时不重复提示；没占位时返回 0。
     */
    fun missing(message: ForwardDigest.Message, talker: String, messageId: Long): Int {
        var total = 0
        for (item in message.items) total += MEDIA_TOKEN.findAll(item.text).count()
        if (total == 0) return 0
        val shots = if (talker.isBlank() || messageId <= 0) emptyList() else read(talker, messageId)
        return (total - shots.size).coerceAtLeast(0)
    }

    /** 摘要面板用：把这条转发里每个媒体占位补成「[图片]（内容：…）」。 */
    fun applyToMessage(message: ForwardDigest.Message, talker: String, messageId: Long): ForwardDigest.Message {
        if (message.items.isEmpty() || talker.isBlank() || messageId <= 0) return message
        val shots = read(talker, messageId)
        if (shots.isEmpty()) return message
        val cursor = intArrayOf(0)
        val items = message.items.map { item ->
            if (!MEDIA_TOKEN.containsMatchIn(item.text)) return@map item
            item.copy(text = applyToText(item.text, shots, cursor))
        }
        return message.copy(items = items)
    }

    /** 帮回上下文、依据弹窗、群总结用：转录已经是纯文本，直接在占位后面补描述。 */
    fun applyToTranscript(talker: String, messageId: Long, transcript: String): String {
        if (transcript.isEmpty() || talker.isBlank() || messageId <= 0) return transcript
        val shots = read(talker, messageId)
        if (shots.isEmpty()) return transcript
        return applyToText(transcript, shots, intArrayOf(0))
    }

    private fun label(kind: String) = if (kind == KIND_VIDEO) "封面" else "内容"

    private fun head(talker: String, messageId: Long) = PREFIX + hash(talker) + ":" + messageId

    private fun key(talker: String, messageId: Long, index: Int) = head(talker, messageId) + ":" + index

    private fun hash(talker: String): String = MessageDigest.getInstance("SHA-256")
        .digest(talker.toByteArray(Charsets.UTF_8)).take(8)
        .joinToString("") { "%02x".format(it) }
}
