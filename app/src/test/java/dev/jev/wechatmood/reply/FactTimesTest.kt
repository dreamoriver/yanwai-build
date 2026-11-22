package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时间判定的边界：跨天、只到日、跨年，都是提醒和待办每天都会踩到的情况。 */
class FactTimesTest {
    private fun day(at: Long): Long = FactTimes.startOfDay(at)

    @Test fun `day only values have no fake clock`() {
        val midnight = day(System.currentTimeMillis())
        assertFalse(FactTimes.hasClock(midnight))
        assertFalse(FactTimes.hasClock(0))
        assertTrue(FactTimes.hasClock(midnight + 60_000L))
        assertTrue(FactTimes.hasClock(midnight + 10 * 3_600_000L + 30 * 60_000L))
    }

    @Test fun `day only item is not overdue until the next day starts`() {
        val now = day(System.currentTimeMillis()) + 23 * 3_600_000L
        val today = day(now)
        assertFalse(FactTimes.overdue(today, now))
        assertTrue(FactTimes.overdue(today - 86_400_000L, now))
    }

    @Test fun `timed item is overdue once the clock passes`() {
        val now = day(System.currentTimeMillis()) + 15 * 3_600_000L
        assertTrue(FactTimes.overdue(now - 60_000L, now))
        assertFalse(FactTimes.overdue(now + 60_000L, now))
    }

    @Test fun `label switches at midnight and keeps clock only when real`() {
        val now = day(System.currentTimeMillis()) + 10 * 3_600_000L
        assertTrue(FactTimes.label(now, now).startsWith("今天"))
        assertTrue(FactTimes.label(now + 86_400_000L, now).startsWith("明天"))
        assertTrue(FactTimes.label(now - 86_400_000L, now).startsWith("昨天"))
        // 只到日的条目不该显示 00:00。
        val dayOnly = day(now) + 86_400_000L
        assertEquals("明天", FactTimes.label(dayOnly, now))
    }

    @Test fun `sameDay ignores the clock and separates adjacent days`() {
        val now = day(System.currentTimeMillis()) + 9 * 3_600_000L
        assertTrue(FactTimes.sameDay(now, now + 5 * 3_600_000L))
        assertFalse(FactTimes.sameDay(now, now + 86_400_000L))
        assertTrue(FactTimes.sameDay(0, 0))
        assertFalse(FactTimes.sameDay(0, now))
    }

    @Test fun `day key is stable across a day and changes at midnight`() {
        val now = day(System.currentTimeMillis()) + 12 * 3_600_000L
        assertEquals(FactTimes.dayKey(now), FactTimes.dayKey(now + 3_600_000L))
        assertNotEquals(FactTimes.dayKey(now), FactTimes.dayKey(now + 86_400_000L))
    }
}
