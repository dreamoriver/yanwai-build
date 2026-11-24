package dev.jev.wechatmood.hook

/** Main-thread presentation state, independent of Android views and the analysis cache. */
internal class AnalysisPopupState(private val retention: Int = 256) {
    init { require(retention > 0) }
    private val seen = linkedSetOf<String>()
    var activeKey: String? = null
        private set

    fun offer(key: String): Boolean {
        if (activeKey == key) return true
        if (key in seen) return false
        // 别的卡片正占着屏幕时只是排队，不能记 seen：记了就只有菜单里的 request() 能再叫醒它，
        // 自动出来的结果会被永久吞掉。真正展示（或明确丢弃）时才记。
        if (activeKey != null) return false
        remember(key)
        activeKey = key
        return true
    }

    /** Explicit message-menu requests may reopen a dismissed result or replace the current one. */
    fun request(key: String) {
        activeKey?.let(::remember)
        seen.remove(key)
        activeKey = key
    }

    fun dismiss() {
        activeKey?.let(::remember)
        activeKey = null
    }

    /** 占位失败时归还：不记 seen，同一个结果下一次（别的气泡、下一次刷新）还能再试。 */
    fun release(key: String) {
        if (activeKey == key) activeKey = null
        // offer 放行时已经写过 seen，归还时必须一起撤掉；否则 attach 失败后这条结果永远不再弹。
        seen.remove(key)
    }

    private fun remember(key: String) {
        seen.add(key)
        while (seen.size > retention) seen.remove(seen.first())
    }
}
