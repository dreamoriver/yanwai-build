package dev.jev.wechatmood.reply

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史纪要的去重、截断与往返。 */
class DigestArchiveTest {
    @Test fun `one time slot has one id no matter how the wording changes`() {
        val base = 1_700_000_000_000L
        val a = requireNotNull(DigestArchive.build("最近 3 小时", base, base, 40, 80, "群里在约周六爬山"))
        val b = requireNotNull(DigestArchive.build("最近 3 小时", base, base, 41, 80, "大家说好周六去爬山，八点集合"))
        assertEquals("同一时段同一天：结论变了也是原地刷新，不是新增", a.id, b.id)
    }

    @Test fun `blank and failure text are dropped`() {
        assertNull(DigestArchive.build("最近 1 小时", 0, 0, 0, 0, "   "))
        assertNull(DigestArchive.build("最近 1 小时", 0, 0, 0, 0, "分析失败：上下文超出"))
    }

    @Test fun `text is capped instead of rejected`() {
        val long = "字".repeat(DigestArchive.MAX_TEXT + 200)
        val digest = requireNotNull(DigestArchive.build("今天", 0, 0, 1, 1, long))
        assertEquals(DigestArchive.MAX_TEXT, digest.text.length)
    }

    @Test fun `encode and decode round trip`() {
        val digest = requireNotNull(DigestArchive.build("最近 12 小时", 10, 20, 30, 60, "说定周日去看房", now = 12345))
        val back = requireNotNull(StoredDigest.decode(digest.encode()))
        assertEquals(digest.id, back.id)
        assertEquals(digest.rangeLabel, back.rangeLabel)
        assertEquals(digest.material, back.material)
        assertEquals(digest.total, back.total)
        assertEquals(digest.text, back.text)
        assertEquals(12345L, back.createdAt)
    }

    @Test fun `broken payload is skipped or repaired instead of failing the list`() {
        assertNull(StoredDigest.decode(JSONObject().put("text", "  ")))
        // 没有 id 的旧数据按内容派生一个，页面照样能显示。
        val repaired = StoredDigest.decode(JSONObject().put("text", "临时约了周五").put("range", "今天").put("to", 7))
        assertTrue(requireNotNull(repaired).id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test fun `same day keeps one entry even if the window end moved`() {
        val base = 1_700_000_000_000L
        val a = requireNotNull(DigestArchive.build("最近 3 小时", base, base + 60_000, 10, 20, "群里在约周六爬山"))
        val b = requireNotNull(DigestArchive.build("最近 3 小时", base, base + 3_600_000, 11, 21, "群里在约周六爬山"))
        assertEquals("自己在群里发了一句，不该让同一条重存", a.id, b.id)
        val nextDay = requireNotNull(DigestArchive.build("最近 3 小时",
            base + 86_400_000, base + 86_400_000, 10, 20, "群里在约周六爬山"))
        assertNotEquals("隔天同一句话是另一次观察", a.id, nextDay.id)
    }

}
