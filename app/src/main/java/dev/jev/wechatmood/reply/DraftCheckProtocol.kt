package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject

/** 一条草稿体检结果：最多两条问题，外加一条保持原意的改写。 */
data class DraftCheck(val issues: List<String>, val rewrite: String?, val parsed: Boolean = true,
    val verdict: String? = null, val relates: Boolean? = null, val referenceCount: Int = 0) {
    val isEmpty: Boolean get() = issues.isEmpty() && rewrite.isNullOrBlank()
}

object DraftCheckProtocol {
    private const val MAX_CONTEXT = 20
    private const val MAX_ISSUE = 40
    private const val MAX_REWRITE = 60

    private const val INSTRUCTIONS = """你是聊天回复检查器。draft 是用户已经打好、但还没发出去的一句话。只判断这句话发出去会不会有问题。
第一步必须先回答 relates_to_topic：这句话有没有接住对方最近说的内容。
先看 focus：它是用户长按的那一条消息（可能是图片/表情/引用），draft 应该是在回应它。
如果 focus 是图片或表情，图片内容会一并附上；先看这张图在表达什么，再判断 draft 有没有接住它，
不要只说「那张图片」，也不要猜图里没有的东西。
没有 focus 时再看 last_incoming（对方最后说的那句）；两者都没有语义关联就判 false，
例如对方问「周末有空吗」而你准备回「今天天气不错」。
- 对方提问、请求、说明一件事，而 draft 完全没有回应这件事 → false
- 主动换话题、开玩笑岔开、只是打招呼 → 算 true，不算问题
relates_to_topic 为 false 时，issues 至少要写一条，说明哪里没接上。
不要因为「这句话本身没毛病」就返回空数组：只要它没接住 last_incoming，就必须算问题。
规则：
1. 只报确定的问题，最多两条；说明要具体，指出依据，比如“他刚说周末要加班，这句容易被读成甩脸子”。
2. 不做心理分析，不推测对方的想法、感情或动机。
3. 只要这句话存在被误读的可能，就至少指出一个最可能被误读的点；只有确实完全中性时才返回空数组。
4. rewrite 要沿用 my_style 里的说话习惯，包括句长和标点：我平时不加句号就别加，不写书面连接词；rewrite 是能直接替换 draft 发出去的版本，不是对 draft 的评论或回应，也不要加「你可以说」这类前缀；保持原意和用户平时说话的口气，不超过 30 个字；不建议改就不返回。
5. 无意义内容、乱码、和当前话题无关的话，必须写进 issues，不能只写在 verdict 里。
6. verdict 必须写一句话，说明这句话和当前对话的关系（例如「回应了对方的问题，语气偏冷」或「和刚才的话题无关」），不管有没有问题都要写。
7. 只返回 JSON 对象：{"relates_to_topic":true,"issues":["..."],"rewrite":"...","verdict":"..."}，不要解释和 Markdown。"""

    fun payload(settings: ReplySettings, context: ReplyContext, draft: String,
        relationship: ReplyRelationship, customRelationship: String = "", image: String? = null): JSONObject {
        val custom = relationship.customValue(customRelationship)
        val style = StyleProfileCache.of(context.talker, context.messages)
        val evidence = JSONObject()
            .put("relationship", JSONObject().put("id", relationship.id).put("label", relationship.displayLabel(custom)))
            .put("my_style", style?.summary() ?: JSONObject.NULL)
            // 单独把对方最后一句拎出来，模型更容易对比 draft 有没有接住。
            .put("last_incoming", context.messages.lastOrNull { it.speaker != "我" }?.text?.take(120) ?: JSONObject.NULL)
            // 长按的那条才是这一轮要回应的对象，图片/表情只有类型没有文字。
            .put("focus", context.targetMedia?.let {
                JSONObject().put("id", context.focusMessageId).put("type", "media").put("media", it)
                    .put("note", "对方发来一张$it")
            } ?: context.messages.firstOrNull { it.id == context.focusMessageId }?.let {
                JSONObject().put("id", it.id).put("speaker", it.speaker).put("text", it.text.take(120))
            } ?: JSONObject.NULL)
            .put("messages", JSONArray(context.messages.takeLast(MAX_CONTEXT).map {
                JSONObject().put("speaker", it.speaker).put("text", it.text.take(120))
            }))
            .put("draft", draft.take(500))
        val userMessage = if (image != null) JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", evidence.toString()))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image))))
        else JSONObject().put("role", "user").put("content", evidence.toString())
        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 500).put("temperature", 0.1)
            // 强制 JSON 输出，减少模型只回一段话导致解析为空。
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", INSTRUCTIONS))
                .put(userMessage))
    }

    internal fun parse(body: String): DraftCheck = runCatching {
        val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return@runCatching DraftCheck(emptyList(), null)
        val json = JSONObject(content.substring(start, end + 1))
        val issues = json.optJSONArray("issues")?.let { array ->
            (0 until minOf(array.length(), 2)).mapNotNull { index ->
                (array.opt(index) as? String)?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_ISSUE)
            }
        }.orEmpty()
        val rewrite = json.optString("rewrite").trim()
            .takeIf { it.isNotBlank() && it != "null" }?.take(MAX_REWRITE)
        val verdict = json.optString("verdict").trim().takeIf { it.isNotBlank() && it != "null" }?.take(120)
        // 模型有时把「无关」只写在 verdict 里；这种情况按问题处理，避免显示成没问题。
        val relates = if (json.has("relates_to_topic")) json.optBoolean("relates_to_topic", true) else null
        val unrelated = relates == false || (verdict != null && listOf("无关", "不相关", "无意义", "乱码", "看不懂",
            "没有关系", "不知所云", "字母", "拼音", "测试输入", "无法理解", "不构成").any { verdict.contains(it) })
        val finalIssues = if (issues.isEmpty() && unrelated)
            listOf("没有接住对方刚才说的内容，容易被当成没在看消息") else issues
        dev.jev.wechatmood.core.MoodLog.i(
            "DRAFT_CHECK ok chars=${content.length} relates=${relates} issues=${finalIssues.size} rewrite=${rewrite != null} verdict=${verdict != null}")
        DraftCheck(finalIssues, rewrite, verdict = verdict, relates = relates)
    }.onFailure {
        dev.jev.wechatmood.core.MoodLog.w("DRAFT_CHECK_PARSE_FAILED " + it.javaClass.simpleName)
    }.getOrDefault(DraftCheck(emptyList(), null, parsed = false))
}

/** 脏话这类明显问题不依赖模型判断，本地先兜一层，避免模型放行。 */
object DraftGuard {
    private val HOSTILE = listOf("操你妈", "草泥马", "傻逼", "去死", "贱人", "废物", "神经病", "滚蛋", "他妈的", "你麻痹")

    fun hint(draft: String): String? {
        HOSTILE.firstOrNull { draft.contains(it) }?.let { hit ->
            return "这句话带攻击性（包含「$hit」），发出去容易把话说死；可以换成说自己感受的说法，比如「我现在挺生气的，晚点再说」"
        }
        if (looksLikeGibberish(draft)) {
            return "这看起来是一串字母或测试输入，和当前聊天没有关系；确认要发的话，先换成一句正常的话"
        }
        return null
    }

    /**
     * 中文用户随手敲的字母串（如 asdfgh）元音比例很低，直接本地判掉，
     * 不依赖模型，避免「没问题」这种明显不对的结论。
     */
    private fun looksLikeGibberish(draft: String): Boolean {
        if (draft.any { it in '\u4e00'..'\u9fff' }) return false
        val letters = draft.filter { it.isLetter() }
        if (letters.length >= 4) {
            val vowels = letters.count { it.lowercaseChar() in "aeiou" }
            return vowels.toDouble() / letters.length < 0.2
        }
        val digits = draft.filter { it.isDigit() }
        return digits.length >= 4 && digits.length == draft.count { it.isLetterOrDigit() }
    }
}

/** 同一份草稿在短时间内重复点只算一次，避免反复花钱。 */
class DraftCheckCache(private val windowMs: Long = 30L * 60 * 1000) {
    private data class Entry(val at: Long, val draft: String, val check: DraftCheck)
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized fun get(talker: String, draft: String, now: Long = System.currentTimeMillis()): DraftCheck? {
        val entry = entries[talker] ?: return null
        if (entry.draft != draft || now - entry.at > windowMs) return null
        return entry.check
    }

    @Synchronized fun put(talker: String, draft: String, check: DraftCheck, now: Long = System.currentTimeMillis()) {
        if (entries.size > 16) entries.clear()
        entries[talker] = Entry(now, draft, check)
    }

    @Synchronized fun clear() = entries.clear()
}
