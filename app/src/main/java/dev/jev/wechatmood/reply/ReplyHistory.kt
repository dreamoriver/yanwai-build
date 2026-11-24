package dev.jev.wechatmood.reply

data class RememberedReply(val context: ReplyContext, val suggestion: ReplySuggestion,
    val direction: String = "", val focusMessageId: Long? = null,
    val relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, val selectedPart: Int = 0,
    val topics: TopicBatch? = null, val customRelationship: String = "")

/** Successful replies only. Never persisted, logged, or shared across conversations. */
class ReplyHistory(private val capacity: Int = 12) {
    init { require(capacity > 0) }
    private val replies = LinkedHashMap<Pair<String, String>, RememberedReply>(capacity, 0.75f, true)
    // 进程级单例：单例实例被不同线程调用，LinkedHashMap 的 get 还会改 access-order 结构，
    // 不加锁会读到别的线程正在改的表（甚至死循环），所以三个入口都同步。
    @Synchronized fun remember(reply: RememberedReply, accountScope: String = "") {
        replies[accountScope to reply.context.talker] = reply
        while (replies.size > capacity) replies.remove(replies.keys.first())
    }
    @Synchronized fun recall(talker: String, focusMessageId: Long? = null, accountScope: String = ""): RememberedReply? =
        replies[accountScope to talker]?.takeIf { focusMessageId == null || it.focusMessageId == focusMessageId }
    @Synchronized fun clear() { replies.clear() }
    companion object { val process = ReplyHistory() }
}
