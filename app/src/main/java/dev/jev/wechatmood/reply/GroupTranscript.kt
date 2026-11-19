package dev.jev.wechatmood.reply

/**
 * 群聊总结的素材处理：抽样与署名。
 * 都是纯函数，方便单测，也不依赖微信。
 */
object GroupTranscript {
    /** 一次喂给模型的素材上限：够覆盖一整天，又不至于撑爆上下文。 */
    const val SAMPLE_LIMIT = 200
    /** 每段取几条连续消息：单条均匀抽会把对话抽成碎片，看不出谁在接谁。 */
    const val RUN = 4
    /** 单条消息留给模型的字数。 */
    const val MESSAGE_CHARS = 90

    /**
     * 在整段消息里取连续小段，尽量覆盖整个时段。
     * 返回的是升序下标，调用方按原顺序拼文本。
     */
    fun pick(size: Int, limit: Int = SAMPLE_LIMIT): List<Int> {
        if (size <= 0) return emptyList()
        if (limit <= 0 || size <= limit) return (0 until size).toList()
        val windows = (limit / RUN).coerceIn(1, size)
        val run = (limit / windows).coerceAtLeast(1)
        val span = size.toDouble() / windows
        val picked = LinkedHashSet<Int>()
        for (window in 0 until windows) {
            val start = (window * span).toInt().coerceIn(0, size - 1)
            for (offset in 0 until run) {
                val index = start + offset
                if (index < size) picked += index
            }
        }
        return picked.sorted()
    }

    /**
     * 素材里的署名：数据库里查到就用，像 wxid 的不裸奔，返回 null 交给调用方编号。
     */
    fun label(raw: String, resolved: String?): String? {
        val fromDb = resolved?.let { dev.jev.wechatmood.core.TextClean.name(it) }
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("uinull", true) }
        if (fromDb != null) return fromDb
        val clean = dev.jev.wechatmood.core.TextClean.name(raw)
        if (clean.isEmpty() || clean.equals("null", true) || clean.equals("uinull", true)) return null
        if (clean.startsWith("wxid", true)) return null
        if (clean.startsWith("对方（")) return null
        if (clean.matches(Regex("[A-Za-z0-9_-]{14,}"))) return null
        return clean
    }

    /**
     * 把消息拼成「署名: 正文」的行，署名查不到的按出现顺序叫「群友 N」。
     * 同一个 wxid 在一段素材里只会拿到一个名字。
     */
    fun lines(indices: List<Int>, messages: List<ReplyMessage>,
        resolved: Map<String, String>): Pair<List<String>, Map<String, String>> {
        val labels = LinkedHashMap<String, String>()
        val lines = ArrayList<String>(indices.size)
        var unnamed = 0
        for (index in indices) {
            val message = messages.getOrNull(index) ?: continue
            val name = labels.getOrPut(message.speaker) {
                label(message.speaker, resolved[message.speaker]) ?: "群友${++unnamed}"
            }
            val text = message.text.replace("\n", " ").trim().let {
                if (it.length > MESSAGE_CHARS) it.take(MESSAGE_CHARS) + "…" else it
            }
            if (text.isEmpty()) continue
            lines += "$name: $text"
        }
        return lines to labels
    }
}

/** chatroom.memberlist 的解析：群昵称优先，其次群备注 / 昵称。 */
object GroupMemberList {
    private val MEMBER = Regex("<member>(.*?)</member>", RegexOption.DOT_MATCHES_ALL)
    private val FIELDS = listOf("displayName", "remark", "nickname", "nickName", "nick")

    fun parse(xml: String): Map<String, String> {
        if (xml.isBlank() || !xml.contains("<member")) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (match in MEMBER.findAll(xml)) {
            val block = match.groupValues[1]
            val user = field(block, "username") ?: continue
            val name = FIELDS.asSequence().mapNotNull { field(block, it) }.firstOrNull() ?: continue
            out[user] = name
        }
        return out
    }

    /** 取 XML 里一个字段的文本，兼容 CDATA 和常见实体。 */
    fun field(block: String, name: String): String? {
        val pattern = Regex("<" + name + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + name + ">",
            RegexOption.DOT_MATCHES_ALL)
        val raw = pattern.find(block)?.groupValues?.getOrNull(1) ?: return null
        val text = raw.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&")
        val clean = dev.jev.wechatmood.core.TextClean.name(text)
        return clean.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("uinull", true) }
    }
}
