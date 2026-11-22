package dev.jev.wechatmood.reply

/**
 * chatroom.roomdata 的解析：群成员在群里的昵称（群备注）在这里。
 *
 * 以前只解析 `chatroom.memberlist` 的 XML，可新版微信（实测 8.0.80）那张表里 `memberlist`
 * 已经是 `wxid;wxid;…` 的纯 id 串，群昵称搬进了 `roomdata` 这个 protobuf 列。
 * 于是「群名片」一层永远空，只剩 rcontact：好友能查到，群里没加好友的人就永远
 * 「未获取昵称」。这里把 roomdata 补上，微信页面上显示什么名字，这里就拿到什么名字。
 *
 * 结构（protobuf wire format，与 WeKit 的 ChatRoomDataProto 一致）：
 * ```
 * ChatRoomData { repeated Member members = 1; }
 * Member { string wxId = 1; string displayName = 2; … string inviterWxId = 4; }
 * ```
 * 不引 protobuf 依赖：只认这几个字段，坏数据一律当没有，绝不抛异常。
 */
object GroupRoomData {
    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH = 2

    /** wxid → 群昵称。没设群昵称的成员（displayName 为空）不放进结果，交给上层兜底。 */
    fun parse(blob: ByteArray?): Map<String, String> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        eachField(blob, 0, blob.size) { field, wire, start, end, _ ->
            if (wire != WIRE_LENGTH || field != 1) return@eachField
            val member = member(blob, start, end) ?: return@eachField
            if (member.first.isNotEmpty() && member.second.isNotEmpty()) out[member.first] = member.second
        }
        return out
    }

    /** 成员 id 列表，顺序与 roomdata 里一致；昵称缺了也能拿到人。 */
    fun memberIds(blob: ByteArray?): List<String> {
        if (blob == null || blob.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        eachField(blob, 0, blob.size) { field, wire, start, end, _ ->
            if (wire != WIRE_LENGTH || field != 1) return@eachField
            val id = member(blob, start, end)?.first.orEmpty()
            if (id.isNotEmpty() && id !in out) out += id
        }
        return out
    }

    /** 单个成员块里取 (wxid, 群昵称)；字段缺失就给空串。 */
    private fun member(blob: ByteArray, from: Int, to: Int): Pair<String, String>? {
        var id = ""
        var name = ""
        eachField(blob, from, to) { field, wire, start, end, _ ->
            if (wire != WIRE_LENGTH) return@eachField
            when (field) {
                1 -> if (id.isEmpty()) id = text(blob, start, end)
                2 -> if (name.isEmpty()) name = text(blob, start, end)
            }
        }
        if (id.isEmpty()) return null
        return id to name
    }

    /**
     * 逐字段走一遍。每次循环都推进下标，坏数据在上面截断，不会原地打转。
     * varint 字段的负载用 value 带回来，长度分隔字段用 [start, end) 区间。
     */
    private inline fun eachField(blob: ByteArray, from: Int, to: Int,
        onField: (field: Int, wire: Int, start: Int, end: Int, value: Long) -> Unit) {
        val limit = to.coerceAtMost(blob.size)
        var index = from.coerceAtLeast(0)
        while (index < limit) {
            val key = varint(blob, index, limit) ?: return
            index = key.second
            val field = (key.first ushr 3).toInt()
            when (key.first and 7L) {
                WIRE_VARINT.toLong() -> {
                    val value = varint(blob, index, limit) ?: return
                    index = value.second
                    onField(field, WIRE_VARINT, index, index, value.first)
                }
                1L -> {
                    if (index + 8 > limit) return
                    onField(field, 1, index, index + 8, 0)
                    index += 8
                }
                WIRE_LENGTH.toLong() -> {
                    val length = varint(blob, index, limit) ?: return
                    index = length.second
                    if (length.first < 0 || length.first > limit - index) return
                    val end = index + length.first.toInt()
                    onField(field, WIRE_LENGTH, index, end, 0)
                    index = end
                }
                5L -> {
                    if (index + 4 > limit) return
                    onField(field, 5, index, index + 4, 0)
                    index += 4
                }
                else -> return
            }
        }
    }

    private fun varint(blob: ByteArray, from: Int, to: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = from
        while (index < to && shift < 64) {
            val byte = blob[index].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            index++
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    /**
     * 诊断用：某个成员块里所有长度分隔字段的原文，按字段号分组。
     * 微信 8.0.7x 的群聊显示名是「群昵称（实名尾字）」，实名如果也在 roomdata 里，
     * 就能给没有群昵称的人一个能看的称呼，而不是「未获取昵称#N」。
     */
    fun memberFields(blob: ByteArray?, username: String): Map<Int, List<String>> {
        if (blob == null || blob.isEmpty() || username.isEmpty()) return emptyMap()
        var found: Map<Int, List<String>>? = null
        eachField(blob, 0, blob.size) { field, wire, start, end, _ ->
            if (wire != WIRE_LENGTH || field != 1 || found != null) return@eachField
            if (!blockHasUser(blob, start, end, username)) return@eachField
            val out = LinkedHashMap<Int, MutableList<String>>()
            eachField(blob, start, end) { nested, nestedWire, from, to, _ ->
                if (nestedWire != WIRE_LENGTH) return@eachField
                val text = text(blob, from, to)
                if (text.isNotEmpty()) out.getOrPut(nested) { ArrayList() }.add(text)
            }
            found = out
        }
        return found ?: emptyMap()
    }

    /** 成员块的 field 1 是不是这个 wxid。 */
    private fun blockHasUser(blob: ByteArray, from: Int, to: Int, username: String): Boolean {
        var matched = false
        eachField(blob, from, to) { field, wire, start, end, _ ->
            if (!matched && wire == WIRE_LENGTH && field == 1) matched = text(blob, start, end) == username
        }
        return matched
    }

    private fun text(blob: ByteArray, start: Int, end: Int): String =
        if (end <= start) "" else dev.jev.wechatmood.core.TextClean.name(
            String(blob, start, end - start, Charsets.UTF_8))
}
