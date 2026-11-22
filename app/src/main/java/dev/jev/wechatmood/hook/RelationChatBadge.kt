package dev.jev.wechatmood.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天标题右侧的提醒圆点。
 *
 * 数据只来自本机档案库，不请求模型：进聊天先读一次，今天到期/已逾期用实心，
 * 只有未来待办用空心。找不到标题栏就不显示，绝不改微信布局层级之外的任何东西。
 */
internal object RelationChatBadge {
    /** 标题栏会被微信重建：没挂上时 400ms 再找一次，挂上后只在重建时重找。 */
    private const val LOOKUP_INTERVAL_MS = 400L
    /** 档案库只在整理或改状态时变，8 秒读一次足够。 */
    private const val REFRESH_INTERVAL_MS = 8_000L

    private var dot: View? = null
    private var host: ViewGroup? = null
    private var talker: String? = null
    private var lastLookup = 0L
    private var lastRefresh = 0L
    private var retryAfter = 0L
    private var lastHiddenTalker: String? = null
    private var lastLoggedBadge: String? = null
    private var checking = false
    private var due = 0
    private var future = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 由 HostUi 每次聊天页刷新时调用；内部自己节流，不在每次滚动时遍历整棵树。 */
    fun update(activity: Activity, currentTalker: String?) {
        if (currentTalker == null) {
            detach()
            talker = null
            lastRefresh = 0
            lastHiddenTalker = null
            lastLoggedBadge = null
            return
        }
        if (currentTalker != talker) {
            detach()
            talker = currentTalker
            due = 0
            future = 0
            lastRefresh = 0
            lastHiddenTalker = null
            lastLoggedBadge = null
        }
        val now = SystemClock.uptimeMillis()
        if (dot?.isAttachedToWindow != true || host?.isAttachedToWindow != true) {
            detach()
            if (now < retryAfter) return
            if (now - lastLookup < LOOKUP_INTERVAL_MS) return
            lastLookup = now
            val title = findTitle(activity)
            if (title == null) {
                // 找不到就退避 2 秒，并且同一个会话只记一次日志：这里跑在微信主线程上。
                retryAfter = now + 2_000
                if (lastHiddenTalker != currentTalker) {
                    lastHiddenTalker = currentTalker
                    MoodLog.i("RELATION_BADGE_HIDDEN")
                }
                return
            }
            attach(activity, title)
        }
        if (!checking && now - lastRefresh > REFRESH_INTERVAL_MS) {
            lastRefresh = now
            refresh(activity, currentTalker)
        }
    }

    private fun refresh(activity: Activity, currentTalker: String) {
        checking = true
        scope.launch {
            val badge = withContext(Dispatchers.IO) {
                runCatching { RelationFactBridge.badge(activity, currentTalker) }.getOrNull()
            }
            checking = false
            if (talker != currentTalker) return@launch
            if (badge == null) {
                if (lastLoggedBadge != "none") {
                    lastLoggedBadge = "none"
                    MoodLog.i("RELATION_BADGE_NO_DATA")
                }
                dot?.visibility = View.GONE
                return@launch
            }
            due = badge.due
            future = badge.future
            paint()
            // 圆点 8 秒刷新一次，只在数字变化时写字，避免主线程上反复落盘。
            val mark = due.toString() + "/" + future
            if (lastLoggedBadge != mark) {
                lastLoggedBadge = mark
                MoodLog.i("RELATION_BADGE due=$due future=$future")
            }
        }
    }

    private fun attach(activity: Activity, title: TextView) {
        val parent = title.parent as? LinearLayout ?: run {
            // 标题容器不是 LinearLayout 时不要每 400ms 重试，等页面下一次重建。
            retryAfter = SystemClock.uptimeMillis() + 3_000
            MoodLog.i("RELATION_BADGE_SKIP parent=" + (title.parent?.javaClass?.simpleName ?: "null"))
            return
        }
        detach()
        val size = dp(title, 8)
        val view = View(title.context).apply {
            contentDescription = "关系档案提醒，点击查看待办"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val current = talker ?: return@setOnClickListener
                runCatching { RelationTodoUi(activity, current).show() }
                    .onFailure { MoodLog.w("RELATION_BADGE_OPEN_FAILED " + it.javaClass.simpleName) }
            }
        }
        val params = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(title, 6)
        }
        val index = parent.indexOfChild(title)
        parent.addView(view, (index + 1).coerceAtMost(parent.childCount), params)
        dot = view
        host = parent
        paint()
    }

    /** 实心＝今天到期/已逾期（含前一天 20:00 后的提前提醒），空心＝只有更远的待办。 */
    private fun paint() {
        val view = dot ?: return
        val accent = ReplyTheme(view.context).accent
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (due > 0) accent else Color.TRANSPARENT)
            setStroke(dp(view, 1).coerceAtLeast(1), accent)
        }
        view.visibility = if (due > 0 || future > 0) View.VISIBLE else View.GONE
    }

    private fun detach() {
        dot?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        dot = null
        host = null
    }

    /** 聊天页的标题栏在 ChattingUILayout 里；找不到这个容器时不做兜底猜测。 */
    private fun findTitle(activity: Activity): TextView? {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val chat = descendants(root).filterIsInstance<ViewGroup>()
            .firstOrNull { it.javaClass.name.endsWith(".ChattingUILayout") } ?: return null
        val metrics = activity.resources.displayMetrics
        // 标题栏在 ChatFooter 上方；排除 ListView（消息气泡）和 EditText（输入框）。
        val footerY = descendants(chat)
            .firstOrNull { it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
            ?.let { screenY(it) } ?: Int.MAX_VALUE
        val limitY = footerY - (40 * metrics.density).toInt()
        val titleSize = 15 * metrics.scaledDensity
        return descendants(chat)
            .filterIsInstance<TextView>()
            .filter { it !is android.widget.EditText }
            .filter { it.isShown && it.text.isNotBlank() && it.textSize >= titleSize }
            .filter { tv ->
                generateSequence(tv.parent as? View) { it.parent as? View }.none { it is android.widget.ListView }
            }
            .filter { screenY(it) < limitY }
            .minByOrNull { screenY(it) }
    }

    private fun screenY(view: View): Int {
        val point = IntArray(2)
        view.getLocationOnScreen(point)
        return point[1]
    }

    private fun descendants(root: View): List<View> {
        val out = ArrayList<View>(256)
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < 4000) {
            val view = stack.removeLast()
            out += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) stack.addLast(view.getChildAt(index))
            }
        }
        return out
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
