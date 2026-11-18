package dev.jev.wechatmood.reply

import java.util.Calendar

/** 一条参与聚合的消息，只保留统计需要的字段。 */
data class StatRecord(val time: Long, val send: Int, val type: Int, val content: String)

/** 群聊统计结果，只放聚合数字，不保留任何聊天正文。 */
data class GroupStats(
    val total: Int = 0,
    val senders: Int = 0,
    val mine: Int = 0,
    val media: Int = 0,
    val voice: Int = 0,
    val links: Int = 0,
    val topSenders: List<Pair<String, Int>> = emptyList(),
    val activeHours: List<Int> = emptyList(),
    val sampled: Boolean = false,
) {
    fun lines(): List<String> = buildList {
        if (total == 0) {
            add("这段时间没有消息")
            return@buildList
        }
        add("共 $total 条 · $senders 人发言 · 我发了 $mine 条")
        if (topSenders.isNotEmpty()) {
            add("最活跃：" + topSenders.joinToString("、") { "${it.first} ${it.second}" })
        }
        if (activeHours.isNotEmpty()) {
            add("活跃时段：" + activeHours.joinToString("、") { "${it} 点" })
        }
        val extras = buildList {
            if (media > 0) add("图片/表情 $media")
            if (voice > 0) add("语音 $voice")
            if (links > 0) add("链接 $links")
        }
        if (extras.isNotEmpty()) add(extras.joinToString(" · "))
        if (sampled) add("（消息较多，已按最近 5000 条采样）")
    }
}

object GroupStatsCalculator {
    const val SAMPLE_LIMIT = 5000
    private val LINK = Regex("https?://", RegexOption.IGNORE_CASE)

    fun compute(records: List<StatRecord>, sampled: Boolean = false): GroupStats {
        if (records.isEmpty()) return GroupStats(sampled = sampled)
        val top = HashMap<String, Int>()
        val hours = HashMap<Int, Int>()
        var mine = 0
        var media = 0
        var voice = 0
        var links = 0
        val calendar = Calendar.getInstance()
        for (record in records) {
            if (record.send == 1) mine++
            when (record.type) {
                3, 47 -> media++
                34 -> voice++
            }
            if (record.type == 1 && LINK.containsMatchIn(record.content)) links++
            calendar.timeInMillis = record.time
            hours[calendar.get(Calendar.HOUR_OF_DAY)] = (hours[calendar.get(Calendar.HOUR_OF_DAY)] ?: 0) + 1
            if (record.send == 0) senderOf(record.content)?.let { top[it] = (top[it] ?: 0) + 1 }
        }
        return GroupStats(
            total = records.size,
            senders = top.size,
            mine = mine,
            media = media,
            voice = voice,
            links = links,
            topSenders = top.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            activeHours = hours.entries.sortedByDescending { it.value }.take(3).map { it.key }.sorted(),
            sampled = sampled,
        )
    }

    /** 群聊正文形如「昵称:\n内容」，取冒号前的昵称；超过 140 字的不是昵称。 */
    private fun senderOf(content: String): String? {
        val head = content.substringBefore(":\n", "").takeIf { it.isNotEmpty() } ?: return null
        if (head.length > 140 || head.contains('\n')) return null
        return head.trim().takeIf { it.isNotEmpty() }
    }
}
