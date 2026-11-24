package dev.jev.wechatmood.reply

import java.util.Calendar

/** 一条参与聚合的消息，只保留统计需要的字段。 */
data class StatRecord(val time: Long, val send: Int, val type: Int, val content: String, val speaker: String = "") {
    /**
     * 统一成毫秒再用：来源既有毫秒也有秒级时间戳，
     * 直接拿秒当毫秒会把活跃时段算到 1970 年（和 GroupTranscript 同一套阈值判断）。
     */
    val timeMillis: Long get() = if (time > 1_000_000_000_000L) time else time * 1000
}

/** 单个成员的发言概况。 */
data class MemberStat(
    val name: String,
    val count: Int,
    val media: Int,
    val voice: Int,
    val firstAt: Long,
    val lastAt: Long,
    val hours: List<Int>,
)

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
    val earliest: Long = 0,
    val latest: Long = 0,
    val members: List<MemberStat> = emptyList(),
    /** 消息类型 → 条数，用来在界面上说清「文字 / 图片 / 表情」各多少。 */
    val types: Map<Int, Int> = emptyMap(),
) {
    val perCapita: Int get() = if (senders <= 0) 0 else total / senders

    /** 消息构成的人话汇总：文字 420 · 图片 79 · 表情 48；没有的分类不出现。 */
    fun kindSummary(): String {
        val parts = GroupStatsCalculator.KIND_LABELS.mapNotNull { (type, label) ->
            types[type]?.takeIf { it > 0 }?.let { "$label $it" }
        }.toMutableList()
        val known = GroupStatsCalculator.KIND_LABELS.sumOf { types[it.first] ?: 0 }
        val others = types.values.sum() - known
        if (others > 0) parts += "其它 $others"
        return parts.joinToString(" · ")
    }

    fun lines(): List<String> = buildList {
        if (total == 0) {
            add("这段时间没有消息")
            return@buildList
        }
        add("共 $total 条 · $senders 人参与 · 人均 $perCapita 条")
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

/** 一个统计时间窗：[start] 含、[end] 不含，单位毫秒。 */
data class StatsWindow(val start: Long, val end: Long)

object GroupStatsCalculator {
    const val SAMPLE_LIMIT = 5000
    private val LINK = Regex("https?://", RegexOption.IGNORE_CASE)

    /** 没有发件人时 [dev.jev.wechatmood.hook.MessageMetadata.speaker] 用的占位，统计时跳过。 */
    const val UNATTRIBUTED = "对方（群成员未知"

    fun compute(records: List<StatRecord>, sampled: Boolean = false): GroupStats {
        if (records.isEmpty()) return GroupStats(sampled = sampled)
        val members = LinkedHashMap<String, MutableMember>()
        val types = HashMap<Int, Int>()
        val hours = HashMap<Int, Int>()
        var mine = 0
        var media = 0
        var voice = 0
        var links = 0
        var earliest = Long.MAX_VALUE
        var latest = 0L
        val calendar = Calendar.getInstance()
        for (record in records) {
            types[record.type] = (types[record.type] ?: 0) + 1
            if (record.send == 1) mine++
            when (record.type) {
                3, 47 -> media++
                34 -> voice++
            }
            if (record.type == 1 && LINK.containsMatchIn(record.content)) links++
            val stamp = record.timeMillis
            calendar.timeInMillis = stamp
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            hours[hour] = (hours[hour] ?: 0) + 1
            if (stamp > 0) {
                if (stamp < earliest) earliest = stamp
                if (stamp > latest) latest = stamp
            }
            // 没有发送者的消息（群里的表情、图片、视频，微信本地不记发件人）计入总数，
            // 但不算某个人：以前它们被并成一个「对方（群成员未知）」，看着像有人没说名字。
            if (record.send != 1 && record.speaker.startsWith(UNATTRIBUTED)) continue
            // 「我」也进成员表，否则会以为自己的消息没统计。
            val name = if (record.send == 1) "我" else displayName(record)
            val member = members.getOrPut(name) { MutableMember(name) }
            member.count++
            when (record.type) {
                3, 47 -> member.media++
                34 -> member.voice++
            }
            if (stamp > 0) {
                if (member.firstAt == 0L || stamp < member.firstAt) member.firstAt = stamp
                if (stamp > member.lastAt) member.lastAt = stamp
            }
            if (hour !in member.hours) member.hours += hour
        }
        val memberStats = members.values
            .sortedByDescending { it.count }
            .map { MemberStat(it.name, it.count, it.media, it.voice, it.firstAt, it.lastAt, it.hours.take(3).sorted()) }
        return GroupStats(
            total = records.size,
            // 参与人数只算对方：自己的条数单独用 mine 表示，混进来会多算一个人。
            senders = memberStats.count { it.name != "我" },
            mine = mine,
            media = media,
            voice = voice,
            links = links,
            topSenders = memberStats.take(5).map { it.name to it.count },
            activeHours = hours.entries.sortedByDescending { it.value }.take(3).map { it.key }.sorted(),
            types = types,
            sampled = sampled,
            earliest = if (earliest == Long.MAX_VALUE) 0 else earliest,
            latest = latest,
            members = memberStats,
        )
    }

    private class MutableMember(val name: String) {
        var count = 0
        var media = 0
        var voice = 0
        var firstAt = 0L
        var lastAt = 0L
        val hours = mutableListOf<Int>()
    }

    /** 昵称优先取 speaker，其次正文前缀；空白或纯符号统一成「匿名成员」。 */
    private fun displayName(record: StatRecord): String {
        val raw = record.speaker.trim().takeIf { it.isNotEmpty() }
            ?: record.content.substringBefore(":\n", "").takeIf { it.isNotEmpty() }
            ?: return UNKNOWN
        if (raw.none { it.isLetterOrDigit() }) return UNKNOWN
        return raw.take(24)
    }

    const val UNKNOWN = "匿名成员"

    /** 消息类型的中文名与展示顺序，统计和分享图共用。 */
    val KIND_LABELS = listOf(1 to "文字", 3 to "图片", 34 to "语音", 43 to "视频", 47 to "表情")

    /**
     * 占比：保留一位小数，最大余数法保证加总正好 100.0%。
     * 以前按整数百分比，1 条的人永远显示 0%，看着像没统计到。
     */
    fun percents(values: List<Int>): List<Double> {
        val total = values.sum()
        if (total <= 0) return values.map { 0.0 }
        // 单位是 0.1%，最后再除 10，避免浮点误差累积。
        val exact = values.map { it * 1000.0 / total }
        val tenths = exact.map { kotlin.math.floor(it).toInt() }.toMutableList()
        var remaining = 1000 - tenths.sum()
        val order = exact.indices.sortedByDescending { exact[it] - tenths[it] }
        var index = 0
        while (remaining > 0 && order.isNotEmpty()) {
            tenths[order[index % order.size]] += 1
            remaining--
            index++
        }
        return tenths.map { it / 10.0 }
    }

    /**
     * 紧邻的等长窗口：最近 3 小时 → 再往前那 3 小时。
     * 用于「最近 N 小时」几个筛选项；「今天」要用 [previousDayWindow]，
     * 否则比的是昨天下午到午夜，不是「昨天同一时段」。
     * 窗口不合法或短到没有意义（刚过零点、时间戳为 0）时返回 null，界面就不显示对比。
     */
    fun previousWindow(sinceMillis: Long, nowMillis: Long): StatsWindow? {
        if (sinceMillis <= 0 || nowMillis <= sinceMillis) return null
        val span = nowMillis - sinceMillis
        // 不到 1 分钟的窗口，上一周期基本也是空的，比出来只会是「持平」误导人。
        if (span < 60_000L) return null
        return StatsWindow(sinceMillis - span, sinceMillis)
    }

    /**
     * 昨天同一时间窗：今天 00:00～现在 → 昨天 00:00～昨天同一时刻。
     * 「今天」档用它；起点按整天往前挪，不跟当前窗口的长度走。
     */
    fun previousDayWindow(sinceMillis: Long, nowMillis: Long): StatsWindow? {
        if (sinceMillis <= 0 || nowMillis <= sinceMillis) return null
        val span = nowMillis - sinceMillis
        if (span < 60_000L) return null
        val start = sinceMillis - 86_400_000L
        return StatsWindow(start, start + span)
    }

    /**
     * 和上一周期比条数，返回人话结论：「比上一时段多 34%」「比上一时段少 12%」「与上一时段持平」。
     * 上一周期一条都没有时返回 null：没有基数，百分比会炸，界面这一行也不显示。
     */
    fun compare(current: Int, previous: Int): String? {
        if (previous <= 0) return null
        // 按整数百分比四舍五入：差不到 0.5% 就算持平，别冒出「多 0%」这种话。
        val percent = kotlin.math.round((current - previous) * 100.0 / previous).toInt()
        return when {
            percent == 0 -> "与上一时段持平"
            percent > 0 -> "比上一时段多 $percent%"
            else -> "比上一时段少 ${-percent}%"
        }
    }
}
