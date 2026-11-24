package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.jev.wechatmood.core.MoodLog

/** 状态文字的语义色，避免把失败混进正文里。 */
internal enum class PanelTone { NEUTRAL, SUCCESS, WARNING, ERROR }

/**
 * 微信内面板的共用零件：标题、说明、卡片、条目、胶囊按钮、底部选择器。
 *
 * 现有界面（附加面板、帮我回抽屉、分析卡）的数值本来就是一套，
 * 但每个新窗口都在各自手写，圆角和触控高度很快就分叉了。
 * 统一从这里取，新加的窗口自然和旧界面一致。
 */
internal class PanelKit(private val context: Context, val theme: ReplyTheme = ReplyTheme(context)) {

    /** 承载窗口的 Activity：宿主可能给的是包装过的 Context，逐层解 ContextWrapper 才找得到。 */
    private val activity: Activity? = findActivity(context)
    /** 本 kit 打开着的弹窗，界面不可用时统一收掉，避免留下没人关的窗口。 */
    private val dialogs = mutableSetOf<Dialog>()
    /** 宿主销毁的回调只挂一次：一个 kit 通常只对应一个 Activity。 */
    private var hooked = false

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    /**
     * 只有宿主 Activity 还活着才能 show：界面正在结束或已销毁时弹窗会抛 BadTokenException，
     * 或者留下一个没人关的窗口。不可用时安全返回 false，不把异常抛给调用方。
     */
    private fun open(dialog: Dialog): Boolean {
        val host = activity
        if (host == null || host.isFinishing || host.isDestroyed) {
            MoodLog.w("PANEL_SHOW_SKIPPED no_activity")
            runCatching { dialog.dismiss() }
            return false
        }
        hookHost()
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        return runCatching {
            dialog.show()
            true
        }.onFailure {
            dialogs -= dialog
            MoodLog.w("PANEL_SHOW_FAILED " + it.javaClass.simpleName)
        }.getOrDefault(false)
    }

    /** 界面销毁前统一收掉本 kit 打开的弹窗。 */
    fun dismissAll() {
        dialogs.toList().forEach { runCatching { it.dismiss() } }
        dialogs.clear()
    }

    /**
     * 把「Activity 销毁」接到 [dismissAll] 上，避免窗口还在、宿主已经走掉留下的 WindowLeaked。
     *
     * 选装饰视图的 attach 状态而不是 Application.ActivityLifecycleCallbacks：后者要在 Application
     * 上注册，每个面板都得配对注销，漏一次就是全局表里的一份泄漏；这里的监听器挂在随 Activity
     * 一起销毁的 decorView 上，不占全局注册表。已经在结束的宿主直接收掉现有弹窗。
     * 各界面自己的 dismiss 只关它自己那一个窗口，不需要为这个改动调整调用方。
     */
    private fun hookHost() {
        if (hooked) return
        val host = activity ?: return
        if (host.isFinishing || host.isDestroyed) {
            dismissAll()
            return
        }
        val decor = host.window?.decorView ?: return
        hooked = true
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                dismissAll()
            }
        })
    }

    /** 面板根：surface 底 + 面板圆角 + 细边框，和附加面板同款。 */
    fun root(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
    }

    /** 面板内边距，沿用 20dp 页边距。 */
    fun content(root: LinearLayout): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(theme.dp(20), theme.dp(16), theme.dp(20), theme.dp(16))
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun heading(text: String): TextView = theme.label(text, ReplyMetrics.TEXT_TITLE, theme.ink, bold = true)

    fun caption(text: String): TextView = theme.label(text, ReplyMetrics.TEXT_CAPTION, theme.muted)

    /** 分区小标题：沿用分析卡的浅青加粗标签，让新窗口也有同一个识别度。 */
    fun sectionLabel(text: String): TextView =
        theme.label(text, ReplyMetrics.TEXT_CAPTION, theme.accent, bold = true).apply {
            setPadding(0, theme.dp(14), 0, theme.dp(4))
        }

    fun body(text: String, size: Float = ReplyMetrics.TEXT_BODY): TextView =
        theme.label(text, size, theme.ink).apply { setLineSpacing(theme.dp(4).toFloat(), 1f) }

    fun divider(): View = View(context).apply { setBackgroundColor(theme.border) }

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
        background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
    }

    /** 条目行：主标题 + 说明 + 右箭头，整行可点，高度不低于 48dp。 */
    fun itemRow(title: String, subtitle: String?, onClick: () -> Unit): View {        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(theme.label(title, ReplyMetrics.TEXT_ITEM, theme.ink))
        if (!subtitle.isNullOrBlank()) {
            texts.addView(theme.label(subtitle, ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
                setPadding(0, theme.dp(2), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(theme.label("›", 20f, theme.muted).apply { setPadding(theme.dp(8), 0, 0, 0) })
        return row
    }

    /**
     * 分组列表里的一行：没有独立卡片底，主标题 + 说明，右侧一个浅色箭头。
     * 卡片背景和行间分隔线由调用方给，这里只负责一行的高度与内容。
     */
    fun listRow(title: String, subtitle: String?, arrow: Boolean = true,
        badgeView: TextView? = null, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(14), theme.dp(11), theme.dp(12), theme.dp(11))
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(theme.label(title, ReplyMetrics.TEXT_ITEM, theme.ink))
        if (!subtitle.isNullOrBlank()) {
            texts.addView(theme.label(subtitle, ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
                setPadding(0, theme.dp(2), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        // 数字标由调用方创建并持有，打开面板后异步刷新不用重建整行。
        if (badgeView != null) {
            row.addView(badgeView, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = theme.dp(4) })
        }
        if (arrow) {
            row.addView(theme.label("›", 20f, theme.muted).apply { setPadding(theme.dp(8), 0, 0, 0) })
        }
        return row
    }

    /** 胶囊筛选按钮：和主按钮同高度，不再压到 34dp。 */
    fun pill(label: String, onClick: () -> Unit): TextView =
        theme.action(label, quiet = true, onClick = onClick).apply {
            textSize = ReplyMetrics.TEXT_CAPTION
            minHeight = theme.dp(ReplyMetrics.TAP)
            setPadding(theme.dp(8), theme.dp(4), theme.dp(8), theme.dp(4))
        }

    /** 状态行：文字永远是唯一的解释来源，颜色只是辅助。 */
    fun status(text: String, tone: PanelTone = PanelTone.NEUTRAL): TextView {
        val color = when (tone) {
            PanelTone.NEUTRAL -> theme.muted
            PanelTone.SUCCESS -> theme.success
            PanelTone.WARNING -> theme.warning
            PanelTone.ERROR -> theme.error
        }
        return theme.label(text, ReplyMetrics.TEXT_CAPTION, color).apply {
            setPadding(0, theme.dp(4), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }

    /** 加载行：26dp 圆环 + 文案，和抽屉里的加载反馈同一套。 */
    fun loading(): ReplyLoadingView = ReplyLoadingView(context, theme)

    /** 底部面板外壳：透明底、贴底、0.32 遮罩。 */
    fun sheet(): Dialog = Dialog(context).apply {
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
    }

    /** 选项行：48dp、卡片底、选中用主色描边。 */
    fun optionRow(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val fill = if (selected) theme.soft else theme.card
        val stroke = if (selected) theme.accent else theme.border
        return theme.label(label, ReplyMetrics.TEXT_ITEM, if (selected) theme.accent else theme.ink).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            setPadding(theme.dp(14), theme.dp(10), theme.dp(14), theme.dp(10))
            isClickable = true; isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf((theme.accent and 0x00ffffff) or 0x22000000),
                theme.shape(fill, ReplyMetrics.RADIUS_CARD, stroke), theme.shape(theme.ink, ReplyMetrics.RADIUS_CARD))
            setOnClickListener { onClick() }
        }
    }

    /** 底部选择器，替代宿主 AlertDialog：圆角、触控高、深浅色都跟着面板走。 */
    fun picker(title: String, options: List<String>, selected: Int = -1, onPick: (Int) -> Unit) {
        val dialog = sheet()
        val root = root()
        val body = content(root)
        body.addView(heading(title))
        options.forEachIndexed { index, option ->
            body.addView(optionRow(option, index == selected) {
                dialog.dismiss()
                onPick(index)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        }
        body.addView(theme.action("取消", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // 界面已经结束时不再弹窗：show() 会抛 BadTokenException，这里安全返回。
        open(dialog)
    }

    /** 单行输入，替代宿主 AlertDialog 的 EditText：重命名群成员这类手填一次的操作。 */
    fun input(title: String, hint: String, value: String = "", onConfirm: (String) -> Unit) {
        val dialog = sheet()
        val root = root()
        val column = content(root)
        column.addView(heading(title))
        val field = android.widget.EditText(context).apply {
            setText(value)
            setHint(hint)
            textSize = ReplyMetrics.TEXT_ITEM
            setTextColor(theme.ink)
            setHintTextColor(theme.muted)
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
            maxLines = 1
            setSingleLine(true)
        }
        column.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        column.addView(theme.action("保存", primary = true) {
            val text = field.text.toString().trim()
            dialog.dismiss()
            onConfirm(text)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        column.addView(theme.action("取消", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // 界面已经结束时不再弹窗：show() 会抛 BadTokenException，这里安全返回。
        open(dialog)
    }

    /** 只读详情，替代 AlertDialog 的 setMessage。 */
    fun info(title: String, message: String) {
        val dialog = sheet()
        val root = root()
        val column = content(root)
        column.addView(heading(title))
        val scroll = PanelMaxScroll(context, (context.resources.displayMetrics.heightPixels * 0.45f).toInt()).apply {
            addView(body(message).apply { setTextIsSelectable(true) })
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // 界面已经结束时不再弹窗：show() 会抛 BadTokenException，这里安全返回。
        open(dialog)
    }
}

/** 内容少时只占实际高度，超过上限才滚动。 */
internal class PanelMaxScroll(context: Context, private val limit: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}
