package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关系温度的纯聚合：多久没说话、什么算本周、排序口径。 */
class RelationTemperatureTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    @Test fun `quiet starts at fourteen days`() {
        assertFalse(RelationTemperature.isQuiet(now - 13 * day, now))
        assertTrue(RelationTemperature.isQuiet(now - 14 * day, now))
        assertFalse("查不到最后消息时不算久未联系", RelationTemperature.isQuiet(0, now))
    }

    @Test fun `quiet text reads like a person`() {
        assertEquals("今天聊过", RelationTemperature.quietText(now, now))
        assertEquals("昨天聊过", RelationTemperature.quietText(now - day, now))
        assertEquals("3 天前聊过", RelationTemperature.quietText(now - 3 * day, now))
        assertEquals("20 天没说话了", RelationTemperature.quietText(now - 20 * day, now))
    }

    @Test fun `week window is seven days from today`() {
        val start = FactTimes.startOfDay(now)
        val range = RelationTemperature.weekWindow(now)
        assertEquals(start, range.first)
        assertEquals(start + 7 * day - 1, range.last)
        assertTrue(RelationTemperature.inWeek(start + 1000, now))
        assertFalse("昨天的不算本周", RelationTemperature.inWeek(start - 1000, now))
        assertFalse(RelationTemperature.inWeek(0, now))
    }

    @Test fun `sorted puts the longest silence first`() {
        val items = listOf(
            TemperatureContact("a", "t1", "阿明", now - 2 * day, 0, 0, 0),
            TemperatureContact("b", "t2", "小李", now - 30 * day, 1, 1, 0),
            TemperatureContact("c", "t3", "老王", now - 30 * day, 3, 2, 1),
        )
        val sorted = RelationTemperature.sorted(items, now)
        assertEquals("同样久没说话，待办多的排前面", "老王", sorted.first().name)
        assertEquals("阿明", sorted.last().name)
    }
}
