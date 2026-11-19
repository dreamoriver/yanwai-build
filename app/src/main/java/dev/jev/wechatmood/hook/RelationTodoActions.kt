package dev.jev.wechatmood.hook

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge

/**
 * 通知按钮的动作接收器。
 *
 * 不放在清单里：模块代码跑在微信进程，动态注册才收得到；广播由自己创建的
 * PendingIntent 发出，用 setPackage 限定到微信，别的应用要伪造也只能改「已完成」这种
 * 可逆状态，而且会留日志。
 */
internal object RelationTodoActions {
    const val ACTION_DONE = "dev.jev.wechatmood.action.RELATION_TODO_DONE"
    const val EXTRA_KEY = "contact_key"
    const val EXTRA_IDS = "fact_ids"
    const val EXTRA_NOTICE = "notice_id"

    private val FACT_ID = Regex("[0-9a-f]{16}")

    fun register(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_DONE) return
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val ids = intent.getStringExtra(EXTRA_IDS).orEmpty().split(",")
                    .map { it.trim() }.filter { it.matches(FACT_ID) }.distinct()
                if (ids.isEmpty()) return
                // 只改「已完成」这个可逆状态；标记错了可以在「附加 · 待办」里恢复。
                val changed = RelationFactBridge.markDone(c, key, ids)
                val notice = intent.getIntExtra(EXTRA_NOTICE, 0)
                if (notice > 0) runCatching {
                    (c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(notice)
                }
                MoodLog.i("RELATION_TODO_DONE_BY_NOTICE asked=" + ids.size + " changed=" + changed)
            }
        }
        runCatching {
            val filter = IntentFilter(ACTION_DONE)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { MoodLog.w("RELATION_TODO_RECEIVER_FAIL " + it.javaClass.simpleName) }
    }
}
