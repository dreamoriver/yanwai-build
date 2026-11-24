package dev.jev.wechatmood.reply

/**
 * 待回清单的一条：对方最后说的、我还没接的会话。
 *
 * 只带会话标识和一句预览，不缓存整段聊天：清单是给人扫一眼的，
 * 点进去仍然是微信自己打开那个会话。
 */
data class PendingReply(
    val talker: String,
    /** 备注名 / 群名；解析不到时为空，界面按「未知会话」显示，不冒充。 */
    val name: String,
    /** 对方最后一条的时间，已统一成毫秒。 */
    val lastAt: Long,
    val preview: String,
    val isGroup: Boolean,
    val unread: Int,
)
