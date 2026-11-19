package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTranscriptTest {
    @Test fun `sample keeps every message when it fits`() {
        assertEquals((0 until 50).toList(), GroupTranscript.pick(50, limit = 200))
        assertEquals(emptyList<Int>(), GroupTranscript.pick(0))
    }

    @Test fun `sample covers the whole span with contiguous runs`() {
        val picked = GroupTranscript.pick(1000)
        assertTrue("取少了: ${picked.size}", picked.size in 150..200)
        assertEquals(picked.sorted(), picked)
        assertEquals(picked.size, picked.distinct().size)
        assertTrue("没覆盖开头: ${picked.first()}", picked.first() < 20)
        assertTrue("没覆盖结尾: ${picked.last()}", picked.last() > 960)
        // 连续取样：同一小段里下标必须挨着，否则又变成碎片。
        val consecutive = picked.zipWithNext().count { (a, b) -> b - a == 1 }
        assertTrue("连续取样太少: $consecutive", consecutive > picked.size / 2)
    }

    @Test fun `wxid and placeholders never reach the model`() {
        assertEquals(null, GroupTranscript.label("wxid_abcdefg1234567", null))
        assertEquals(null, GroupTranscript.label("abcdefgh12345678", null))
        assertEquals(null, GroupTranscript.label("对方（群成员未知）", null))
        assertEquals(null, GroupTranscript.label("   ", null))
        assertEquals("老王", GroupTranscript.label("wxid_abcdefg1234567", "老王"))
        assertEquals(null, GroupTranscript.label("uinull", null))
        assertEquals(null, GroupTranscript.label("wxid_abc", "uinull"))
        assertEquals("老王", GroupTranscript.label("wxid_abc", "老王"))
    }

    @Test fun `names keep hash and underscore`() {
        assertEquals("阿_理#1", GroupTranscript.label("阿_理#1", null))
    }

    @Test fun `unresolved speakers get numbered aliases in order`() {
        val messages = listOf(
            ReplyMessage(1, "wxid_a1234567890123", 0, "先说一句"),
            ReplyMessage(2, "老王", 0, "接一句"),
            ReplyMessage(3, "wxid_b1234567890123", 0, "再说一句"),
        )
        val (lines, labels) = GroupTranscript.lines(listOf(0, 1, 2), messages, mapOf("老王" to "老王"))
        assertEquals(listOf("群友1: 先说一句", "老王: 接一句", "群友2: 再说一句"), lines)
        assertEquals(3, labels.size)
    }

    @Test fun `long messages are cut and media placeholders survive`() {
        val long = "字".repeat(200)
        val messages = listOf(ReplyMessage(1, "老王", 0, long), ReplyMessage(2, "老王", 0, "[图片]"))
        val (lines, _) = GroupTranscript.lines(listOf(0, 1), messages, emptyMap())
        assertEquals(GroupTranscript.MESSAGE_CHARS + 1, lines[0].removePrefix("老王: ").length)
        assertTrue(lines[1].endsWith("[图片]"))
    }

    @Test fun `memberlist xml prefers group display name`() {
        val xml = "<memberlist>" +
            "<member><username>wxid_1</username><nickname>昵称一</nickname><displayName>群名片一</displayName></member>" +
            "<member><username>wxid_2</username><nickname>昵称二</nickname><displayName></displayName></member>" +
            "<member><username>wxid_3</username><remark>备注三</remark></member>" +
            "<member><username>wxid_4</username><nickname>uinull</nickname></member>" +
            "</memberlist>"
        val parsed = GroupMemberList.parse(xml)
        assertEquals("群名片一", parsed["wxid_1"])
        assertEquals("昵称二", parsed["wxid_2"])
        assertEquals("备注三", parsed["wxid_3"])
        assertEquals(null, parsed["wxid_4"])
    }

    @Test fun `memberlist parse tolerates junk`() {
        assertEquals(emptyMap<String, String>(), GroupMemberList.parse(""))
        assertEquals(emptyMap<String, String>(), GroupMemberList.parse("not xml"))
    }
}
