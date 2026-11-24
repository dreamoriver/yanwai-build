package dev.jev.wechatmood.reply

/** Dispatch must be FIFO and serial. Writes outlive drawers; callbacks never change identity state. */
class ReplyIdentityQueue(private val dispatch: (() -> Unit) -> Unit,
    private val read: (ReplyContactKey) -> ReplyIdentitySetting,
    private val write: (ReplyContactKey, ReplyIdentitySetting) -> Unit) {
    fun load(key: ReplyContactKey, complete: (Result<ReplyIdentitySetting>) -> Unit) = dispatch {
        complete(runCatching { read(key) })
    }
    fun save(owner: ReplyIdentityOwner, value: ReplyIdentitySetting, verify: () -> String?, complete: (Boolean) -> Unit) {
        // 没有可写的键也要按其它分支的线程语义回调：统一走 dispatch，不在这里同步回调。
        val key = owner.key ?: run { dispatch { complete(false) }; return }
        dispatch {
            val saved = runCatching {
                check(owner.acceptsAccount(verify()))
                write(key, value)
            }.isSuccess
            complete(saved)
        }
    }
}
