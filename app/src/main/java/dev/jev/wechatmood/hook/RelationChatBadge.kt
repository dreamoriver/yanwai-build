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
    /** 找不到标题的退避：2 秒起，每失败一次翻倍，最长 10 秒，别每 2 秒扫一遍页面。 */
    private const val RETRY_MIN_MS = 2_000L
    private const val RETRY_MAX_MS = 10_000L
    /** 找聊天页容器只看这么多节点：广度优先、浅层先到，够用即停。 */
    private const val CONTAINER_SCAN_LIMIT = 800
    private const val CHAT_LAYOUT = ".ChattingUILayout"
    /** 消息列表这类大子树不必往里钻：标题栏是它们的兄弟。 */
    private val HEAVY_SUBTREES = listOf("ListView", "RecyclerView")
    /** 底部标签的文字永远不是聊天标题。 */
    private val BOTTOM_TABS = setOf("微信", "通讯录", "发现", "我")
    /** 档案库只在整理或改状态时变，8 秒读一次足够。 */
    private const val REFRESH_INTERVAL_MS = 8_000L

    private var dot: View? = null
    private var host: ViewGroup? = null
    /** 挂圆点时临时压缩标题，摘除时把原始 maxWidth 和省略方式都还回去。 */
    private var titleView: TextView? = null
    private var originalMaxWidth = 0
    private var originalEllipsize: android.text.TextUtils.TruncateAt? = null
    /** 只有真的改过标题才需要还原：maxWidth 原本可能正好是 0（不限宽），不能用它当「改过」的判断。 */
    private var titleCompressed = false
    private var talker: String? = null
    private var lastLookup = 0L
    private var lastRefresh = 0L
    private var retryAfter = 0L
    /** 当前退避间隔：成功挂上后回到 2 秒，找不到就翻倍到 10 秒。 */
    private var retryDelay = RETRY_MIN_MS
    private var lastHiddenTalker: String? = null
    private var lastLoggedBadge: String? = null
    private var lastScanInfo = ""
    private var debugScan = true
    private var checking = false
    private var due = 0
    private var future = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 由 HostUi 每次聊天页刷新时调用；内部自己节流，不在每次滚动时遍历整棵树。 */
    fun update(activity: Activity, currentTalker: String?, force: Boolean = false) {
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
            retryAfter = 0
            retryDelay = RETRY_MIN_MS
            lastHiddenTalker = null
            lastLoggedBadge = null
            debugScan = true
        }
        val now = SystemClock.uptimeMillis()
        // 档案刚改完状态：跳过 8 秒节流，马上重读一次，别让圆点停在旧数字上。
        if (force) lastRefresh = 0
        if (dot?.isAttachedToWindow != true || host?.isAttachedToWindow != true) {
            detach()
            if (now < retryAfter) return
            if (now - lastLookup < LOOKUP_INTERVAL_MS) return
            lastLookup = now
            val title = findTitle(activity)
            if (title == null) {
                // 找不到就退避（2s 起翻倍到 10s），并且同一个会话只记一次日志：这里跑在微信主线程上。
                retryAfter = now + retryDelay
                retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MS)
                if (lastHiddenTalker != currentTalker) {
                    lastHiddenTalker = currentTalker
                    MoodLog.i("RELATION_BADGE_HIDDEN " + lastScanInfo)
                }
                return
            }
            retryDelay = RETRY_MIN_MS
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
        val parent = title.parent as? ViewGroup ?: run {
            retryAfter = SystemClock.uptimeMillis() + 3_000
            MoodLog.i("RELATION_BADGE_SKIP parent=null")
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
        // 标题容器可能是 LinearLayout，也可能是 RelativeLayout（通知入口常见），两种都要能挂。
        val params: ViewGroup.LayoutParams = when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(title, 6)
            }
            is android.widget.RelativeLayout -> {
                if (title.id == View.NO_ID) title.id = View.generateViewId()
                android.widget.RelativeLayout.LayoutParams(size, size).apply {
                    addRule(android.widget.RelativeLayout.RIGHT_OF, title.id)
                    addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                    marginStart = dp(title, 6)
                }
            }
            else -> {
                retryAfter = SystemClock.uptimeMillis() + 3_000
                MoodLog.i("RELATION_BADGE_SKIP parent=" + parent.javaClass.simpleName)
                return
            }
        }
        // 标题常常占满整个标题容器（长群名尤其明显）：不给圆点让位，它就被挤出可见区域。
        if (parent.width > 0) {
            titleView = title
            originalMaxWidth = title.maxWidth
            originalEllipsize = title.ellipsize
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            title.maxWidth = (parent.width - size - dp(title, 6)).coerceAtLeast(dp(title, 60))
            titleCompressed = true
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
        // 用「改过」的标志判断，而不是 maxWidth 是否为 0：0 本身是合法值（不限宽）。
        if (titleCompressed) {
            titleView?.let { view ->
                view.maxWidth = originalMaxWidth
                // ellipsize 也要还原，否则宿主标题栏会一直停在 END 省略。
                view.ellipsize = originalEllipsize
            }
        }
        titleView = null
        originalMaxWidth = 0
        originalEllipsize = null
        titleCompressed = false
    }

    /**
     * 聊天页的标题栏通常在 ChattingUILayout 里；通知直接拉起 ChattingUI 时没有这个容器，
     * 所以先按类名找容器，容器里找不到才退回整页顶部找，仍然排除消息列表和输入框。
     *
     * 以前每次都在 decorView 上遍历最多 8000 个节点、还对每个 TextView 调 getLocationOnScreen，
     * 找不到时每 2 秒重来一遍，全跑在微信主线程上。现在容器找得到就只在容器里找，
     * 位置也只在过了前几道不看位置的筛选之后算一次。
     */
    private fun findTitle(activity: Activity): TextView? {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val metrics = activity.resources.displayMetrics
        // 微信标题默认 13sp；跟着系统字体缩放走，放大字体时才不会把标题漏掉。
        val titleSize = 13 * metrics.scaledDensity
        val chat = chatContainer(root)
        if (chat != null) {
            val found = pickTitle(chat, null, titleSize)
            if (found.title != null) {
                lastScanInfo = "scope=chat" + found.describe()
                return found.title
            }
        }
        // 容器没了，或者标题不在容器里（通知直接拉起时是 RelativeLayout）：按整页找，只认顶部 25%。
        val limitY = (metrics.heightPixels * 0.25f).toInt()
        val found = pickTitle(root, limitY, titleSize)
        lastScanInfo = "scope=" + (if (chat != null) "chat-miss" else "root") + found.describe()
        return found.title
    }

    /** 一次扫描的结果：标题 + 扫了多少节点，用来写诊断日志。 */
    private class TitlePick(val title: TextView?, val nodes: Int, val texts: Int,
        val candidates: Int, val limitY: Int?) {
        fun describe(): String = " nodes=" + nodes + " texts=" + texts + " candidates=" + candidates +
            (limitY?.let { " limitY=" + it } ?: "")
    }

    /**
     * 在 [scope] 里挑标题：最靠上的那个符合条件的 TextView。
     *
     * 先做不看位置的筛选，只有剩下的才调 getLocationOnScreen——旧写法在过滤链和 minBy 里
     * 对同一批节点反复算位置，等于每个 TextView 都多算两三次。
     */
    private fun pickTitle(scope: View, limitY: Int?, titleSize: Float): TitlePick {
        val all = descendants(scope)
        val texts = all.filterIsInstance<TextView>()
        if (debugScan) {
            debugScan = false
            // 不记正文，只记长度/字号/宽度/可见性和位置，用来确认标题到底长什么样。
            MoodLog.i("RELATION_BADGE_TEXTS n=" + texts.size + " " + texts.take(15).joinToString(";") {
                "len=" + it.text.length + ",size=" + it.textSize.toInt() + ",w=" + it.width +
                    ",vis=" + it.visibility + ",y=" + screenY(it)
            })
        }
        var best: TextView? = null
        var bestY = Int.MAX_VALUE
        var candidates = 0
        for (view in texts) {
            if (view is android.widget.EditText) continue
            if (view.visibility != View.VISIBLE || view.text.isBlank()) continue
            if (view.textSize < titleSize) continue
            if (view.text.toString().trim() in BOTTOM_TABS) continue
            // 消息列表里的文字都是正文，不可能是标题，连位置都不用算。
            if (insideList(view)) continue
            val y = screenY(view)
            if (limitY != null && y >= limitY) continue
            candidates++
            if (y < bestY) {
                bestY = y
                best = view
            }
        }
        return TitlePick(best, all.size, texts.size, candidates, limitY)
    }

    /** 聊天页容器：广度优先找，浅层先到；消息列表这类大子树不往里钻。 */
    private fun chatContainer(root: ViewGroup): ViewGroup? {
        val queue = ArrayDeque<View>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < CONTAINER_SCAN_LIMIT) {
            val view = queue.removeFirst()
            visited++
            if (view !== root && view is ViewGroup && view.javaClass.name.endsWith(CHAT_LAYOUT)) return view
            if (view !is ViewGroup) continue
            // 标题栏是消息列表的兄弟，不会在列表里层，跳过这些子树不影响定位。
            if (view !== root && HEAVY_SUBTREES.any { view.javaClass.name.contains(it) }) continue
            for (index in 0 until view.childCount) queue.addLast(view.getChildAt(index))
        }
        return null
    }

    private fun insideList(view: View): Boolean =
        generateSequence(view.parent as? View) { it.parent as? View }.any { it is android.widget.ListView }

    private fun screenY(view: View): Int {
        val point = IntArray(2)
        view.getLocationOnScreen(point)
        return point[1]
    }

    private fun descendants(root: View): List<View> {
        val out = ArrayList<View>(256)
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        // 微信主界面的 decorView 节点很多，4000 会在标题之前截断；只作为防爆上限。
        while (stack.isNotEmpty() && out.size < 8000) {
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
