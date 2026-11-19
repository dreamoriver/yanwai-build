package dev.jev.wechatmood.hook

import android.content.Context

/**
 * 「＋」面板里那个入口的状态后缀。
 *
 * 有今天到期或已经过期的 → 提醒；只有还没到日子的待办 → 待办；都没有就不加后缀。
 * 只在本机按 chat 记两个数字，面板每次刷新读一次，不做任何查询。
 */
internal object RelationBadge {
    private const val PREFS = "yanwai_relation_badge"

    fun update(context: Context, talker: String?, due: Int, open: Int) {
        if (talker.isNullOrBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(talker + ":due", due)
                .putInt(talker + ":open", open)
                .apply()
        }
    }

    fun label(context: Context, talker: String?): String? {
        if (talker.isNullOrBlank()) return null
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            when {
                prefs.getInt(talker + ":due", 0) > 0 -> "提醒"
                prefs.getInt(talker + ":open", 0) > 0 -> "待办"
                else -> null
            }
        }.getOrNull()
    }
}
