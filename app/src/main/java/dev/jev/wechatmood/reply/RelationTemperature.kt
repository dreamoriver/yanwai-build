package dev.jev.wechatmood.reply

/** 档案目录里的一行：哈希键、聊天对象、备注名。 */
data class ContactRow(val contactKey: String, val talker: String, val name: String)

/**
 * 关系温度：谁很久没联系、谁那儿还悬着约定、这周要处理几件。
 *
 * 纯聚合，不请求模型；数据来自本机档案目录与聊天库的最后消息时间。
 * 结论只描述「多久没说话、有几件事没落地」，不评价关系好不好。
 */
data class TemperatureContact(
    val key: String,
    val talker: String,
    val name: String,
    /** 最后一条消息的时间；0 表示查不到（库没打开或从没聊过）。 */
    val lastAt: Long,
    /** 还开着的时间型条目。 */
    val openTodos: Int,
    /** 其中落在未来 7 天内的。 */
    val weekTodos: Int,
    val overdue: Int,
)

object RelationTemperature {
    /** 超过这么多天没说话就算「久未联系」。 */
    const val QUIET_DAYS = 14L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun quietDays(lastAt: Long, now: Long): Long {
        if (lastAt <= 0 || now <= lastAt) return 0
        return (now - lastAt) / DAY_MS
    }

    fun isQuiet(lastAt: Long, now: Long): Boolean = quietDays(lastAt, now) >= QUIET_DAYS

    /** 本周窗口：今天零点起 7 天，和待办页的口径一致。 */
    fun weekWindow(now: Long): LongRange {
        val start = FactTimes.startOfDay(now)
        return start until start + 7 * DAY_MS
    }

    /** 一条时间是否落在本周窗口里。 */
    fun inWeek(occurredAt: Long, now: Long): Boolean =
        occurredAt > 0 && occurredAt in weekWindow(now)

    /** 给界面的一句话：「23 天没说话」。 */
    fun quietText(lastAt: Long, now: Long): String {
        if (lastAt <= 0) return "没有聊天记录"
        val days = quietDays(lastAt, now)
        return when {
            days <= 0 -> "今天聊过"
            days == 1L -> "昨天聊过"
            days < QUIET_DAYS -> "${days} 天前聊过"
            else -> "${days} 天没说话了"
        }
    }

    /** 排序：没说话最久的排前面；同样久按待办多的排。 */
    fun sorted(items: List<TemperatureContact>, now: Long): List<TemperatureContact> =
        items.sortedWith(compareByDescending<TemperatureContact> { quietDays(it.lastAt, now) }
            .thenByDescending { it.openTodos }
            .thenBy { it.name })
}
