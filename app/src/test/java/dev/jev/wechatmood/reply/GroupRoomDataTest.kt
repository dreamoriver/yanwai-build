package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRoomDataTest {
    private fun varint(value: Int): ByteArray {
        var rest = value
        val out = ArrayList<Byte>(5)
        while (rest and 0x7F.inv() != 0) {
            out += ((rest and 0x7F) or 0x80).toByte()
            rest = rest ushr 7
        }
        out += rest.toByte()
        return out.toByteArray()
    }

    private fun lengthField(number: Int, payload: ByteArray): ByteArray =
        byteArrayOf(((number shl 3) or 2).toByte()) + varint(payload.size) + payload

    private fun member(id: String, displayName: String, inviter: String = ""): ByteArray {
        var bytes = lengthField(1, id.toByteArray(Charsets.UTF_8))
        bytes += lengthField(2, displayName.toByteArray(Charsets.UTF_8))
        if (inviter.isNotEmpty()) bytes += lengthField(4, inviter.toByteArray(Charsets.UTF_8))
        return bytes
    }

    private fun roomData(vararg members: ByteArray): ByteArray {
        var bytes = ByteArray(0)
        for (item in members) bytes += lengthField(1, item)
        return bytes
    }

    @Test fun `parses group nicknames`() {
        val blob = roomData(
            member("wxid_a", "老王", inviter = "wxid_owner"),
            member("wxid_b", "小李"),
            member("wxid_c", "阿理"),
        )
        assertEquals(mapOf("wxid_a" to "老王", "wxid_b" to "小李", "wxid_c" to "阿理"),
            GroupRoomData.parse(blob))
        assertEquals(listOf("wxid_a", "wxid_b", "wxid_c"), GroupRoomData.memberIds(blob))
    }

    @Test fun `members without a group nickname stay out`() {
        val blob = roomData(member("wxid_a", "老王"), member("wxid_b", ""))
        assertEquals(mapOf("wxid_a" to "老王"), GroupRoomData.parse(blob))
        // 昵称缺了也要能列出人，界面上才有机会补名字。
        assertEquals(listOf("wxid_a", "wxid_b"), GroupRoomData.memberIds(blob))
    }

    @Test fun `unknown fields and other wire types are skipped`() {
        // 外层带一个 varint 字段和一个 4 字节定长字段，成员前面夹一个不认识的字符串字段。
        var blob = byteArrayOf((3 shl 3).toByte(), 0x96.toByte(), 0x01)
        blob += byteArrayOf(((5 shl 3) or 5).toByte(), 1, 2, 3, 4)
        var noisy = lengthField(9, "ignored".toByteArray())
        noisy += member("wxid_a", "老王")
        blob += lengthField(1, noisy)
        assertEquals(mapOf("wxid_a" to "老王"), GroupRoomData.parse(blob))
    }

    @Test fun `broken blobs never throw`() {
        assertEquals(emptyMap<String, String>(), GroupRoomData.parse(null))
        assertEquals(emptyMap<String, String>(), GroupRoomData.parse(ByteArray(0)))
        assertEquals(emptyMap<String, String>(), GroupRoomData.parse(byteArrayOf(0x0A)))
        // 长度写着 100 实际只有几个字节：截断，不越界。
        val truncated = byteArrayOf(0x0A, 0x64, 0x0A, 0x03, 'w'.code.toByte())
        assertTrue(GroupRoomData.parse(truncated).isEmpty())
        // 非长度分隔的裸字节，也不能死循环。
        GroupRoomData.parse(byteArrayOf(0x08, 0x01, 0x10, 0x02))
    }

    @Test fun `display name is cleaned but keeps punctuation`() {
        val blob = roomData(member("wxid_a", "老王 #1"))
        assertEquals(mapOf("wxid_a" to "老王 #1"), GroupRoomData.parse(blob))
    }
}
