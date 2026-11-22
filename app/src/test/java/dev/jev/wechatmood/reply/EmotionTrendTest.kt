package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 情绪日聚合：会话标识稳定、计数合并、排序。 */
class EmotionTrendTest {
    @Test fun `session id is stable and hides the wxid`() {
        val a = EmotionTrend.sessionOf("wxid_abc123")
        val b = EmotionTrend.sessionOf("wxid_abc123")
        assertEquals(a, b)
        assertTrue(requireNotNull(a).matches(Regex("[0-9a-f]{16}")))
        assertTrue("不能把明文 wxid 留在库里", !requireNotNull(a).contains("wxid"))
        assertNull(EmotionTrend.sessionOf("  "))
    }

    @Test fun `labels are merged and sorted by count`() {
        val rows = listOf(
            EmotionDay("2026-11-20", "平和", 2),
            EmotionDay("2026-11-21", "平和", 3),
            EmotionDay("2026-11-21", "疑问", 1),
        )
        val summary = EmotionTrend.summarize(rows)
        assertEquals("平和", summary.first().label)
        assertEquals(5, summary.first().count)
        assertEquals(1, summary.last().count)
    }

    @Test fun `dominant picks the most frequent label`() {
        val rows = listOf(EmotionDay("2026-11-21", "疑问", 4), EmotionDay("2026-11-21", "平和", 1))
        assertEquals("疑问", EmotionTrend.dominant(rows)?.label)
        assertNull(EmotionTrend.dominant(emptyList()))
    }
}
