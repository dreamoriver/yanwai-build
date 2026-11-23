package dev.jev.wechatmood.hook

import dev.jev.wechatmood.reply.ReplyContext
import org.junit.Assert.*
import org.junit.Test

class MessageMetadataTest {
    @Test fun `over 1000 characters are skipped rather than truncated`() {
        assertEquals(1000, MessageMetadata(1, 0, "好".repeat(1000), "friend").incomingText()?.length)
        assertNull(MessageMetadata(1, 0, "好".repeat(1001), "friend").incomingText())
        assertNotNull(MessageMetadata(1, 0, "😀".repeat(1000), "friend").incomingText())
        assertNull(MessageMetadata(1, 0, "😀".repeat(1001), "friend").incomingText())
    }
    @Test fun `length limit counts surrounding whitespace before normalization`() {
        assertNull(MessageMetadata(1, 0, " ".repeat(1000) + "好", "friend").incomingText())
        assertEquals("好", MessageMetadata(1, 0, " 好 ", "friend").incomingText())
    }
    open class Fields(
        @JvmField val field_type: Int = 1,
        @JvmField val field_isSend: Int = 0,
        @JvmField val field_content: String? = "你好",
        @JvmField val field_talker: String? = "friend",
    )
    class Inherited : Fields(field_content = "12:30")
    class Timed : Fields() {
        @JvmField val field_createTime = 1700000000000L
        @JvmField val field_msgId = 42L
    }
    @Test fun `reply metadata reads host timestamps and message identity`() {
        val message = MessageMetadata.read(Timed())!!
        assertEquals(1700000000000L, message.createdAt)
        assertEquals(42L, message.messageId)
        assertEquals(0L, MessageMetadata.read(Fields())!!.createdAt)
    }
    @Test fun `long incoming text remains a reply target without enabling Jev analysis`() {
        val message = MessageMetadata(1, 0, "长".repeat(3000), "friend", 42)
        assertNull(message.incomingText())
        assertTrue(message.isReplyTarget())
        assertFalse(message.copy(isSend = 1).isReplyTarget())
        assertFalse(message.copy(type = 3).isReplyTarget())
        assertFalse(message.copy(messageId = 0).isReplyTarget())
    }

    @Test fun `read inherited message fields and preserve time-like text`() {
        assertEquals("12:30", MessageMetadata.read(Inherited())?.incomingText())
    }
    @Test fun `all media and app types are skipped even when they contain readable text`() {
        listOf(3, 34, 43, 62, 47, 49, 1090519089, 822083633, 10000, 0).forEach {
            assertNull("type=$it", MessageMetadata.read(Fields(it, field_content = "今晚见.pdf"))?.incomingText())
        }
    }
    @Test fun `our own messages and unknown direction are skipped`() {
        listOf(1, 2, -1).forEach { assertNull(MessageMetadata.read(Fields(field_isSend = it))?.incomingText()) }
    }
    @Test fun `group sender prefix is excluded`() {
        assertEquals("你好\n明天见", MessageMetadata.read(Fields(field_talker = "group@chatroom",
            field_content = "wxid_sender:\n你好\n明天见"))?.incomingText())
    }
    @Test fun `private chat colon and bracket text are retained`() {
        assertEquals("[好的]", MessageMetadata.read(Fields(field_content = "[好的]"))?.incomingText())
        assertEquals("备注:\n你好", MessageMetadata.read(Fields(field_content = "备注:\n你好"))?.incomingText())
    }
    @Test fun `unrecognized item and absent content fail closed`() {
        assertNull(MessageMetadata.read(Any()))
        assertNull(MessageMetadata.read(null))
        assertNull(MessageMetadata.read(Fields(field_content = null))?.incomingText())
        assertNull(MessageMetadata.read(Fields(field_content = "  "))?.incomingText())
    }
    @Test fun `missing conversation identity fails closed`() {
        assertNull(MessageMetadata.read(Fields(field_talker = null))?.incomingText())
    }
}

/** 位置、名片、通话记录、红包这些消息，素材里应该带上能读到的那一点事实。 */
class MaterialTextTest {
    private fun meta(type: Int, content: String, talker: String = "123@chatroom") =
        MessageMetadata(type = type, isSend = 0, content = content, talker = talker)

    @Test fun `location keeps its poi name`() {
        val m = meta(48, "wxid_a:\n<msg><location x=\"30.2\" y=\"120.1\" scale=\"16\" " +
            "label=\"浙江省杭州市西湖区文三路\" poiname=\"西湖文化广场\" /></msg>")
        assertEquals("西湖文化广场", m.mediaText())
    }

    @Test fun `location falls back to label when poi is blank`() {
        val m = meta(48, "<msg><location poiname=\"\" label=\"文三路口\" /></msg>")
        assertEquals("文三路口", m.mediaText())
    }

    @Test fun `card keeps the nickname`() {
        val m = meta(42, "<msg username=\"wxid_x\" nickname=\"张三\" fullpy=\"zhangsan\" />")
        assertEquals("张三", m.mediaText())
    }

    @Test fun `card without nickname has nothing to show`() {
        assertNull(meta(42, "<msg username=\"wxid_x\" />").mediaText())
    }

    @Test fun `call record strips the group sender prefix`() {
        assertEquals("通话时长 02:31", meta(50, "wxid_a:\n通话时长 02:31").callText())
    }

    @Test fun `call record without line break also strips the prefix`() {
        assertEquals("通话时长 02:31", meta(50, "wxid_a:通话时长 02:31").callText())
    }

    @Test fun `pure xml call record has no text`() {
        assertNull(meta(50, "<voipmsg><duration>30</duration></voipmsg>").callText())
    }

    @Test fun `payment cards only expose the kind`() {
        val red = meta(49, "<msg><appmsg><title>微信红包</title><type>2001</type>" +
            "<wcpayinfo><feedesc>¥100.00</feedesc></wcpayinfo></appmsg></msg>")
        assertEquals("红包", red.evidenceKind())
        assertNull(red.evidenceBody())
        assertEquals("【红包】", red.materialText())

        val transfer = meta(49, "<msg><appmsg><title>转账</title><type>2000</type></appmsg></msg>")
        assertEquals("转账", transfer.evidenceKind())
        assertNull(transfer.evidenceBody())
    }

    @Test fun `payment detected by wcpayinfo alone`() {
        assertEquals("红包", meta(49, "<appmsg><wcpayinfo><feedesc>1</feedesc></wcpayinfo></appmsg>").evidenceKind())
    }

    @Test fun `file cards report as file`() {
        val m = meta(49, "<msg><appmsg><title>预算.xlsx</title><appattach>" +
            "<fileext>xlsx</fileext><totallen>1024</totallen></appattach></appmsg></msg>")
        assertEquals("文件", m.evidenceKind())
        assertEquals("预算.xlsx", m.evidenceBody())
    }

    @Test fun `appattach without totals is not a file`() {
        val m = meta(49, "<msg><appmsg><title>视频号</title><appattach></appattach></appmsg></msg>")
        assertEquals("链接、引用或转发", m.evidenceKind())
    }

    @Test fun `plain url card stays a link`() {
        assertEquals("链接", meta(49, "<msg><appmsg><title>文章</title><url>https://x</url></appmsg></msg>").evidenceKind())
    }

    @Test fun `material text carries type facts`() {
        assertEquals("【位置：西湖文化广场】",
            meta(48, "<msg><location poiname=\"西湖文化广场\" /></msg>").materialText())
        assertEquals("通话时长 02:31", meta(50, "wxid_a:\n通话时长 02:31").materialText())
    }

    @Test fun `material text keeps old behavior for plain text, voice and image`() {
        assertEquals("你好", meta(1, "你好").materialText())
        assertEquals(dev.jev.wechatmood.voice.VoiceText.WAITING,
            meta(34, "wxid_a:").copy(messageId = 7).materialText())
        assertEquals("[媒体]", meta(3, "").materialText())
        assertEquals("【视频】", meta(43, "").materialText())
        assertEquals("【系统消息】", meta(10000, "xxx 撤回了一条消息").materialText())
        assertEquals("【红包·已被领取】", meta(10000, "1领取了你的红包").materialText())
        assertEquals("【转账·已收款】", meta(10000, "来自卡拉很OK的¥0.01 已收款").materialText())
    }

    @Test fun `call and location reach the context`() {
        val context = ReplyContext.collect("123@chatroom", listOf(
            meta(50, "wxid_a:\n通话时长 02:31"),
            meta(48, "wxid_a:\n<msg><location poiname=\"西湖文化广场\" /></msg>"),
        ))
        assertEquals(listOf("通话时长 02:31", "【位置：西湖文化广场】"),
            context.messages.map { it.text })
    }

    @Test fun `transfer keeps amount and memo, red packet keeps the wish`() {
        val transfer = meta(49, "<msg><appmsg><title>转账</title><type>2000</type><wcpayinfo>" +
            "<feedesc>¥100.00</feedesc><pay_memo>房租</pay_memo>" +
            "<receiver_username>wxid_x</receiver_username></wcpayinfo></appmsg></msg>")
        assertEquals("【转账·转入：¥100.00（备注：房租）】", transfer.materialText())
        assertEquals("【转账·转出：¥100.00（备注：房租）】", transfer.copy(isSend = 1).materialText())
        val red = meta(49, "<msg><appmsg><title>微信红包</title><type>2001</type><wcpayinfo>" +
            "<sendertitle>恭喜发财，大吉大利</sendertitle></wcpayinfo></appmsg></msg>")
        assertEquals("【红包：恭喜发财，大吉大利】", red.materialText())
        val paid = meta(49, "<msg><appmsg><title>转账</title><type>2000</type><wcpayinfo>" +
            "<paysubtype>3</paysubtype><feedesc>¥0.01</feedesc></wcpayinfo></appmsg></msg>")
        assertEquals("【转账·已收款：¥0.01】", paid.materialText())
        val plain = meta(49, "<msg><appmsg><title>微信红包</title><type>2001</type></appmsg></msg>")
        assertEquals("【红包】", plain.materialText())
    }

    @Test fun `flagged appmsg types are still parsed`() {
        // 微信给 appmsg 加高位 flag：436207665 就是 49 的变体，以前会退化成 [媒体]。
        val card = meta(436207665, "<msg><appmsg><title>百度地图</title><url>https://x</url></appmsg></msg>")
        assertEquals("链接", card.evidenceKind())
        assertEquals("百度地图", card.materialText())
    }

    @Test fun `empty call record still reaches the context`() {
        val context = ReplyContext.collect("123@chatroom", listOf(meta(50, "")))
        assertEquals(listOf("【通话记录】"), context.messages.map { it.text })
    }
}
