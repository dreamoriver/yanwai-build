package dev.jev.wechatmood.core

/** Process-only choices for individual incoming text messages. */
class ManualAnalysis {
    /**
     * 长按与收起各只留一份「消息 + 当时的上下文」，一条最多 24 条 context（几十到上百 KB）。
     * 微信里翻一天群聊就能攒出几百上千条，进程内存会一路上涨；这里按插入顺序淘汰最旧的。
     * 上限只是兜底，正常用不到：被淘汰的那条只是「不再自动分析 / 收起卡片重新显示」，重新长按即可恢复。
     * 用 LinkedHashMap（accessOrder=false）而不是 ConcurrentHashMap：只有它能免费给出插入顺序，
     * 并发安全改由 [lock] 保证。
     */
    private val lock = Any()
    private val selected = LinkedHashMap<String, AnalysisInput>()
    /** 用户主动收起的卡片；只存在进程内存，重启微信或重新长按会恢复。 */
    private val hidden = LinkedHashMap<String, AnalysisInput>()

    /** 收起状态只认「哪条消息」，不跟着 accountScope / 背景的短暂变化漂移。 */
    private fun hideKey(input: AnalysisInput): String? =
        if (input.messageId <= 0 || input.talker.isBlank()) null
        else input.talker + "\u0000" + input.messageId + "\u0000" + input.speaker

    /** 按插入顺序淘汰最旧的一条；已在表里的 key 不会因为重放而挪到队尾。 */
    private fun put(map: LinkedHashMap<String, AnalysisInput>, key: String, value: AnalysisInput) {
        synchronized(lock) {
            map[key] = value
            if (map.size > MAX_ENTRIES) {
                val oldest = map.keys.iterator()
                if (oldest.hasNext()) { oldest.next(); oldest.remove() }
            }
        }
    }

    fun hide(input: AnalysisInput): Boolean {
        val key = hideKey(input) ?: return false
        put(hidden, key, input)
        return true
    }

    fun reveal(input: AnalysisInput) {
        hideKey(input)?.let { key -> synchronized(lock) { hidden.remove(key) } }
    }

    fun isHidden(input: AnalysisInput): Boolean =
        hideKey(input)?.let { key -> synchronized(lock) { hidden.containsKey(key) } } ?: false

    fun select(input: AnalysisInput): Boolean {
        val identity = identity(input) ?: return false
        synchronized(lock) {
            // Keep the original context/cache key even if more history is loaded later.
            if (!selected.containsKey(identity))
                put(selected, identity, input.copy(context = input.context.toList()))
        }
        return true
    }

    fun selectedInput(input: AnalysisInput): AnalysisInput? =
        identity(input)?.let { key -> synchronized(lock) { selected[key] } }

    /** A user can select a message while its background account lookup is still pending. */
    fun resolveAccount(input: AnalysisInput, pendingScope: String) {
        val key = identity(input.copy(accountScope = pendingScope)) ?: return
        val pending = synchronized(lock) { selected.remove(key) } ?: return
        select(pending.copy(accountScope = input.accountScope))
    }

    fun clearConversation(talker: String) {
        synchronized(lock) {
            selected.entries.removeAll { it.value.talker == talker }
            hidden.entries.removeAll { it.value.talker == talker }
        }
    }

    fun allows(input: AnalysisInput, chatEnabled: Boolean): Boolean =
        chatEnabled || selectedInput(input) != null

    companion object {
        /** 每个表最多留这么多条；超了就丢最旧的一条，宁可少记也不让进程内存无界增长。 */
        private const val MAX_ENTRIES = 256

        fun identity(input: AnalysisInput): String? {
            if (input.messageId <= 0 || input.talker.isBlank() || MessagePolicy.textOrNull(input.text) == null) return null
            return MoodStore.keyOf(input.text, input.talker, messageId = input.messageId, speaker = input.speaker,
                zoneId = input.voice?.key ?: java.util.TimeZone.getDefault().id, quoted = input.quoted,
                accountScope = input.accountScope)
        }
    }
}
