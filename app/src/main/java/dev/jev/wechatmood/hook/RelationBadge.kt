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

    fun update(context: Context, talker: String?, due: Int, open: Int, timed: Int = open) {
        if (talker.isNullOrBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(talker + ":due", due)
                .putInt(talker + ":open", open)
                .putInt(talker + ":timed", timed)
                .apply()
            // 数字变了入口文案就要跟着变：否则「附加（提醒）」会一直停在旧状态。
            ReplyPlusRow.notifyBadgeChanged()
        }
    }

    data class Counts(val due: Int, val open: Int, val timed: Int)

    /** 「附加」面板里的关系档案/待办行用：今天要处理几条、档案几条、待办列表几条。 */
    fun counts(context: Context, talker: String?): Counts {
        if (talker.isNullOrBlank()) return Counts(0, 0, 0)
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val open = prefs.getInt(talker + ":open", 0)
            Counts(prefs.getInt(talker + ":due", 0), open,
                prefs.getInt(talker + ":timed", open))
        }.getOrDefault(Counts(0, 0, 0))
    }

    /** 全局扫描只知道今天到期的条数，不要用它覆盖「总共还有几条」。 */
    fun updateDue(context: Context, talker: String?, due: Int) {
        if (talker.isNullOrBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(talker + ":due", due)
                .apply()
            ReplyPlusRow.notifyBadgeChanged()
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
