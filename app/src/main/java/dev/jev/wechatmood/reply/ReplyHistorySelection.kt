package dev.jev.wechatmood.reply

/** Prepared local evidence, independent of the last successful model result. */
class ReplyHistorySelection(private val talker: String, initial: ReplyContext? = null) {
    data class Ticket(val serial: Long, val limit: Int)
    private var serial = 0L
    private var active: Ticket? = null
    var context: ReplyContext? = initial?.takeIf { it.talker == talker }
        private set
    val loading: Boolean get() = active != null

    fun begin(limit: Int): Ticket {
        require(ReplyHistoryLimit.valid(limit))
        context = null
        return Ticket(++serial, limit).also { active = it }
    }

    fun complete(ticket: Ticket, loaded: ReplyContext, currentTalker: String?): Boolean {
        // 被新请求顶掉的旧票直接忽略，别去动当前这张票的 loading 状态。
        if (active != ticket) return false
        // 结果一到就先销号：内容不合格时也要退出加载，否则抽屉会一直转圈。
        active = null
        if (currentTalker != talker || loaded.talker != talker ||
            loaded.requestedMessages != ticket.limit || loaded.messages.isEmpty()) return false
        context = loaded
        return true
    }

    fun fail(ticket: Ticket) { if (active == ticket) active = null }
    fun isCurrent(ticket: Ticket): Boolean = ticket.serial == serial
    fun cancel() { serial++; active = null; context = null }

    /** 预设档位要含默认值 20，否则默认值会显示成「自定义」。 */
    companion object { val OPTIONS = listOf(10, 20, 30, 50, 100) }
}
