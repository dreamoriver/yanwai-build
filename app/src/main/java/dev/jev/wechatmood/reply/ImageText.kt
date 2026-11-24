package dev.jev.wechatmood.reply

import android.content.Context
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.UsageKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * 长按图片「提取文字」：把图里的字按原有顺序抄出来（快递单号、地址、菜单价格、截图内容），
 * 供用户复制。
 *
 * 为什么和「图片描述」[ImageDigest] 分成两套，而不是共用一段提示词：
 * - 描述要的是「一句话概括」——提示词明确要求 40 字以内、只写能确定的内容，
 *   模型被要求压缩、挑重点；取字要的是逐行原样，要求不解释、不总结、不补全残缺字。
 * - 两种要求直接冲突。让描述那套去抄单号，模型会把「7731 2345 6789」压成「一张快递单」，
 *   细节全丢；让取字那套去写描述，模型会甩出整屏文字，塞不进回复提示词。
 * - 所以提示词、解析、缓存 key 都分开（key 前缀 "t:" 与描述的 "d:" 区分），
 *   但共用同一个 SharedPreferences 文件，省一个存储位置。
 */
object ImageText {
    /** 与 [ImageDigest] 共用同一个文件，只靠 key 前缀分家。 */
    private const val PREFS = "yanwai_image_digest"
    private const val PREFIX = "t:"
    /** 取字的用途是复制粘贴，超过这个长度在弹层里也没法看，直接截断。 */
    private const val MAX_CHARS = 2000
    /** 本模块自己的缓存条数上限；描述那部分的淘汰由 ImageDigest 管。 */
    private const val MAX_ENTRIES = 200
    /** 模型照提示词回答「图里没字」时的那句话，界面要单独翻成人话。 */
    const val NO_TEXT = "没有文字"

    /**
     * 取字请求体。参数与 [ImageDigest] 的请求保持一致（同一个模型、同一套温度与预算）：
     * 有些服务对特别低温或很小的 max_tokens 会直接拒绝，表现为「模型不接受图片」，
     * 让人误以为它没有视觉能力。max_tokens 留 1500：截断时 ReplyHttpClient 会放宽到
     * 2400 重试一次，中文取字的量基本够用。
     */
    internal fun payload(settings: ReplySettings, imageDataUri: String): JSONObject {
        val instructions = "只把图中的文字按原有顺序抄出来，不要解释、不要总结、不要补全残缺字；" +
            "没有文字就回答「没有文字」。表格/价目按行输出，保留原有的换行与顺序。"
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "请抄出这张图片里的全部文字。"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUri)))
        return JSONObject().put("model", settings.model).put("stream", false).put("max_tokens", 1500)
            .put("temperature", 0.9)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", content)))
    }

    /**
     * 解析取字响应：正文原样保留换行，绝不走 [ImageDigest.normalize]——那个函数把换行压成空格，
     * 表格和一行一个的价目表会被它揉成一坨。
     */
    internal fun parse(body: String): String {
        val root = JSONObject(body)
        check(!root.has("error")) { "取字请求被拒绝" }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: error("模型没有返回内容，请重试")
        val message = choice.optJSONObject("message") ?: JSONObject()
        val finish = choice.optString("finish_reason")
        val picked = clean(message.optString("content")) ?: clean(choice.optString("text"))
        if (picked == null) {
            // 空正文 + length：预算不够，错误里带「截断」让 ReplyHttpClient 放宽 max_tokens 重试一次。
            error(if (finish == "length") "输出被截断，没抄到完整文字，请重试"
            else "没有取到文字（finish=$finish content=${message.optString("content").length}）")
        }
        return picked
    }

    /** 清掉模型自带的代码围栏与行尾空白；空行、缩进和表格行都保留。纯函数，单测可直接用。 */
    internal fun clean(raw: String?): String? {
        var text = raw.orEmpty().replace("\r\n", "\n").replace('\r', '\n')
        if (text.trim().startsWith("```")) {
            // 围栏本身不是图里的文字，里面的内容保留。
            text = text.trim().removePrefix("```").let { it.substringAfter('\n', it) }.removeSuffix("```")
        }
        text = text.lines().joinToString("\n") { it.trimEnd() }.trim()
        text = text.replace(Regex("\n{3,}"), "\n\n")
        return text.take(MAX_CHARS).trimEnd().ifBlank { null }
    }

    /** 模型照提示词回「没有文字」的几种写法，界面上要显示成「这张图里没识别到文字」。 */
    fun isNoText(text: String?): Boolean {
        val value = clean(text)?.replace(Regex("\\s+"), "") ?: return false
        if (value.length > 14) return false
        val plain = value.trim('。', '.', '"', '“', '”', '「', '」', '、')
        return plain == NO_TEXT || plain.contains("没有识别到文字") || plain.contains("图中没有文字") ||
            plain.contains("图片中没有文字") || plain.contains("没有可识别的文字") ||
            plain == "无文字" || plain.lowercase().contains("no text")
    }

    fun cached(context: Context, talker: String, messageId: Long): String? {
        if (messageId <= 0) return null
        return runCatching {
            val raw = prefs(context).getString(key(talker, messageId), null) ?: return@runCatching null
            clean(JSONObject(raw).optString("text"))
        }.getOrNull()
    }

    /**
     * 缓存命中直接用；否则请求一次视觉模型并写回。
     *
     * 失败要能分清两种情况：模型不接受图片（HTTP 400/422 类）和「图里真的没有字」。
     * 前者绝不能显示成「没有文字」——那会让用户以为图是空白的，实际是模型选错了。
     */
    suspend fun extract(context: Context, settings: ReplySettings, talker: String, messageId: Long,
        image: String): Result<String> {
        if (!settings.isConfigured) {
            return Result.failure(IllegalStateException("请先在言外的「回复建议」里配置模型"))
        }
        if (image.isBlank()) {
            return Result.failure(IllegalStateException("没能取到这张图片，请重新长按再试"))
        }
        cached(context, talker, messageId)?.let { return Result.success(it) }
        return try {
            // 与图片描述同一条通道：imageFallback = false，绝不把图去掉再问一次，
            // 否则模型只会答「没有收到图片」，还会被当成取字结果存起来。
            val text = ReplyHttpClient().request(settings, payload(settings, image), { body -> parse(body) },
                imageFallback = false, kind = UsageKind.IMAGE)
            // 只缓存真抄到字的结果：「没有文字」可能是模型偷懒，下次长按应该再问一次。
            val saved = if (isNoText(text)) null else store(context, talker, messageId, text)
            MoodLog.i("IMAGE_TEXT_OK len=${text.length} saved=${saved != null}")
            Result.success(text)
        } catch (e: Exception) {
            // 只记类型与错误文本，不记图中的文字本身。
            MoodLog.w("IMAGE_TEXT_FAILED ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
            Result.failure(IllegalStateException(friendly(e), e))
        }
    }

    /** 把 HTTP 层的措辞翻成用户能直接照做的动作。 */
    private fun friendly(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            // ReplyHttpClient 对 400/422 且带图的请求统一报这句，这里换成明确的下一步。
            message.contains("模型没有接受这次请求") ->
                "当前回复模型不接受图片，请在「回复建议」里换一个支持图片的模型"
            message.contains("API Key") || message.contains("额度") || message.contains("接口地址") ||
                message.contains("重定向") -> message
            message.startsWith("没有取到文字") -> "模型这次没有给出内容，请重试"
            message.isBlank() -> "识别失败，请重试"
            else -> message
        }
    }

    private fun store(context: Context, talker: String, messageId: Long, text: String): String? {
        val value = clean(text) ?: return null
        if (messageId <= 0) return value
        runCatching {
            val prefs = prefs(context)
            val entry = JSONObject().put("text", value).put("at", System.currentTimeMillis()).toString()
            val editor = prefs.edit().putString(key(talker, messageId), entry)
            // 只淘汰自己的 "t:" 条目："d:" 是图片描述缓存，淘汰规则由 ImageDigest 负责。
            val mine = prefs.all.filterKeys { it.startsWith(PREFIX) }
            if (mine.size >= MAX_ENTRIES) {
                mine.entries.mapNotNull { (name, raw) ->
                    runCatching { JSONObject(raw as String).optLong("at") }.getOrNull()?.let { name to it }
                }.sortedBy { it.second }.take(mine.size - MAX_ENTRIES + 1).forEach { editor.remove(it.first) }
            }
            editor.apply()
        }
        return value
    }

    private fun key(talker: String, messageId: Long) = "$PREFIX${talker.hashCode()}:$messageId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
