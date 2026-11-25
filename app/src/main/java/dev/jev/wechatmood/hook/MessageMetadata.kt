package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.MessagePolicy
import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.voice.VoiceText

data class MessageMetadata(val type: Int, val isSend: Int, val content: String, val talker: String,
    val messageId: Long = 0, val createdAt: Long = 0, val imagePath: String = "", val serverId: Long = 0,
    val displayName: String = "") {
    private val quoteReply by lazy {
        if (isAppMessage() && isSend in 0..1 && talker.isNotBlank()) QuoteMessage.parse(body()) else null
    }
    fun quotedMessage() = quoteReply?.quoted
    fun isAnalysisContent() = type in setOf(1, 34) || quoteReply != null
    fun incomingText(): String? = if (isSend == 0) plainText() else null
    fun isReplyTarget(allowMedia: Boolean = false): Boolean = isSend == 0 && talker.isNotBlank() && messageId > 0 &&
        ((type == 1 && content.isNotBlank()) || type == 34 || quoteReply?.text?.isNotBlank() == true ||
            // 视频（43）与小视频（62）也算可回复目标：内容靠抽关键帧读，不是只看类型。
            (allowMedia && (type == 3 || type == 43 || type == 47 || type == 62 || isAppMessage())))

    fun voiceSource(): VoiceSource? = if (type == 34 && isSend in 0..1 && talker.isNotBlank() && messageId > 0)
        VoiceSource(talker, messageId, createdAt, isSend, imagePath, serverId) else null

    fun analysisText(): String? = if (voiceSource() != null) VoiceText.WAITING else plainText()

    fun plainText(): String? {
        if (isSend !in 0..1 || talker.isBlank()) return null
        val text = if (type == 1) body() else quoteReply?.text ?: return null
        return MessagePolicy.textOrNull(text)
    }

    /** appmsg 的 type 会带高位 flag：49、822083633、436207665 都是同一种消息，按低 16 位判断。 */
    fun isAppMessage(): Boolean = (type and 0xFFFF) == 49

    /**
     * 依据弹窗专用：type 49 的正文在 title/des 里。
     * 只用于展示「这条依据到底说了什么」，不参与回复上下文，避免把链接标题当成对话。
     */
    fun evidenceBody(): String? {
        if (!isAppMessage()) return null
        val raw = body()
        if (raw.isBlank()) return null
        // 合并转发：摊平成「谁说了什么」，能看到具体是哪几句支撑了这条事实。
        if (dev.jev.wechatmood.reply.ForwardDigest.isForward(raw)) {
            val digest = dev.jev.wechatmood.reply.ForwardDigest.parse(raw) ?: return null
            // 记录页里描述过图片/视频时，依据弹窗也显示内容，而不是一串 [图片]。
            return dev.jev.wechatmood.reply.RecordMedia.applyToTranscript(talker, messageId,
                digest.transcript().take(1500)).ifBlank { null }
        }
        // 红包、转账只显示类型，不带金额和备注；必须排在合并转发之后，
        // 否则转发内容里的 <wcpayinfo> 会把整段转发正文吞掉。
        if (isPayment()) return null
        if (!raw.contains('<')) return MessagePolicy.textOrNull(raw)
        val pattern = Regex("<(?:title|des)(?:\\s[^>]*)?>(.*?)</(?:title|des)>", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(raw).map { cleanXml(it.groupValues[1]) }
            .firstOrNull { it.isNotBlank() }?.take(1000)
    }

    /** 依据弹窗里显示的短类型：49 再按内容细分，不再一律写「链接、引用或转发」。 */
    fun evidenceKind(): String {
        if (isAppMessage()) return when {
            dev.jev.wechatmood.reply.ForwardDigest.isForward(content) -> "合并转发"
            quoteReply != null -> "引用回复"
            // 文件消息只有文件名和大小，标成「链接」会让模型以为内容已经读过。
            content.contains("<fileext>") ||
                (content.contains("<appattach>") && content.contains("<totallen>")) -> "文件"
            // 红包、转账只告诉模型发生过这件事，金额和备注不读。
            content.contains("<type>2001</type>") -> "红包"
            content.contains("<type>2000</type>") -> "转账"
            content.contains("<wcpayinfo") -> "红包"
            content.contains("<url>") -> "链接"
            else -> "链接、引用或转发"
        }
        return when (type) {
            1 -> "文字"
            3 -> "图片"
            34 -> "语音"
            42 -> "名片"
            43 -> "视频"
            47 -> "表情"
            48 -> "位置"
            10000, 10002 -> "系统消息"
            else -> "类型 " + type
        }
    }

    /**
     * 位置和名片在素材里只是占位，但 XML 里有一个可读的字段：地点名、名片昵称。
     * 取出来拼进占位，模型才知道「说的是哪儿」「指的是谁」。
     */
    fun mediaText(): String? = when (type) {
        42 -> attr("nickname")
        48 -> listOf("poiname", "label", "address").firstNotNullOfOrNull { attr(it) }
        else -> null
    }

    /** 语音、视频通话记录（type 50）的正文：「通话时长 02:31」这类，群里带 wxid 前缀。 */
    fun callText(): String? {
        if (type != 50) return null
        val raw = body()
        // XML 形态的通话消息（取消、未接）洗出来的只是时长数字，没有可读正文。
        if (raw.contains('<')) return null
        return MessagePolicy.textOrNull(raw)?.takeIf { it.isNotBlank() }
    }

    /**
     * 转账金额与备注、红包祝福语。
     * 转账的 feedesc / pay_memo 是聊天里本来就显示的；红包本地消息没有金额，只取祝福语，不猜。
     */
    fun paymentText(): String? {
        if (!isAppMessage()) return null
        if (content.contains("<type>2000</type>")) {
            // 诊断：只记标签名，不记金额和用户名，用来确认收款状态到底在哪个字段。
            dev.jev.wechatmood.core.MoodLog.i("PAYMENT_TAGS send=$isSend tags=" +
                Regex("<([A-Za-z_]+)[ >]").findAll(content).map { it.groupValues[1] }
                    .distinct().take(16).joinToString(","))
            dev.jev.wechatmood.core.MoodLog.i("PAYMENT_DETAIL send=$isSend paysubtype=" +
                tag("paysubtype") + " type=" + tag("type") + " des=" + tag("des"))
            val amount = tag("feedesc")?.take(20) ?: tag("des")?.take(20) ?: return null
            val memo = tag("pay_memo")?.take(40)
            return if (memo.isNullOrBlank()) amount else "$amount（备注：$memo）"
        }
        if (content.contains("<type>2001</type>") || content.contains("<wcpayinfo")) {
            return tag("sendertitle")?.take(40) ?: tag("receivertitle")?.take(40) ?: tag("des")?.take(40)
        }
        return null
    }

    private fun tag(name: String): String? =
        Regex("<" + name + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + name + ">", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.groupValues?.get(1)?.let { cleanXml(it) }?.takeIf { it.isNotBlank() }

    private fun attr(name: String): String? =
        attrPatterns.getOrPut(name) { Regex("<[^>]*\\s" + name + "=\"([^\"]*)\"") }
            .find(content)?.groupValues?.get(1)?.let { cleanXml(it) }?.takeIf { it.isNotBlank() }

    /** 红包、转账：只认类型，不碰 wcpayinfo 里的金额和备注。 */
    private fun isPayment(): Boolean = content.contains("<type>2001</type>") ||
        content.contains("<type>2000</type>") || content.contains("<wcpayinfo")

    /**
     * 统计和依据用的素材文本：正文 → 卡片/转发的可读部分 → 位置、名片、通话、支付这些类型事实。
     * 以前红包卡片在这里退成 [媒体]，模型看不到群里发过红包。
     */
    private fun String.takePoints(max: Int): String =
        if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

    fun materialText(): String = analysisText() ?: evidenceBody() ?: when {
        type == 50 -> callText() ?: "【通话记录】"
        type == 42 || type == 48 -> {
            val label = dev.jev.wechatmood.reply.ReplyContext.mediaLabel(type)
            mediaText()?.takePoints(60)?.let { "【$label：$it】" } ?: "【$label】"
        }
        // 视频、表情、小视频只有类型：标成【视频】比 [媒体] 更像人话，总结里才不会出现「发了条媒体」。
        type == 43 || type == 47 || type == 62 ->
            "【${dev.jev.wechatmood.reply.ReplyContext.mediaLabel(type)}】"
        // 领取、收款、过期都在系统消息正文里，别一律压成【系统消息】。
        type == 10000 || type == 10002 -> when {
            content.contains("领取了") && content.contains("红包") -> "【红包·已被领取】"
            content.contains("已过期") && content.contains("红包") -> "【红包·已过期】"
            content.contains("已收款") || content.contains("已被接收") -> "【转账·已收款】"
            else -> "【系统消息】"
        }
        isAppMessage() -> {
            val kind = evidenceKind()
            val detail = if (kind == "转账" || kind == "红包") paymentText() else null
            // 转账必须带方向：两条同样的 0.01，一条是发出、一条是收到，只给金额模型会写反。
            val label = if (kind == "转账") when (tag("paysubtype")) {
                // 3 是收款后的状态消息：它不是我收到的另一笔，别写成「转入」。
                "3" -> "转账·已收款"
                else -> if (isSend == 1) "转账·转出" else "转账·转入"
            } else kind
            if (detail != null) "【$label：$detail】" else "【$label】"
        }
        else -> "[媒体]"
    }

    /** 去掉 appmsg 里的内层标签与实体，只留人能读的文字。 */
    private fun cleanXml(value: String): String = value
        .replace(Regex("<[^>]{1,300}>"), "")
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
            runCatching { String(Character.toChars(match.groupValues[1].toInt(16))) }.getOrDefault("")
        }
        .replace(Regex("&#(\\d+);")) { match ->
            runCatching { String(Character.toChars(match.groupValues[1].toInt())) }.getOrDefault("")
        }
        .trim()

    /**
     * 群消息正文前缀里的发送者 wxid / 微信号；私聊和自己发的返回 null。
     *
     * 前缀的分隔符不止一种：文本和引用是「wxid:\n正文」，语音是「wxid:」，
     * 视频这类是「wxid:0:0」。以前只认「:\n」，视频消息就整批变成「没有发送者」，
     * 统计里全被并成一个「未署名」。
     */
    internal fun groupSender(): String? {
        if (isSend != 0 || !talker.endsWith("@chatroom")) return null
        val head = if (content.contains(":\n")) content.substringBefore(":\n") else content.substringBefore(':')
        return head.takeIf {
            it.isNotBlank() && it.length in 4..128 &&
                it.none { c -> c.isWhitespace() || c == '<' || c == '>' } &&
                (it.startsWith("wxid") || it.matches(Regex("[A-Za-z0-9_@.\\-]{4,64}")))
        }
    }
    private fun body(): String {
        if (groupSender() == null || type == 34) return content
        // 群消息前缀不止「wxid:\n」一种，语音和通话这类没有换行，直接按冒号切。
        return if (content.contains(":\n")) content.substringAfter(":\n") else content.substringAfter(':')
    }

    /** 发送者标识（wxid 或微信号），和 speaker 一起用就能把「显示名 → 原始 id」对上。 */
    fun senderId(): String = if (isSend == 1) "" else groupSender().orEmpty()

    fun speaker(): String = when {
        isSend == 1 -> "我"
        talker.endsWith("@chatroom") -> displayName.takeIf { it.isNotBlank() } ?: groupSender() ?: "对方（群成员未知）"
        else -> "对方"
    }
    companion object {
        private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Map<String, java.lang.reflect.Field?>>()
        private val attrPatterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
        private val dumped = java.util.concurrent.atomic.AtomicBoolean(false)
        /** 最近一次探测到的消息对象字段名，诊断用。 */
        @Volatile var lastFields: List<String> = emptyList()

        /** 一次性把消息对象的字段名写进日志，方便确认昵称到底存在哪个字段。 */
        private fun dumpFields(item: Any) {
            if (!dumped.compareAndSet(false, true)) return
            runCatching {
                val names = generateSequence(item.javaClass) { it.superclass }.takeWhile { it != Any::class.java }
                    .flatMap { clazz -> clazz.declaredFields.map { it.name } }.distinct().toList()
                lastFields = names
                dev.jev.wechatmood.core.MoodLog.i("MSG_FIELDS count=${names.size} names=${names.take(60)}")
            }
        }

        private fun fields(type: Class<*>): Map<String, java.lang.reflect.Field?> = fieldCache.getOrPut(type) {
            listOf("field_type", "field_isSend", "field_content", "field_talker", "field_msgId", "field_createTime",
                "field_imgPath", "field_msgSvrId",
                // 群成员显示名可能在这些字段里，取到就优先用
                "field_displayName", "field_nickName", "field_senderName",
                "field_chatroomMemberName", "field_memberName", "field_chatroomNickName").associateWith { name ->
                generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
                    .firstNotNullOfOrNull { clazz ->
                        runCatching { clazz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                    }
            }
        }

        fun read(item: Any?): MessageMetadata? {
            if (item == null) return null
            dumpFields(item)
            return runCatching {
                val accessors = fields(item.javaClass)
                fun value(name: String): Any? = accessors[name]?.get(item)
                MessageMetadata(value("field_type") as? Int ?: return null,
                    value("field_isSend") as? Int ?: return null,
                    value("field_content") as? String ?: "",
                    value("field_talker") as? String ?: "",
                    (value("field_msgId") as? Number)?.toLong() ?: 0,
                    (value("field_createTime") as? Number)?.toLong() ?: 0,
                    value("field_imgPath") as? String ?: "", (value("field_msgSvrId") as? Number)?.toLong() ?: 0,
                    listOf("field_displayName", "field_nickName", "field_senderName", "field_chatroomMemberName",
                        "field_memberName", "field_chatroomNickName")
                        .firstNotNullOfOrNull { name -> (value(name) as? String)?.takeIf { it.isNotBlank() } } ?: "")
            }.getOrNull()
        }
    }
}
