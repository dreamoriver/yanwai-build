package dev.jev.wechatmood.reply

import org.json.JSONObject
import java.security.MessageDigest

/**
 * 存下来的一条 AI 转述（「群里/你们在聊什么」的结论）。
 *
 * 只存模型写出来的那一段话，不存聊天素材：素材不落盘，翻历史也看不到原文。
 * 同一个时段重复分析不会多一条：id 由时段与结论派生。
 */
data class StoredDigest(
    val id: String,
    val createdAt: Long,
    val rangeLabel: String,
    val rangeStart: Long,
    val rangeEnd: Long,
    /** 这次转述看过多少条素材、一共多少条消息，用来判断结论的可信范围。 */
    val material: Int,
    val total: Int,
    val text: String,
) {
    fun encode(): JSONObject = JSONObject()
        .put("id", id).put("created_at", createdAt).put("range", rangeLabel)
        .put("from", rangeStart).put("to", rangeEnd)
        .put("material", material).put("total", total).put("text", text)

    companion object {
        internal val ID_PATTERN = Regex("[0-9a-f]{16}")

        /** 解析不出来就返回 null，让这条脏数据自然消失，而不是让整页读失败。 */
        fun decode(json: JSONObject): StoredDigest? {
            val text = json.optString("text").trim()
            if (text.isBlank()) return null
            val label = json.optString("range").trim().take(DigestArchive.MAX_LABEL)
            val from = json.optLong("from").coerceAtLeast(0)
            val to = json.optLong("to").coerceAtLeast(0)
            val id = json.optString("id").trim().takeIf { it.matches(ID_PATTERN) }
                ?: DigestArchive.derivedId(label, to)
            return StoredDigest(id, json.optLong("created_at").coerceAtLeast(0), label, from, to,
                json.optInt("material").coerceAtLeast(0), json.optInt("total").coerceAtLeast(0),
                text.take(DigestArchive.MAX_TEXT))
        }
    }
}

object DigestArchive {
    /** 每个会话最多留这么多条：再多也不会有人翻，白占库。 */
    const val MAX_PER_CONTACT = 200
    const val MAX_TEXT = 1200
    const val MAX_LABEL = 40

    /**
     * 同一时段、同一天只派生一个 id：重复分析是刷新结论，不是新增一次观察。
     *
     * 早先把「结论文本」和「时段末尾那条消息的时间」都当盐，结果措辞一变、或者自己
     * 在群里发一句，就会多出一条几乎一样的纪要。文本不参与 id 之后，结论变了原地更新；
     * 跨天才另算一条。
     */
    fun derivedId(rangeLabel: String, rangeEnd: Long): String {
        val day = if (rangeEnd > 0) FactTimes.dayKey(rangeEnd) else ""
        val source = rangeLabel + "\u0000" + day
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 模型给的结论在这里收口：空白、失败文案不落盘，超长截断而不是整条丢。 */
    fun build(rangeLabel: String, rangeStart: Long, rangeEnd: Long, material: Int, total: Int,
        text: String, now: Long = System.currentTimeMillis()): StoredDigest? {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isBlank() || clean.startsWith("分析失败")) return null
        val label = rangeLabel.trim().take(MAX_LABEL)
        return StoredDigest(derivedId(label, rangeEnd), now, label, rangeStart, rangeEnd,
            material.coerceAtLeast(0), total.coerceAtLeast(0), clean)
    }

    /** 列表上的一行时间：11-22 14:30。 */
    fun stamp(createdAt: Long): String = FactTimes.formatLocal(createdAt, "MM-dd HH:mm")
}
