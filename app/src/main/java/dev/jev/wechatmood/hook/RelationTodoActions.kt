package dev.jev.wechatmood.hook

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.ReplyContactKey

/**
 * 通知上的三个动作：进聊天、标记完成、稍后再提醒。
 *
 * 不放在清单里：模块代码跑在微信进程，动态注册才收得到；广播由自己创建的
 * PendingIntent 发出，用 setPackage 限定到微信。别的应用要伪造也只能改「已完成」
 * 这种可逆状态，而且会留日志。
 */
internal object RelationTodoActions {
    const val ACTION_DONE = "dev.jev.wechatmood.action.RELATION_TODO_DONE"
    const val ACTION_OPEN = "dev.jev.wechatmood.action.RELATION_TODO_OPEN"
    const val ACTION_SNOOZE = "dev.jev.wechatmood.action.RELATION_TODO_SNOOZE"
    const val EXTRA_KEY = "contact_key"
    const val EXTRA_IDS = "fact_ids"
    const val EXTRA_TALKER = "talker"
    const val EXTRA_NOTICE = "notice_id"
    const val EXTRA_MINUTES = "minutes"

    /** 延后到某个绝对时刻：通知上的「明早」按天算，换算成分钟容易差一小时（跨零点）。 */
    const val EXTRA_UNTIL = "until_at"

    private val FACT_ID = Regex("[0-9a-f]{16}")
    private val CONTACT_KEY = Regex("[0-9a-f]{64}")
    private val TALKER = Regex("[A-Za-z0-9_@.\\-]{1,256}")
    /** 注册过的接收器与状态：重复注册等于同一条广播处理多次，见 [register]。 */
    private var handle: BroadcastReceiver? = null
    private var registered = false

    fun register(context: Context) {
        // attach 会随宿主界面重建反复调用：已经注册过就直接返回，
        // 否则重复注册会让同一条广播被处理多次（比如一次点「完成」记两遍日志、取消两次通知）。
        if (registered) return
        val receiver = handle ?: object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_DONE -> markDone(c, intent)
                    ACTION_OPEN -> openChat(c, intent)
                    ACTION_SNOOZE -> snooze(c, intent)
                }
            }
        }.also { handle = it }
        val outcome = runCatching {
            val filter = IntentFilter().apply {
                addAction(ACTION_DONE)
                addAction(ACTION_OPEN)
                addAction(ACTION_SNOOZE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
        // 注册失败要把标志复位，下次还能再试；接收器实例留着复用，不复用也是白建一个。
        registered = outcome.isSuccess
        outcome.onFailure { MoodLog.w("RELATION_TODO_RECEIVER_FAIL " + it.javaClass.simpleName) }
    }

    private fun markDone(c: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.matches(CONTACT_KEY) } ?: return
        val ids = intent.getStringExtra(EXTRA_IDS).orEmpty().split(",")
            .map { it.trim() }.filter { it.matches(FACT_ID) }.distinct()
        if (ids.isEmpty()) return
        // 只改「已完成」这个可逆状态；标记错了可以在「附加 · 待办」里恢复。
        val changed = RelationFactBridge.markDone(c, key, ids)
        cancelNotice(c, intent)
        if (changed > 0) {
            // 通知上的动作也要把角标和剩余通知一起刷新：
            // 以前只改库，标记完成后角标还停在「提醒」，别的到期条目也不再重发。
            val contact = runCatching { ReplyContactKey(key) }.getOrNull()
            if (contact != null) {
                RelationReminder.refreshAsync(c, contact, intent.getStringExtra(EXTRA_TALKER))
            }
        }
        MoodLog.i("RELATION_TODO_DONE_BY_NOTICE asked=" + ids.size + " changed=" + changed)
    }

    /** 稍后提醒：先撤通知，到点后由下一次检查重新弹；不依赖微信进程一直活着。 */
    private fun snooze(c: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.matches(CONTACT_KEY) } ?: return
        val notice = intent.getIntExtra(EXTRA_NOTICE, 0)
        val until = intent.getLongExtra(EXTRA_UNTIL, 0L)
        if (until > System.currentTimeMillis()) {
            // 绝对时刻路径：明早这类跨天档位按天算，换算成分钟容易差一小时。
            RelationReminder.snoozeUntil(c, key, until, notice)
            MoodLog.i("RELATION_TODO_SNOOZE_UNTIL at=" + until)
            return
        }
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 60)
        RelationReminder.snooze(c, key, minutes, notice)
        MoodLog.i("RELATION_TODO_SNOOZE minutes=" + minutes)
    }

    /**
     * 打开对应会话。
     *
     * 这版微信的 LauncherUI 不认带 Chat_User 的 extra，点了只会回到首页；
     * ChattingUI 才是真正的聊天页入口（WeKit 的 openContact 也是这么做的）。
     * 放在这里是因为接收器跑在微信进程，startActivity 用的是微信自己的身份。
     */
    private fun openChat(c: Context, intent: Intent) {
        val talker = intent.getStringExtra(EXTRA_TALKER)?.takeIf { it.matches(TALKER) }
        cancelNotice(c, intent)
        if (talker == null) {
            MoodLog.w("RELATION_OPEN no_talker")
            start(c, Intent().setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
            return
        }
        openTalker(c, talker)
    }

    /**
     * 在微信进程里打开某个会话。
     *
     * 待回清单也走这里：那时候没有 PendingIntent，但同样需要「ChattingUI + Chat_User」这条路，
     * 两处各写一份迟早会有一边漏掉回退分支。
     */
    internal fun openTalker(context: Context, talker: String): Boolean {
        val opened = start(context, Intent().apply {
            setClassName("com.tencent.mm", "com.tencent.mm.ui.chatting.ChattingUI")
            putExtra("Chat_User", talker)
        })
        if (opened) return true
        return start(context, Intent().apply {
            setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
            putExtra("Chat_User", talker)
            putExtra("Chat_Mode", 1)
        })
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        MoodLog.i("RELATION_OPEN target=" + intent.component?.className?.substringAfterLast('.'))
        true
    }.onFailure {
        MoodLog.w("RELATION_OPEN_FAILED " + it.javaClass.simpleName + " " + (intent.component?.className ?: ""))
    }.getOrDefault(false)

    private fun cancelNotice(context: Context, intent: Intent) {
        val notice = intent.getIntExtra(EXTRA_NOTICE, 0)
        if (notice <= 0) return
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(notice)
        }
    }
}
