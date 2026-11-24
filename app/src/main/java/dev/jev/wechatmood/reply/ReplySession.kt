package dev.jev.wechatmood.reply

class ReplySession {
    data class Ticket(val serial: Long, val talker: String, val fingerprint: String)
    // 每次开始回填在主线程，判定在 HTTP 回调线程：不加 @Volatile 时读线程可能看不到新的 serial/ticket，
    // 把已经作废的一次回复当成当前的。
    @Volatile private var serial = 0L
    @Volatile private var current: Ticket? = null
    @Synchronized fun begin(talker: String, fingerprint: String) = Ticket(++serial, talker, fingerprint).also { current = it }
    fun accepts(ticket: Ticket, talker: String?) = ticket == current && ticket.talker == talker
    @Synchronized fun cancel() { serial++; current = null }
}
