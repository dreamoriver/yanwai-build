package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class GroupStatsTest {
    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `empty records produce empty stats`() {
        val stats = GroupStatsCalculator.compute(emptyList())
        assertEquals(0, stats.total)
        assertEquals(listOf("这段时间没有消息"), stats.lines())
    }

    @Test fun `group stats count senders media and hours`() {
        val records = listOf(
            StatRecord(at(10), 0, 1, "老王:\n早"),
            StatRecord(at(10), 0, 1, "老王:\n今天开会吗"),
            StatRecord(at(11), 0, 3, "小林:\n[图片]"),
            StatRecord(at(12), 1, 1, "我发的"),
            StatRecord(at(12), 0, 34, "阿May:\n[语音]"),
            StatRecord(at(21), 0, 1, "老王:\nhttps://example.com/x"),
        )
        val stats = GroupStatsCalculator.compute(records)
        assertEquals(6, stats.total)
        assertEquals(3, stats.senders)
        assertEquals(1, stats.mine)
        assertEquals(1, stats.media)
        assertEquals(1, stats.voice)
        assertEquals(1, stats.links)
        assertEquals("老王", stats.topSenders.first().first)
        assertEquals(3, stats.topSenders.first().second)
        assertTrue(stats.activeHours.contains(10))
    }

    @Test fun `sampled flag is surfaced`() {
        val stats = GroupStatsCalculator.compute(listOf(StatRecord(at(9), 0, 1, "a:\nhi")), sampled = true)
        assertTrue(stats.lines().any { it.contains("采样") })
    }

    @Test fun `private chat counts the other person`() {
        val stats = GroupStatsCalculator.compute(listOf(StatRecord(at(9), 0, 1, "普通消息", "对方")))
        assertEquals(1, stats.total)
        assertEquals(1, stats.senders)
        assertEquals("对方", stats.members.first().name)
    }

    @Test fun `blank or symbol names become anonymous`() {
        val stats = GroupStatsCalculator.compute(listOf(StatRecord(at(9), 0, 1, "@", "")))
        assertEquals(GroupStatsCalculator.UNKNOWN, stats.members.first().name)
    }

    @Test fun `percents keep one decimal and still sum to one hundred`() {
        assertEquals(100.0, GroupStatsCalculator.percents(listOf(1, 1, 1)).sum(), 0.001)
        assertEquals(100.0, GroupStatsCalculator.percents(listOf(3, 3, 3, 3, 3, 3, 3)).sum(), 0.001)
        assertEquals(100.0, GroupStatsCalculator.percents(listOf(7)).sum(), 0.001)
        assertTrue(GroupStatsCalculator.percents(emptyList()).isEmpty())
        // 一条消息占 572 条时不该再显示 0%
        val tiny = GroupStatsCalculator.percents(listOf(570, 1, 1))
        assertEquals(0.2, tiny[1], 0.001)
        assertEquals(100.0, tiny.sum(), 0.001)
    }

    @Test fun `kind summary lists only the types that appear`() {
        val stats = GroupStatsCalculator.compute(listOf(
            StatRecord(1_000, 0, 1, "文字", "甲"),
            StatRecord(2_000, 0, 3, "[媒体]", "甲"),
            StatRecord(3_000, 0, 47, "[媒体]", "乙")))
        assertEquals("文字 1 · 图片 1 · 表情 1", stats.kindSummary())
        assertEquals(3, stats.types.values.sum())
    }
}
