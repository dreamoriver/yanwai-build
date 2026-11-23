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
        assertTrue("条数不对: ${picked.size}", picked.size in 100..140)
        assertEquals(picked.sorted(), picked)
        assertEquals(picked.size, picked.distinct().size)
        assertTrue("没覆盖开头: ${picked.first()}", picked.first() < 20)
        // 最后一窗锚在最新消息上：以前均匀分布会漏掉最新几十条。
        assertEquals("没覆盖结尾: ${picked.last()}", 999, picked.last())
        // 连续取样：同一小段里下标必须挨着，否则又变成碎片。
        val consecutive = picked.zipWithNext().count { (a, b) -> b - a == 1 }
        assertTrue("连续取样太少: $consecutive", consecutive > picked.size / 2)
    }

    @Test fun `sample stays inside the char budget and keeps small chats whole`() {
        val picked = GroupTranscript.sample(1000)
        assertTrue("条数超上限: ${picked.size}", picked.size <= GroupTranscript.SAMPLE_LIMIT)
        assertEquals("最新一条必须进素材", 999, picked.last())
        val perLine = GroupTranscript.MESSAGE_CHARS + GroupTranscript.LINE_OVERHEAD
        assertTrue("素材超预算: ${picked.size * perLine}",
            picked.size * perLine <= GroupTranscript.BUDGET_CHARS + perLine)
        // 小群不抽样，整段都给。
        assertEquals((0 until 30).toList(), GroupTranscript.sample(30))
    }

    @Test fun `timestamps are prefixed when asked`() {
        val messages = listOf(ReplyMessage(1, "老王", 1_700_000_000_000, "早"))
        val (lines, _) = GroupTranscript.lines(listOf(0), messages, emptyMap(), timestamps = true)
        assertTrue("缺时间戳: ${lines[0]}", lines[0].matches(Regex("\\d{2}:\\d{2} 老王: 早")))
    }

    @Test fun `wxid and placeholders never reach the model`() {
        assertEquals(null, GroupTranscript.label("wxid_abcdefg1234567", null))
        assertEquals(null, GroupTranscript.label("abcdefgh12345678", null))
        assertEquals(null, GroupTranscript.label("对方（群成员未知）", null))
        assertEquals(null, GroupTranscript.label("   ", null))
        assertEquals("老王", GroupTranscript.label("wxid_abcdefg1234567", "老王"))
        assertEquals("uinull", GroupTranscript.label("uinull", null))
        assertEquals("uinull", GroupTranscript.label("wxid_abc", "uinull"))
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
        assertEquals("uinull", parsed["wxid_4"])
    }

    @Test fun `refermsg gives wxid and nickname pair`() {
        val xml = "<msg><appmsg><title>回复</title><refermsg><chatusr>wxid_hscabrw6u39122</chatusr>" +
            "<displayname>阿理</displayname><content>原文</content></refermsg></appmsg></msg>"
        assertEquals("wxid_hscabrw6u39122", GroupMemberList.field(xml, "chatusr"))
        assertEquals("阿理", GroupMemberList.field(xml, "displayname"))
        assertEquals(null, GroupMemberList.field(xml, "nothere"))
    }

    @Test fun `cdata and entities are unwrapped`() {
        assertEquals("老王 & 小李", GroupMemberList.field("<displayname><![CDATA[老王 &amp; 小李]]></displayname>", "displayname"))
        assertEquals("A<B>", GroupMemberList.field("<displayname>A&lt;B&gt;</displayname>", "displayname"))
        assertEquals("null", GroupMemberList.field("<displayname>null</displayname>", "displayname"))
    }

    @Test fun `memberlist parse tolerates junk`() {
        assertEquals(emptyMap<String, String>(), GroupMemberList.parse(""))
        assertEquals(emptyMap<String, String>(), GroupMemberList.parse("not xml"))
    }
}
