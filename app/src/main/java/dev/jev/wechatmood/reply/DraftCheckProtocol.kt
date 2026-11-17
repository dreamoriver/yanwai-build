package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject

/** 一条草稿体检结果：最多两条问题，外加一条保持原意的改写。 */
data class DraftCheck(val issues: List<String>, val rewrite: String?, val parsed: Boolean = true,
    val verdict: String? = null) {
    val isEmpty: Boolean get() = issues.isEmpty() && rewrite.isNullOrBlank()
}

object DraftCheckProtocol {
    private const val MAX_CONTEXT = 8
    private const val MAX_ISSUE = 40
    private const val MAX_REWRITE = 60

    private const val INSTRUCTIONS = """你是聊天回复检查器。draft 是用户已经打好、但还没发出去的一句话。只判断这句话发出去会不会有问题。
检查项只限这些：太急、太生硬、太冷、太敷衍、太书面、有歧义、答非所问、和当前话题无关、节奏不合。
规则：
1. 只报确定的问题，最多两条；说明要具体，指出依据，比如“他刚说周末要加班，这句容易被读成甩脸子”。
2. 不做心理分析，不推测对方的想法、感情或动机。
3. 只要这句话存在被误读的可能，就至少指出一个最可能被误读的点；只有确实完全中性时才返回空数组。
4. rewrite 是能直接替换 draft 发出去的版本，不是对 draft 的评论或回应，也不要加「你可以说」这类前缀；保持原意和用户平时说话的口气，不超过 30 个字；不建议改就不返回。
5. verdict 必须写一句话，说明这句话和当前对话的关系（例如「回应了对方的问题，语气偏冷」或「和刚才的话题无关」），不管有没有问题都要写。
6. 只返回 JSON 对象：{"issues":["..."],"rewrite":"...","verdict":"..."}，不要解释和 Markdown。"""

    fun payload(settings: ReplySettings, context: ReplyContext, draft: String,
        relationship: ReplyRelationship, customRelationship: String = ""): JSONObject {
        val custom = relationship.customValue(customRelationship)
        val evidence = JSONObject()
            .put("relationship", JSONObject().put("id", relationship.id).put("label", relationship.displayLabel(custom)))
            .put("messages", JSONArray(context.messages.takeLast(MAX_CONTEXT).map {
                JSONObject().put("speaker", it.speaker).put("text", it.text.take(120))
            }))
            .put("draft", draft.take(500))
        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 300).put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", INSTRUCTIONS))
                .put(JSONObject().put("role", "user").put("content", evidence.toString())))
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
        val verdict = json.optString("verdict").trim().takeIf { it.isNotBlank() && it != "null" }?.take(80)
        dev.jev.wechatmood.core.MoodLog.i("DRAFT_CHECK ok chars=${content.length} issues=${issues.size} rewrite=${rewrite != null} verdict=${verdict != null}")
        DraftCheck(issues, rewrite, verdict = verdict)
    }.onFailure {
        dev.jev.wechatmood.core.MoodLog.w("DRAFT_CHECK_PARSE_FAILED " + it.javaClass.simpleName)
    }.getOrDefault(DraftCheck(emptyList(), null, parsed = false))
}

/** 脏话这类明显问题不依赖模型判断，本地先兜一层，避免模型放行。 */
object DraftGuard {
    private val HOSTILE = listOf("操你妈", "草泥马", "傻逼", "去死", "贱人", "废物", "神经病", "滚蛋", "他妈的", "你麻痹")

    fun hint(draft: String): String? {
        val hit = HOSTILE.firstOrNull { draft.contains(it) } ?: return null
        return "这句话带攻击性（包含「$hit」），发出去容易把话说死；可以换成说自己感受的说法，比如「我现在挺生气的，晚点再说」"
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
