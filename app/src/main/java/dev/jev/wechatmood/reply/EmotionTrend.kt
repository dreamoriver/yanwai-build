package dev.jev.wechatmood.reply

import java.security.MessageDigest

/** 某一天、某一方被判定成某个情绪的次数。只存计数，不存原文。 */
data class EmotionDay(val dayKey: String, val label: String, val count: Int, val speaker: String = "")

/**
 * 情绪日聚合。
 *
 * 现有分析缓存按 cache_key 哈希存取、14 天滑动就删，没法按人按天统计，
 * 所以单独记一份：**只存标签和次数，不存任何聊天原文**，留 90 天。
 * 从这一版开始累计，之前的历史补不回来。
 */
object EmotionTrend {
    const val KEEP_DAYS = 90
    /** 会话标识：不存明文 wxid，只存哈希前缀。 */
    fun sessionOf(talker: String?): String? {
        if (talker.isNullOrBlank()) return null
        return MessageDigest.getInstance("SHA-256").digest(talker.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 把次数按标签合并，按次数从多到少。 */
    fun summarize(rows: List<EmotionDay>): List<EmotionDay> {
        val merged = LinkedHashMap<String, Int>()
        rows.forEach { merged[it.label] = (merged[it.label] ?: 0) + it.count }
        return merged.map { (label, count) -> EmotionDay("", label, count) }
            .sortedByDescending { it.count }
    }

    /** 占比最高的那个标签；没有数据返回 null。 */
    fun dominant(rows: List<EmotionDay>): EmotionDay? {
        val total = rows.sumOf { it.count }
        if (total <= 0) return null
        return summarize(rows).firstOrNull()
    }
}
