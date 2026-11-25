package dev.jev.wechatmood.reply


/**
 * 合并转发消息：微信把 type=49 / appmsg type=19 的内容放在
 * `<recorditem><![CDATA[ <recordinfo>… ]]></recorditem>` 里。
 *
 * 结构化字段是 `<datalist>` 下的 `<dataitem>`，比 `<desc>` 那串
 * 「昵称: 内容」更可靠，所以优先按 dataitem 取；拿不到时退回 desc。
 * 嵌套的聊天记录只展开一层，再深的直接标注，避免一条消息把上下文撑爆。
 */
object ForwardDigest {
    const val MAX_ITEMS = 300
    const val MAX_ITEM_CHARS = 150
    const val MAX_TOTAL_CHARS = 40000
    /** 原始 XML 上限：转发里夹带代码或超大卡片时，超过这个长度只处理前面的部分。 */
    const val MAX_RAW_CHARS = 512 * 1024
    /** datatype=17 是嵌套的聊天记录，内层完整 XML 在 <recordxml> 里。 */
    private const val DATATYPE_CHAT_RECORD = 17
    /** 展开深度上限；测试要构造超深嵌套，所以不设成 private。 */
    internal const val MAX_DEPTH = 6
    /** 「昵称: 内容」里昵称的长度上限；超过这个长度就当正文里自带冒号，不拆。 */
    private const val MAX_SENDER = 12
    /** 长得像人名但不是人名的前缀，拆出来只会把正文读成「某某说」。 */
    private val NOT_NAMES = setOf("我说", "你说", "他说", "她说", "我说过", "回复", "备注")
    /** 纯拟声和感叹，删掉不影响“这事有没有结果”。 */
    private const val CHATTER_CHARS = "哈呵嘿嘻哦喔噢啊呀哇诶欸唉嗷呜吧呢啦咯嘛嘞噗"
    /** 应答词的口语后缀：「好的」「行吧」「收到啦」都要认出来。 */
    private const val ACK_SUFFIX = "了啦吧呀啊哦的哈咯嘛呢"
    /** 能单独成句的应答字：「好」「对」「行」「在」往往是整段记录唯一的结论。 */
    private const val ACK_KEYS = "好对是行可以成在来去嗯"
    private val ACK_WORDS = setOf("收到", "明白", "知道", "同意", "可以", "没问题", "没有", "有空", "来了")
    private const val FILLER_MIN_LENGTH = 120
    private const val FILLER_MAX_PERIOD = 8
    private const val FILLER_PREFIX_MIN = 32
    /** 重复填充检测最多看这么多个字符：正常消息远小于它，超长转发也不必全量扫。 */
    private const val FILLER_SCAN_CHARS = 4096
    /** 任意 2 到 8 字符的片段连续重复 6 次以上，当作填充。 */
    private val REPEAT = Regex("(.{2,8}?)\\1{5,}", setOf(RegexOption.DOT_MATCHES_ALL))
    private val PLACEHOLDER = Regex("^\\[[^]]{1,6}]$")
    /**
     * 微信 XML 缺字段时会写成 nullnull…（或 undefined…）。
     * 这些串会跟着转录进提示词，模型照抄到摘要开头。零宽字符夹在中间时周期正则认不出来，
     * 所以单独按「连续 3 个以上」删掉。
     */
    private val NULL_RUN = Regex("(?i)(?:null|undefined){3,}")
    private val DESC = Regex("<datadesc(?:\\s[^>]*)?>(.*?)</datadesc>", RegexOption.DOT_MATCHES_ALL)
    private val NOISE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\u200B-\\u200F\\u2028\\u2029\\uFEFF]")

    data class Item(val type: Int, val sender: String, val text: String, val time: String, val nested: Boolean = false)
    data class Message(val title: String, val chatroom: Boolean, val items: List<Item>, val omitted: Int, val nested: Int,
        val truncatedRaw: Boolean = false, val rawCount: Int = 0) {
        /** 界面上的「共 N 条」用解析到的原始条数：items 是压缩后的段落，直接显示会比微信里看到的少。 */
        val count: Int get() = if (rawCount > 0) rawCount else items.size
        /** 给模型的纯文本：不带 XML、不带未展开的条数。 */
        fun transcript(): String = buildString {
            if (title.isNotBlank()) append("聊天记录：").append(title.cleanNoise().stripFiller().stripNullRuns()).append('\n')
            items.forEachIndexed { index, item ->
                append(index + 1).append(". ")
                if (item.sender.isNotBlank()) append(item.sender.cleanNoise().stripNullRuns()).append(": ")
                append(item.text.cleanNoise().stripFiller().stripNullRuns()).append('\n')
            }
            if (omitted > 0) append("（另有 ").append(omitted).append(" 条未展开）\n")
        }.trim()
    }

    fun isForward(raw: String): Boolean = raw.contains("<recorditem>") && raw.contains("<recordinfo")

    fun parse(raw: String): Message? {
        // 只解析外层结构，媒体 URL、头像、base64 这些字段一律不取。
        val truncatedRaw = raw.length > MAX_RAW_CHARS
        val safe = if (truncatedRaw) raw.substring(0, MAX_RAW_CHARS) else raw
        val body = recordInfo(safe) ?: return null
        val items = mutableListOf<Item>()
        var omitted = 0
        var nested = 0
        var budget = MAX_TOTAL_CHARS
        collect(body, depth = 1, out = items, budget = { budget }, spend = { budget -= it },
            omitted = { omitted++ }, nested = { nested++ })
        // 截在记录内部时前缀里仍可能有完整条目，只要解析出东西就照常返回。
        if (items.isEmpty()) return null
        // 结构解析之外再兜底扫一遍所有 datadesc：四层嵌套时按 dataitem 配对容易漏，
        // 漏掉的内容不该因为层级对不上就消失；但补扫和结构解析共用同一份预算，
        // 否则超过总字数或深度限制的内容会从这条通路重新加回来。
        val supplemented = supplement(body, items, budget)
        // 纯语气和同人连发会在 compress 里被并掉或丢掉，这部分以前不计入 omitted，
        // 于是「只解析出几条」看起来像丢消息。把压缩前的条数和被当成语气词的条数一起记下来。
        val chatter = supplemented.count { it.text.isChatter() || it.text.isFiller() }
        val merged = compress(supplemented)
        dev.jev.wechatmood.core.MoodLog.i(
            "FORWARD_DIGEST parsed=${items.size} supplemented=${supplemented.size} chatter=$chatter " +
                "merged=${merged.size} nested=$nested omitted=$omitted raw=${safe.length} truncated=$truncatedRaw")
        // 只记结构计数，不记正文：用来区分「XML 里本来就只有这几条」和「结构没认出来」。
        val pairs = dataItems(body)
        val types = pairs.map { (attributes, _) ->
            Regex("""datatype\s*=\s*"(\d+)""").find(attributes)?.groupValues?.get(1) ?: "?"
        }.groupingBy { it }.eachCount()
        val descs = DESC.findAll(body).map { it.groupValues[1] }.toList()
        // 只有 datadesc 没有 dataitem 的转发拿不到发送人：看这些行是否自带「昵称: 」前缀，
        // 决定下一版能不能把名字补回去。只记计数，不记内容。
        val named = descs.count { value ->
            val head = if (value.contains('：')) value.substringBefore('：') else value.substringBefore(':')
            head.isNotEmpty() && head.length <= 20 && !head.contains('\n')
        }
        dev.jev.wechatmood.core.MoodLog.i(
            "FORWARD_DIGEST_SHAPE dataitem=${pairs.size} types=$types " +
                "desc=${descs.size} nameLike=$named " +
                "recordinfo=${Regex("<recordinfo").findAll(body).count()} " +
                "recordxml=${Regex("<recordxml").findAll(body).count()} body=${body.length}")
        return Message(tag(body, "title").decode().cleanNoise().stripFiller().stripNullRuns(), tag(body, "isChatRoom") == "1",
            merged, omitted, nested, truncatedRaw, rawCount = supplemented.size)
    }

    private fun collect(
        body: String,
        depth: Int,
        out: MutableList<Item>,
        budget: () -> Int,
        spend: (Int) -> Unit,
        omitted: () -> Unit,
        nested: () -> Unit,
    ) {
        // 不能先截 <datalist>：嵌套记录里的 <datalist> 会让非贪婪匹配提前闭合。
        val pairs = dataItems(body)
        if (pairs.isEmpty()) {
            // 外层没有 dataitem：本机微信的转发是 desc 平铺，被转进来的那条记录整段塞在
            // <recordxml> 里（实测 recordinfo=2 recordxml=1）。原来只拆 <desc>，
            // 内层十几条内容就被压成一行「[聊天记录]」——摘要里自然看不到「谁说了什么」。
            val inner = block(body, "recordxml").takeIf { it.contains("<recordinfo") }
            var expanded = false
            fun expandNested() {
                if (inner == null || expanded) return
                expanded = true
                nested()
                if (depth < MAX_DEPTH) collect(inner, depth + 1, out, budget, spend, omitted, nested)
            }
            tag(body, "desc").decode().split('\n').filter { it.isNotBlank() }.forEach { line ->
                if (out.size >= MAX_ITEMS || budget() <= 0) { omitted(); return@forEach }
                val sender = line.substringBefore(':', "").trim()
                val text = line.substringAfter(':', line).trim()
                    .cleanNoise().trimFillerPrefix().stripFiller().take(MAX_ITEM_CHARS)
                // 占位行所在的位置就是内层记录的位置：就地展开，顺序才不会跑到最前面。
                if (inner != null && !expanded &&
                    (text == "[聊天记录]" || text.contains("<recordinfo") || text.isBlank())) {
                    expandNested()
                    return@forEach
                }
                if (text.isBlank() || text.isFiller()) { omitted(); return@forEach }
                spend(text.length + sender.length)
                out += Item(1, sender, text, "")
            }
            // desc 里连占位行都没有时也要把内层补上，否则整段记录凭空消失。
            expandNested()
            return
        }
        pairs.forEach { (attributes, itemBody) ->
            if (out.size >= MAX_ITEMS || budget() <= 0) { omitted(); return@forEach }
            val type = Regex("""datatype\s*=\s*"(\d+)"""").find(attributes)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            var text = tag(itemBody, "datadesc").decode()
            val sender = tag(itemBody, "sourcename").decode().cleanNoise().stripFiller()
            val time = tag(itemBody, "sourcetime").decode()
            // 公众号、视频、语音、表情这些类型，微信通常已经写成 [视频]/标题；为空时补个占位。
            if (text.isBlank()) {
                text = tag(itemBody, "datatitle").decode().ifBlank { "[类型$type]" }
            }
            // 嵌套：datatype=17，内层完整 XML 在 <recordxml>；老格式可能塞在 datadesc 里。
            val nestedXml = block(itemBody, "recordxml")
            if (type == DATATYPE_CHAT_RECORD || nestedXml.contains("<recordinfo") || text.contains("<recordinfo")) {
                nested()
                if (depth < MAX_DEPTH) {
                    val inner = when {
                        nestedXml.contains("<recordinfo") -> nestedXml
                        text.contains("<recordinfo") -> text
                        else -> ""
                    }
                    if (inner.isNotEmpty()) {
                        // recordxml 本身就是 <recordinfo>…</recordinfo>，直接递归；
                        // 再用正则截 recordinfo 会被更深的同名标签提前闭合。
                        collect(inner, depth + 1, out, budget, spend, omitted, nested)
                        return@forEach
                    }
                    // 只有平铺文本时，退化成「昵称: 内容」行，不再递归。
                    text = tag(itemBody, "datadesc").decode()
                    // 记录被截断、内层闭合不上时展开不了，别把原始 XML 当正文塞给模型。
                    if (text.contains("<recordinfo")) text = "[聊天记录]"
                } else {
                    text = "[聊天记录]"
                }
            }
            // 整屏重复字符（群友常发 uinull / 零宽填充）没有信息量，直接跳过，别占摘要开头。
            val cut = text.cleanNoise().trimFillerPrefix().stripFiller().take(MAX_ITEM_CHARS)
            if (cut.isBlank() || cut.isFiller()) {
                omitted()
                return@forEach
            }
            spend(cut.length + sender.length)
            out += Item(type, sender, cut, time, depth > 1)
        }
    }

    /**
     * 逐条切 `<dataitem>`，按标签深度配对。
     * 嵌套记录的内层也带 `<dataitem>`，非贪婪正则会在第一个 `</dataitem>` 提前截断。
     */
    private fun dataItems(src: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < src.length) {
            val start = src.indexOf("<dataitem", index)
            if (start < 0) break
            val attrEnd = src.indexOf('>', start)
            if (attrEnd < 0) break
            val attributes = src.substring(start + "<dataitem".length, attrEnd)
            var depth = 1
            var cursor = attrEnd + 1
            var end = -1
            while (cursor < src.length) {
                val nextOpen = src.indexOf("<dataitem", cursor)
                val nextClose = src.indexOf("</dataitem>", cursor)
                if (nextClose < 0) break
                if (nextOpen in 0 until nextClose) {
                    depth++
                    cursor = nextOpen + 1
                    continue
                }
                depth--
                if (depth == 0) { end = nextClose; break }
                cursor = nextClose + 1
            }
            if (end < 0) break
            out += attributes to src.substring(attrEnd + 1, end)
            index = end + "</dataitem>".length
        }
        return out
    }

    /**
     * 取 `<recordinfo>` 的内容。
     * 原始 XML 可能因为长度上限被截断，也可能本身就是残缺的：
     * 这时不再要求 CDATA 和 recordinfo 都闭合，直接把起点之后的部分交给解析器，
     * `<dataitem>` 配对会自己停在最后一条完整记录上。
     */
    private fun recordInfo(raw: String): String? {
        val cdata = Regex("""<recorditem>\s*<!\[CDATA\[(.*?)]]></recorditem>""", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)
        if (cdata != null) return cdata
        val cdataOpen = Regex("""<recorditem>\s*<!\[CDATA\[""").find(raw)
        if (cdataOpen != null) return raw.substring(cdataOpen.range.last + 1)
        // 少数包不带 CDATA，直接取 recordinfo；未闭合时取到末尾。
        val open = Regex("""<recordinfo(?:\s[^>]*)?>""").find(raw) ?: return null
        val start = open.range.last + 1
        val close = Regex("</recordinfo>").find(raw, start)
        return if (close != null) raw.substring(start, close.range.first) else raw.substring(start)
    }

    /**
     * 压缩：同一人连续发言合并成一条，纯语气和纯表情丢掉。
     * 长转发里这类内容占一半以上，压完输入更短，模型也更容易抓重点。
     */
    private fun compress(items: List<Item>): List<Item> {
        val out = mutableListOf<Item>()
        for (item in items) {
            if (item.text.isBlank() || item.text.isFiller() || item.text.isChatter()) continue
            val last = out.lastOrNull()
            // 只合并同一个人的连续发言。全局补扫出来的条目没有发送人，以前空 sender 被当成「同一个人」，
            // 于是几十条挤成几段：看不出谁说的，时间和条数也一起丢了。
            val sameSender = last != null && item.sender.isNotBlank() &&
                last.sender.isNotBlank() && last.sender == item.sender
            if (last != null && sameSender && last.text.length + item.text.length < MAX_ITEM_CHARS) {
                out[out.lastIndex] = last.copy(text = last.text + "；" + item.text)
            } else {
                out += item
            }
        }
        return out
    }

    /**
     * 只有语气、表情或标点的短句，没有信息量。
     * 但“好”“对”“收到”这类短应答是“有没有结果”的关键，不能一起丢掉。
     */
    internal fun String.isChatter(): Boolean {
        val core = replace(Regex("[\\s\\p{Punct}，。！？、~～…：；]"), "")
        if (core.isEmpty()) return true
        if (core.length > 4) return false
        if (core.isAck()) return false
        return core.all { it in CHATTER_CHARS }
    }

    /** 短应答：整段转发里可能就这么一句结论，必须留下。 */
    private fun String.isAck(): Boolean {
        var word = lowercase()
        while (word.isNotEmpty() && word.last() in ACK_SUFFIX) word = word.dropLast(1)
        if (word.isEmpty()) return false
        return word in ACK_WORDS || (word.length <= 2 && word.all { it in ACK_KEYS })
    }

    /**
     * 用全局 datadesc 补齐结构解析漏掉的条目，只按文本去重，不重复计算字数。
     * 预算与结构解析共用：结构解析已经用掉的字数不能再从补扫里加回来；
     * 超过展开深度的 datadesc 也不补，否则等于绕开了 MAX_DEPTH。
     */
    internal fun supplement(raw: String, parsed: List<Item>, budget: Int): List<Item> {
        if (parsed.size >= MAX_ITEMS || budget <= 0) return parsed
        val seen = parsed.mapTo(HashSet()) { it.text }
        val extra = mutableListOf<Item>()
        var room = MAX_ITEMS - parsed.size
        var remaining = budget
        var cursor = 0
        var depth = 0
        // 全局扫描：结构解析在四层嵌套时只切出外层几条，内层的 datadesc 全靠这里补。
        for (match in DESC.findAll(raw)) {
            if (room <= 0 || remaining <= 0) break
            // 增量推进 `<recordinfo>` 开关，算出这条 datadesc 所在层级。
            while (true) {
                val nextOpen = raw.indexOf("<recordinfo", cursor)
                val nextClose = raw.indexOf("</recordinfo>", cursor)
                val next = when {
                    nextOpen < 0 -> nextClose
                    nextClose < 0 -> nextOpen
                    else -> minOf(nextOpen, nextClose)
                }
                if (next < 0 || next >= match.range.first) break
                if (next == nextOpen) {
                    depth++
                    cursor = nextOpen + "<recordinfo".length
                } else {
                    depth = maxOf(0, depth - 1)
                    cursor = nextClose + "</recordinfo>".length
                }
            }
            if (depth > MAX_DEPTH) continue
            val raw = match.groupValues[1].decode().cleanNoise()
            // 没有 <dataitem> 的转发拿不到 sourcename，但 datadesc 常常自带「昵称: 内容」：
            // 拆出来，摘要里才看得出是谁说的，同一个人的连续发言也才能合并。
            val (sender, body) = splitSpeaker(raw)
            val text = body.trimFillerPrefix().stripFiller().take(MAX_ITEM_CHARS)
            // 占位符（[聊天记录]、[图片]）不补，否则数量会虚增。
            if (text.isBlank() || text.isFiller() || text.matches(PLACEHOLDER) || !seen.add(text)) continue
            if (text.length > remaining) break
            extra += Item(0, sender, text, "", nested = true)
            room--
            remaining -= text.length
        }
        return if (extra.isEmpty()) parsed else parsed + extra
    }

    /**
     * 从「昵称: 内容」里拆出昵称。
     *
     * 只认短、且不含换行和常见标点的前缀：正文里本来就有冒号的情况（「我说：算了」）
     * 会把半句话当成人名，宁可保留原样也不拆错。
     */
    internal fun splitSpeaker(value: String): Pair<String, String> {
        val cut = value.indexOfFirst { it == '：' || it == ':' }
        if (cut <= 0 || cut > MAX_SENDER) return "" to value
        val head = value.substring(0, cut).trim()
        if (head.isEmpty() || head.length > MAX_SENDER || head in NOT_NAMES) return "" to value
        if (head.any { it == '\n' || it == '\r' || it in "，。！？、；\"“”" }) return "" to value
        val body = value.substring(cut + 1).trim()
        if (body.isEmpty()) return "" to value
        return head to body
    }

    /** 去掉不可打印字符与零宽填充，避免整屏噪声进摘要。 */
    internal fun String.cleanNoise(): String = NOISE.replace(this, "").trim()

    /** 删掉「nullnullnull…」这类缺字段填充，避免模型把它抄进摘要开头。 */
    internal fun String.stripNullRuns(): String = NULL_RUN.replace(this, "")

    /**
     * 去掉文本里任意位置的固定周期重复段（nullnull…、uinull…）。
     * 填充不一定在开头，截图里就是「[聊天记录]」夹在两段填充中间。
     */
    internal fun String.stripFiller(): String {
        if (length < FILLER_PREFIX_MIN) return this
        // 带反向引用的正则在长文本上是平方级的，一条 512KB 的转发能卡住界面。
        // 填充总在开头一小段出现，所以只拿前 FILLER_SCAN_CHARS 个字符去匹配，后面的原文原样接回去。
        if (length <= FILLER_SCAN_CHARS) return replaceFiller()
        val cut = if (Character.isHighSurrogate(this[FILLER_SCAN_CHARS - 1])) FILLER_SCAN_CHARS - 1 else FILLER_SCAN_CHARS
        return substring(0, cut).replaceFiller() + substring(cut)
    }

    private fun String.replaceFiller(): String = REPEAT.replace(this) { match ->
        val unit = match.groupValues[1]
        // 单字重复是语气（哈哈哈），不当填充。
        if (unit.toSet().size <= 1) match.value else ""
    }

    /** 行首整段是固定周期重复（如 nullnull…）时剥掉，保留后面的正文。 */
    internal fun String.trimFillerPrefix(): String {
        val text = cleanNoise()
        if (text.length < FILLER_PREFIX_MIN) return text
        // 「哈哈哈…」这类单字重复是语气，不是刷屏填充。
        if (text.take(FILLER_PREFIX_MIN).toSet().size <= 1) return text
        for (period in 2..FILLER_MAX_PERIOD) {
            var end = period
            while (end + period <= text.length &&
                text.regionMatches(end - period, text, end, period)) end += period
            if (end >= FILLER_PREFIX_MIN) return text.substring(end).trim()
        }
        return text
    }

    /** 长且按固定短周期重复的文本基本是刷屏填充，例如 uinulluinull…；正常的重复语气词不在此列。 */
    internal fun String.isFiller(): Boolean {
        val text = cleanNoise()
        if (text.length < FILLER_MIN_LENGTH) return false
        if (text.toSet().size <= 1) return false
        val sample = text.take(1200)
        for (period in 2..FILLER_MAX_PERIOD) {
            var repeating = true
            for (index in period until sample.length) {
                if (sample[index] != sample[index % period]) { repeating = false; break }
            }
            if (repeating) return true
        }
        return false
    }

    /** 同名标签可嵌套（recordxml 里还有 recordxml），必须按深度配对，不能用非贪婪正则。 */
    private fun block(src: String, name: String): String {
        val open = "<$name"
        val close = "</$name>"
        val start = src.indexOf(open)
        if (start < 0) return ""
        val head = src.indexOf('>', start)
        if (head < 0) return ""
        var depth = 1
        var cursor = head + 1
        while (cursor < src.length) {
            val nextOpen = src.indexOf(open, cursor)
            val nextClose = src.indexOf(close, cursor)
            if (nextClose < 0) return ""
            if (nextOpen in 0 until nextClose) {
                depth++
                cursor = nextOpen + 1
                continue
            }
            depth--
            if (depth == 0) return src.substring(head + 1, nextClose)
            cursor = nextClose + 1
        }
        return ""
    }

    /** 微信的标签常带属性（如 `<datalist count="3">`），匹配时要允许。 */
    private fun tag(body: String, name: String): String =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1).orEmpty()

    /** 微信把换行、空格写成 &#x0A; / &#x20;，还可能二次转义。 */
    internal fun String.decode(): String {
        if (isEmpty()) return this
        var value = this
        repeat(2) {
            value = value
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            value = ENTITY.replace(value) { match ->
                val hex = match.groupValues[1].takeIf { it.isNotEmpty() }
                val dec = match.groupValues[2].takeIf { it.isNotEmpty() }
                val code = hex?.toIntOrNull(16) ?: dec?.toIntOrNull() ?: return@replace match.value
                runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
            }
        }
        return value.trim()
    }

    private val ENTITY = Regex("""&#(?:[xX]([0-9a-fA-F]+)|(\d+));""")
}
