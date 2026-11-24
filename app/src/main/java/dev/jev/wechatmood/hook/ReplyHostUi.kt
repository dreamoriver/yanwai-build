package dev.jev.wechatmood.hook

import dev.jev.wechatmood.voice.VoicePreparation
import dev.jev.wechatmood.voice.VoiceState

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.ReplyIdentityBridge
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.core.ReplyLimitBridge
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*

/** Native widgets only: module resource IDs are not valid inside the host's resource table. */
class ReplyHostUi(private val activity: Activity,
    private val openPanel: () -> Unit = {}) {
    /**
     * 沙盘里一条「他可能这么接」的分支。
     *
     * [mine] 是模型建议的我的下一句，也是继续往下推时的起点；
     * [children] 是拿 [mine] 再问一次得到的下一层，最多推三层，避免越推越玄。
     */
    private class SimLine(
        val stance: String,
        val theirs: String,
        val basis: String,
        /** 我能直接发出去的一句原话；「用这句回复」只填这个字段。 */
        val mine: String,
        /** 给我的动作提示，只在界面小字显示，永不进聊天框。 */
        val move: String = "",
        var children: List<SimLine> = emptyList(),
        var loading: Boolean = false,
        /** 收起后只留一行「档位 + 他说的预览」，收起父节点连带收起它下面整棵子树。 */
        var collapsed: Boolean = false,
    )
    /** 状态行里的短句预览：按码点退一位，别把 emoji 劈成半个字符。 */
    private fun shortText(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = if (text[max - 1].isHighSurrogate() && text[max].isLowSurrogate()) max - 1 else max
        return text.substring(0, cut) + "…"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = ReplySession()
    /** 沙盘与生成各自持有会话号：预演作废不该连带取消正在生成的回复。 */
    private val simSession = ReplySession()
    private val history = ReplyHistory.process
    // 横条只留「附加」一个入口：分析开关、统计、档案、待办都在同一个面板里。
    private val plusEntry = ReplyPlusRow(activity) { openPanel(); true }
    private var job: Job? = null
    private var simJob: Job? = null
    private var historyJob: Job? = null
    private var openingJob: Job? = null
    private var ownerEpoch: String? = null
    private var drawerRolesRevision: Long? = null
    private var talker: String? = null
    private var dialog: Dialog? = null
    private var styleDialog: Dialog? = null
    private var snapshot: ReplyContext? = null
    /** 依据弹窗改成异步读缓存后再显示：交给 PanelKit 的公共面板，同一面板不会叠两个。 */
    private val kit = PanelKit(activity)
    private var newMessageNotice: TextView? = null
    private var invalidateRequest: (() -> Unit)? = null
    /** 长按图片/表情时记住目标消息与已取的图，重新读取历史后也要留住。 */
    private var targetFocusId: Long = 0
    private var targetImage: String? = null
    /** 从「＋」面板进来、没有长按目标：目标跟随对方最后一条消息，重新读取时要重算。 */
    private var autoTarget = false
    /** 当前会话的关系档案，生成回复时随上下文一起给模型。 */
    private var facts: List<RelationFact> = emptyList()
    /** 「更新关系档案」按钮：点完先改字，避免点了像没反应。 */
    private var factsActionRef: TextView? = null
    private var draftTargetMedia: String? = null
    private var draftText: String = ""
    private var draftLastIncoming: String? = null
    /** 打开面板各阶段耗时：临时诊断用，定位「读取最近 N 条」到底卡在哪一步。 */
    private var openStartedAt = 0L
    private fun timing(stage: String) {
        if (openStartedAt > 0L) MoodLog.i("REPLY_TIMING stage=$stage cost=${android.os.SystemClock.elapsedRealtime() - openStartedAt}ms")
    }

    fun update(currentTalker: String?) {
        // 口吻画像改过就重开抽屉：按钮文字和选项都按新配置画。以前这里还判一个从没赋过值的窗口字段，恒真，等于没判。
        if (dialog?.isShowing == true && drawerRolesRevision != ModulePrefs.analysisSettings()?.rolesRevision) hide()
        if (talker != currentTalker || ownerEpoch?.let { it != ReplyDatabaseHistory.pendingAccountScope() } == true) {
            hide(); talker = currentTalker
        }
        if (currentTalker == null) return
        plusEntry.update(footer())
        if (dialog?.isShowing == true) {
            if (!ModulePrefs.canGenerateReply &&
                (job?.isActive == true || historyJob?.isActive == true)) {
                job?.cancel(); historyJob?.cancel(); session.cancel(); invalidateRequest?.invoke()
            }
            updateNotice()
        }
    }
    /**
     * 口吻画像：只展示模型参考的说话习惯，不提供「关掉」开关。
     *
     * 以前这段摘要只发给模型，用户看不到，觉得回复不像自己时无从核对。
     * 这里把摘要和几条示例短语摊开，明确说明不会逐字模仿。
     */
    private fun showStyleProfile(profile: StyleProfile?) {
        val dialog = Dialog(activity)
        styleDialog = dialog
        dialog.setOnDismissListener { if (styleDialog === dialog) styleDialog = null }
        val theme = ReplyTheme(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(16), theme.dp(20), theme.dp(16))
            background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
        }
        box.addView(theme.label("口吻画像", ReplyMetrics.TEXT_TITLE, theme.ink, bold = true))
        if (profile == null) {
            box.addView(theme.label("聊天里「我」的文字还不够，至少 8 条才能看出习惯。",
                ReplyMetrics.TEXT_BODY, theme.muted).apply {
                setPadding(0, theme.dp(8), 0, theme.dp(8))
            })
        } else {
            box.addView(theme.label(profile.summary(), ReplyMetrics.TEXT_BODY, theme.ink).apply {
                setPadding(0, theme.dp(8), 0, theme.dp(6))
                setLineSpacing(theme.dp(3).toFloat(), 1f)
                setTextIsSelectable(true)
            })
            if (profile.samples.isNotEmpty()) {
                box.addView(theme.label("我最近这样说话", ReplyMetrics.TEXT_CAPTION, theme.accent, bold = true).apply {
                    setPadding(0, theme.dp(6), 0, theme.dp(2))
                })
                profile.samples.take(3).forEach { sample ->
                    box.addView(theme.label("· " + sample, ReplyMetrics.TEXT_BODY, theme.muted).apply {
                        setPadding(0, theme.dp(2), 0, 0)
                        // 极窄的聊天窗口里再折一次，末尾必须有省略号，不能半句消失。
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
            }
            box.addView(theme.label("回复会照这个习惯写，不会逐字模仿。",
                ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
                setPadding(0, theme.dp(8), 0, 0)
            })
        }
        box.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun updateNotice() {
        val captured = snapshot ?: return
        val latest = MessageSniffer.replyBoundary() ?: return
        newMessageNotice?.visibility = if (latest != captured.latestLoadedId && captured.messages.none { it.id == latest }) View.VISIBLE else View.GONE
    }
    private var footerRef: java.lang.ref.WeakReference<View>? = null
    private var editorRef: java.lang.ref.WeakReference<EditText>? = null

    /**
     * 这两个查找原来每 tick、每次按键都从 decorView 起做一次最多 4000 节点的 DFS。
     * 底部栏和输入框在同一个聊天页里是稳定的，缓存后用 attach 状态校验即可。
     */
    private fun footer(): View? {
        footerRef?.get()?.takeIf { it.isAttachedToWindow && it.isShown }?.let { return it }
        val found = views(activity.window.decorView).singleOrNull {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" && it.isShown
        }
        footerRef = found?.let { java.lang.ref.WeakReference(it) }
        return found
    }

    private fun editor(): EditText? {
        editorRef?.get()?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled }?.let { return it }
        val found = footer()?.let { root ->
            views(root).filterIsInstance<EditText>().filter { it.isShown && it.isEnabled }.singleOrNull()
        }
        editorRef = found?.let { java.lang.ref.WeakReference(it) }
        return found
    }

    fun open(focusMessageId: Long? = null, imageDataUri: String? = null, retry: Int = 0): Boolean {
        if (retry == 0) openStartedAt = android.os.SystemClock.elapsedRealtime()
        MoodLog.i("REPLY_OPEN_ENTER focus=${focusMessageId ?: 0} knownTalker=${talker != null} " +
            "dialog=${dialog?.isShowing == true} visible=${dialog?.window?.decorView?.isShown == true} " +
            "opening=${openingJob?.isActive == true}")
        targetFocusId = focusMessageId ?: 0
        targetImage = imageDataUri
        autoTarget = focusMessageId == null
        val selectedTalker = talker ?: return false
        // 附加面板关闭的瞬间 ChatFooter 可能读不到聊天：读到不同聊天才拒绝，读不到就继续，
        // 后面读到 live.talker 时还会再核对一次。否则第一次点「帮我回」会静默无反应。
        val current = MessageSniffer.currentReplyTalker()
        if (current != null && current != selectedTalker) {
            MoodLog.i("REPLY_OPEN_REJECT reason=talker_mismatch")
            return false
        }
        if (dialog?.isShowing == true || openingJob?.isActive == true) {
            MoodLog.i("REPLY_OPEN_SKIP showing=${dialog?.isShowing == true} opening=${openingJob?.isActive == true}")
            return true
        }
        val epoch = ReplyDatabaseHistory.pendingAccountScope()
        ownerEpoch = epoch
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false, focusId = targetFocusId) }.getOrElse { error ->
            val message = error.message.orEmpty()
            // 点「＋ → 附加」时聊天列表可能正在被面板盖住或重建，读一次是空的；
            // 隔一帧再试，比当场甩一句「无法核对」好。
            if (retry < 2 && (message.contains("核对当前聊天") || message.contains("识别当前聊天"))) {
                MoodLog.w("REPLY_OPEN_RETRY retry=" + retry + " reason=" + message)
                activity.window?.decorView?.postDelayed({
                    runCatching { open(focusMessageId, imageDataUri, retry + 1) }
                        .onFailure { toast(it.message ?: "读取聊天失败") }
                }, 140L)
                return true
            }
            toast(message.ifBlank { "读取聊天失败" })
            return true
        }
        timing("context1")
        // 从附加面板进来没有长按目标：默认回对方最后一条消息。
        // 以前要求「正文非空」，会把最后一条是图片/语音时跳过它，转而去回更早那句；
        // 现在按消息取，占位由 replyTargetText 说明类型。
        if (focusMessageId == null) {
            live.messages.lastOrNull { it.speaker != "我" && it.id > 0 }?.let { target ->
                targetFocusId = target.id
                MoodLog.i("REPLY_AUTO_TARGET ready=${target.id > 0} placeholder=${target.placeholder}")
            }
        }
        if (live.talker != selectedTalker) {
            MoodLog.i("REPLY_OPEN_REJECT reason=live_mismatch")
            toast("聊天已切换，请重新打开")
            return false
        }
        openingJob = scope.launch {
            try {
                // A cold host may not have finished connecting yet. Load before capturing ownership.
                if (!ModulePrefs.bridgeAvailable) withContext(Dispatchers.IO) { ModulePrefs.reload(force = true) }
                ensureActive()
                val generation = ModulePrefs.analysisSettings()?.generation
                val rolesRevision = ModulePrefs.analysisSettings()?.rolesRevision
                check(generation != null) { "设置尚未连接" }
                val account = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(live) }
                timing("account")
                val owner = ReplyIdentityOwner(epoch, account, selectedTalker)
                val identity = owner.key?.let { ReplyIdentityBridge.load(activity, it) } ?: ReplyIdentitySetting()
                timing("identity")
                val background = owner.key?.let { ReplyIdentityBridge.loadBackground(activity, it) } ?: ContactBackground()
                timing("background")
                val loadedFacts = owner.key?.let { key -> withContext(Dispatchers.IO) { RelationFactBridge.load(activity, key) } }.orEmpty()
                timing("facts")
                val preferredLimit = ReplyLimitBridge.load(activity, account)
                timing("limit")
                ensureActive()
                if (generation != ModulePrefs.analysisSettings()?.generation || rolesRevision != ModulePrefs.analysisSettings()?.rolesRevision) return@launch
                if (!owner.isCurrent(ReplyDatabaseHistory.pendingAccountScope(), MessageSniffer.currentReplyTalker())) return@launch
                val latestPage = MessageSniffer.replyContext(requireBottom = false, focusId = targetFocusId)
                timing("context2")
                val latestAccount = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(latestPage) }
                timing("account2")
                if (generation != ModulePrefs.analysisSettings()?.generation || rolesRevision != ModulePrefs.analysisSettings()?.rolesRevision ||
                    !owner.isCurrent(ReplyDatabaseHistory.pendingAccountScope(), MessageSniffer.currentReplyTalker()) ||
                    !owner.acceptsAccount(latestAccount)) return@launch
                // All reads finish before the editor is created, so a late load cannot erase typed text.
                openResolved(focusMessageId, live, owner, identity, preferredLimit, background, imageDataUri, loadedFacts)
                timing("dialog")
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) {
                MoodLog.e("REPLY_SETTINGS_READ_FAILED", error)
                if (epoch == ReplyDatabaseHistory.pendingAccountScope() && talker == selectedTalker) {
                    // 不再弹「去开自启动」的对话框；本地已有回退，失败只记日志。
                    if (error is dev.jev.wechatmood.core.SettingsConnectionUnavailableException || !ModulePrefs.bridgeAvailable)
                        dev.jev.wechatmood.core.MoodLog.w("REPLY_OPEN_WITHOUT_BRIDGE 未能连接言外，已按本地缓存继续")
                    else toast("回复设置读取失败，请重试；长按分析开关可导出日志")
                }
            }
        }
        return true
    }

    private fun openResolved(focusMessageId: Long?, live: ReplyContext, owner: ReplyIdentityOwner,
        identity: ReplyIdentitySetting, preferredLimit: Int, contactBackground: ContactBackground,
        imageDataUri: String? = null, loadedFacts: List<RelationFact> = emptyList()): Boolean {
        facts = loadedFacts
        val selectedTalker = owner.talker
        val generation = ModulePrefs.analysisSettings()?.generation
        var rolesRevision = ModulePrefs.analysisSettings()?.rolesRevision
        val remembered = history.recall(selectedTalker, focusMessageId, owner.historyScope)
        val group = selectedTalker.endsWith("@chatroom")
        val composition = ReplyComposition(remembered, if (group) ReplyIdentitySetting(
            remembered?.relationship ?: ReplyRelationship.UNSPECIFIED, remembered?.customRelationship.orEmpty()) else identity,
            preferredLimit, contactBackground)
        var selectedIdentity = identity
        val reference = ReplyHistorySelection(selectedTalker, remembered?.context?.takeIf { it.requestedMessages == preferredLimit })
        // Reopening saved content must not depend on scrolling to the latest message or text input mode.
        val captured = (remembered?.context ?: live).let { ctx ->
            val image = imageDataUri ?: targetImage
            if (image != null && ctx.targetImageDataUri == null) ctx.copy(targetImageDataUri = image) else ctx
        }
        if (captured.talker != selectedTalker) return false
        val initialEditor = editor()
        if (remembered == null && initialEditor == null) { toast("请先切换到文字输入"); return true }
        // 默认目标模式不沿用上次面板里的 focus：那可能是几小时前的一条，重新打开应该看最新一条。
        val focusId = if (autoTarget) targetFocusId else remembered?.focusMessageId ?: focusMessageId
        /** 「这一轮在回哪条」：有目标就写时间和摘要，没有就说自由发挥。 */
        fun replyTargetText(ctx: ReplyContext): String {
            val id = (if (autoTarget) 0L else ctx.focusMessageId).takeIf { it > 0 } ?: targetFocusId
            val target = ctx.messages.firstOrNull { it.id == id }
                ?: ctx.messages.lastOrNull { it.speaker != "我" && it.text.isNotBlank() }
            if (target == null) return "自由发挥：按最近对话接一句"
            val clock = if (target.time > 0) java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(target.time)) else ""
            // 图片、表情的正文只是占位：直接显示「【图片】」看不出在回什么。
            // 有缓存描述就写描述，没有就说明生成时会先看图。
            // 本轮刚生成的描述在 ctx 里；重开面板时 ctx 没有，去缓存里找同一张图的描述。
            val digest = ctx.targetImageDigest?.takeIf { it.isNotBlank() && ctx.focusMessageId == target.id }
                ?: if (target.placeholder) ImageDigest.cached(activity, ctx.talker, target.id) else null
            val text = when {
                target.placeholder -> when {
                    digest != null -> "「${digest.take(30)}」"
                    target.text.contains("表情") -> "一个表情（生成时会先看它）"
                    target.text.contains("图片") -> "一张图片（生成时会先看它）"
                    else -> target.text.trim('【', '】')
                }
                // 语音：转写过就显示内容，跳过和失败要分开说，别让用户以为没语音。
                target.voice != null -> when (target.voiceState) {
                    VoiceState.READY -> target.text.take(30)
                    VoiceState.SKIPPED -> "一条语音（本轮未转写）"
                    VoiceState.FAILED -> "一条语音（未能转写）"
                    else -> "一条语音"
                }
                // 合并转发：正文是摊平的长文本，这里只说清「回的是哪段记录」。
                target.text.startsWith("【合并转发") -> {
                    val title = Regex("【合并转发：([^】]*)】").find(target.text)?.groupValues?.get(1).orEmpty()
                    if (title.isBlank()) "一段聊天记录" else "一段聊天记录：${title.take(20)}"
                }
                else -> target.text.replace("\n", " ").take(30)
            }
            return if (target.speaker == "我") "接着我${if (clock.isBlank()) "" else " $clock"}的话：$text"
            else "回复${if (clock.isBlank()) "" else " $clock"}：$text"
        }
        snapshot = reference.context
        initialEditor?.let { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(it.windowToken, 0) }
        val theme = ReplyTheme(activity)
        fun dp(value: Int) = theme.dp(value)
        fun action(text: String, primary: Boolean = false, quiet: Boolean = false, click: () -> Unit = {}) =
            theme.action(text, primary, quiet) {
                runCatching(click).onFailure { MoodLog.w("REPLY_UI_FAILED ${it.javaClass.simpleName}"); toast("操作失败，请重试") }
            }
        fun line() = View(activity).apply { setBackgroundColor(theme.border) }
        val window = Dialog(activity)
        dialog = window
        drawerRolesRevision = rolesRevision
        fun ownsDrawer() = window === dialog && generation == ModulePrefs.analysisSettings()?.generation &&
            rolesRevision == ModulePrefs.analysisSettings()?.rolesRevision &&
            owner.isCurrent(ReplyDatabaseHistory.pendingAccountScope(), MessageSniffer.currentReplyTalker())
        suspend fun verifyAccount(): Boolean {
            if (!ownsDrawer()) return false
            val currentPage = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull() ?: return false
            val account = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(currentPage) }
            return ownsDrawer() && owner.acceptsAccount(account)
        }
        fun fitWindow() {
            window.window?.setLayout(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val body = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = activity.window.decorView.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
                val windowLimit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    available else View.MeasureSpec.getSize(heightMeasureSpec)
                // AT_MOST lets short content shrink. A wrap-content weighted scroll region absorbs
                // overflow while the heading and copy action remain outside the scrolling area.
                val limit = ReplyDrawerSizing.height(available, windowLimit)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10))
            background = theme.shape(theme.surface, 24); elevation = dp(12).toFloat(); isFocusableInTouchMode = true
        }
        body.addView(View(activity).apply { background = theme.shape(theme.border, 2) },
            LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(theme.label("帮我回", 18f, bold = true))
            addView(theme.label("言外 ${BuildConfig.VERSION_NAME} · 狗头军师", 11f, theme.muted))
        }
        heading.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("关闭", quiet = true) { window.dismiss() }, LinearLayout.LayoutParams(dp(56), dp(48)))
        body.addView(heading)
        val progress = ReplyLoadingView(activity, theme)
        body.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val scroll = ScrollView(activity).apply { isFillViewport = false; clipToPadding = false }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        val roleRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        fun roleLabel() = if (composition.relationship == ReplyRelationship.UNSPECIFIED) "选择对方身份 ▾"
            else "${composition.relationship.displayLabel(composition.customRelationship)} ▾"
        val rolePicker = action(roleLabel()).apply { maxLines = 1 }
        rolePicker.contentDescription = "选择对方身份，当前${composition.relationship.displayLabel(composition.customRelationship)}"
        val historyPicker = action("参考最近 ${composition.historyLimit} 条 ▾").apply { maxLines = 1 }
        // 两格等宽；字号在 10–13sp 之间自适应，避免长身份名被截断。
        listOf(rolePicker, historyPicker).forEach { button ->
            button.textSize = 12f
            button.setPadding(dp(6), dp(6), dp(4), dp(6))
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                button, 10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        roleRow.addView(rolePicker, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        roleRow.addView(historyPicker, LinearLayout.LayoutParams(0, -2, 1f))
        results.addView(roleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        var roleExpanded = composition.relationship == ReplyRelationship.OTHER && composition.customRelationship.isBlank()
        var instructionExpanded = false
        val roleToggle = action("角色背景 ▾", quiet = true)
        val instructionToggle = action("本次补充 ▾", quiet = true)
        val disclosureRow = LinearLayout(activity)
        listOf(roleToggle, instructionToggle).forEachIndexed { index, toggle ->
            toggle.textSize = 12f; toggle.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            toggle.maxLines = 2; toggle.ellipsize = android.text.TextUtils.TruncateAt.END
            toggle.setPadding(dp(4), dp(4), dp(4), dp(4))
            disclosureRow.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) rightMargin = dp(8) })
        }
        results.addView(disclosureRow)
        val roleEditor = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        results.addView(roleEditor, LinearLayout.LayoutParams(-1, -2))
        val customRole = EditText(activity).apply {
            hint = "填写对方名称或身份，如：小林、前同事"
            contentDescription = "自定义对方身份，最多 ${ReplyRelationship.MAX_CUSTOM_LENGTH} 字"
            textSize = 14f; setTextColor(theme.ink); setHintTextColor(theme.muted)
            inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true); minHeight = dp(48)
            filters = arrayOf(InputFilter.LengthFilter(ReplyRelationship.MAX_CUSTOM_LENGTH)); isSaveEnabled = false
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = theme.shape(theme.card, 12, theme.border)
            setText(composition.customRelationship)
            visibility = if (composition.relationship == ReplyRelationship.OTHER) View.VISIBLE else View.GONE
            setOnFocusChangeListener { _, focused -> background = theme.shape(theme.card, 12, if (focused) theme.accent else theme.border) }
        }
        roleEditor.addView(customRole, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val identityBackground = EditText(activity).apply {
            hint = "这个角色的经历、偏好或相处方式（选填）"
            textSize = 14f; setTextColor(theme.ink); setHintTextColor(theme.muted)
            gravity = Gravity.TOP or Gravity.START; minLines = 2; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(2000)); isSaveEnabled = false
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = theme.shape(theme.card, 12, theme.border)
            setText(composition.background.text)
        }
        roleEditor.addView(identityBackground, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        var refreshFacts: (Boolean) -> Unit = {}
        val factActions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        // 按钮自己就是进度：点完立刻改字，不必去底部状态行找结果。
        val factsAction = action("更新关系档案", quiet = true) { refreshFacts(false) }
        factsActionRef = factsAction
        factActions.addView(factsAction,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        factActions.addView(action("口吻画像 ▾", quiet = true) {
            showStyleProfile(StyleProfileCache.of(talker.orEmpty(), live.messages))
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        roleEditor.addView(factActions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val instruction = EditText(activity).apply {
            hint = "这次想怎么回？如：委婉拒绝、别太正式（选填）"
            textSize = 14f; setTextColor(theme.ink); setHintTextColor(theme.muted)
            gravity = Gravity.TOP or Gravity.START; minLines = 2; maxLines = 4; minHeight = dp(48)
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = theme.shape(theme.card, 12, theme.border)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(2000)); isSaveEnabled = false
            // 不再回填上次的补充要求：旧版本曾把模型输出写进这里，回填后会被当成要求发给模型。
            setText("")
            setOnFocusChangeListener { _, focused -> background = theme.shape(theme.card, 12, if (focused) theme.accent else theme.border) }
        }
        results.addView(instruction, LinearLayout.LayoutParams(-1, -2))
        // 草稿体检：只在输入框有文字时出现，点一下看这句会不会说错。
        var lastRewrite: String? = null
        var checkDraftAction: (() -> Unit)? = null
        var backgroundSaveJob: Job? = null
        val draftPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = theme.shape(theme.card, 12, theme.accent)
            visibility = View.GONE
        }
        val draftIssues = theme.label("", 13f, theme.ink)
        // 同一句话在不同关系的人听来可能完全不同，这是「言外」这个名字本来的意思。
        val draftReadings = theme.label("", 12f, theme.muted).apply { setPadding(0, dp(6), 0, 0) }
        val draftRewrite = theme.label("", 15f, theme.ink).apply { setPadding(0, dp(6), 0, 0) }
        val draftUse = action("用这条替换") {
            val rewrite = lastRewrite
            if (rewrite.isNullOrBlank()) return@action
            val target = editor()
            if (target == null) {
                toast("找不到微信输入框，请切回文字输入再试")
                return@action
            }
            // 写的是微信聊天输入框，不是抽屉里的「本次补充」。
            target.setText(rewrite)
            target.setSelection(rewrite.length)
            hide()
            toast("已替换，自己确认后再发送")
        }
        draftPanel.addView(draftIssues)
        draftPanel.addView(draftReadings)
        draftPanel.addView(draftRewrite)
        draftPanel.addView(draftUse, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(10) })
        results.addView(draftPanel)
        val checkDraft = action("检查输入框这句") { checkDraftAction?.invoke() }.apply { visibility = View.GONE }
        // 把「这一轮在回哪条」摆在按钮上方：没有长按的时候，用户也能看出默认目标是什么。
        val targetLine = theme.label(replyTargetText(captured), 12f, theme.muted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(2))
        }
        results.addView(targetLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val generateButton = action("生成回复", primary = true)
        val topicButton = action("找找话题")
        val shorter = action("更简短", quiet = true)
        val generationActions = LinearLayout(activity)
        listOf(generateButton, topicButton, checkDraft).forEachIndexed { index, button ->
            button.textSize = 13f; button.setPadding(dp(4), dp(6), dp(4), dp(6))
            // Let large system fonts wrap instead of clipping an action or shrinking the touch target.
            generationActions.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index < 2) rightMargin = dp(6) })
        }
        results.addView(generationActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val state = theme.label("", 12f, theme.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(6), 0, dp(6))
        }
        results.addView(state)
        val stale = action("有新消息 · 重新读取", quiet = true)
        stale.visibility = View.GONE; newMessageNotice = stale
        results.addView(stale, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        val replyTitle = action("", quiet = true) { composition.result?.context?.let(::showEvidence) }.apply {
            textSize = 12f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0)
        }
        results.addView(replyTitle)
        val topicTitle = theme.label("", 14f, bold = true)
        results.addView(topicTitle)
        val parts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        results.addView(parts)
        val topicHint = theme.label("", 12f, theme.muted).apply { setPadding(0, dp(6), 0, dp(4)) }
        results.addView(topicHint)
        val reasonToggle = action("为什么这样回 ▾", quiet = true)
        val reason = theme.label("", 12f, theme.muted).apply { visibility = View.GONE }
        reasonToggle.setOnClickListener {
            val expanded = reason.visibility != View.VISIBLE
            reason.visibility = if (expanded) View.VISIBLE else View.GONE
            reasonToggle.text = if (expanded) "收起理由 ▴" else if (composition.result?.topics != null) "为什么聊这个 ▾" else "为什么这样回 ▾"
        }
        // 沙盘：预演时整个抽屉只留这一条线，候选、理由、复制填入全部收起，
        // 否则两套东西同屏，分不清哪句接哪句。
        var runSimulationRef: (() -> Unit)? = null
        var exitSimulationRef: (() -> Unit)? = null
        var simMode = false
        var simBusy = false
        var simRoot = ""
        var simLines: List<SimLine> = emptyList()
        var simNote = ""
        val simulate = action("预演反应", quiet = true) {
            if (simMode) exitSimulationRef?.invoke() else runSimulationRef?.invoke()
        }
        val resultActions = LinearLayout(activity)
        resultActions.addView(reasonToggle, LinearLayout.LayoutParams(0, -2, 1f))
        resultActions.addView(shorter, LinearLayout.LayoutParams(0, -2, 1f))
        resultActions.addView(simulate, LinearLayout.LayoutParams(0, -2, 1f))
        results.addView(resultActions); results.addView(reason)
        val simPanel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        results.addView(simPanel)
        scroll.addView(results); body.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        body.addView(line(), LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
        fun fillIntoEditor(text: String) {
            val input = editor() ?: run { toast("请先切换到文字输入"); return }
            val previous = input.text?.toString().orEmpty()
            if (previous.isNotBlank() && previous != text) {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("言外原草稿", previous))
            }
            input.setText(text)
            input.setSelection(text.length)
            input.requestFocus()
            runCatching {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            // 已经写进聊天框，抽屉直接收起，把屏幕还给用户自己改和发；不再弹提示。
            runCatching { window.dismiss() }
        }
        val fill = action("填入聊天框", primary = true) {
            composition.selectedText?.let { fillIntoEditor(it) }
        }
        val copy = action("复制这条") {
            composition.selectedText?.let { text ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("言外回复建议", text))
                toast("已复制第 ${composition.selectedPart + 1} 个方案")
            }
        }
        val footerActions = LinearLayout(activity).apply { setPadding(0, dp(10), 0, 0) }
        footerActions.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        footerActions.addView(fill, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(footerActions)
        val footerHint = theme.label("填入后可自由修改，发送永远由你点", 11f, theme.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        }
        body.addView(footerHint)
        var busy = false
        var failed = false
        var findingTopics = false
        var applyingRole = false
        var identitySaving = false
        fun hasUnsavedIdentityBackground() = false
        // 背景改为停止输入后自动保存，不再需要「未保存」拦截。
        fun topicKey(current: ReplyContext, draft: String, date: String) = TopicKey(current.fingerprint, current.requestedMessages,
            composition.relationship, instruction.text.toString(), draft, date, composition.activeCustomRelationship)
        fun preview(text: CharSequence, empty: String) = text.toString().trim().replace(Regex("\\s+"), " ").take(22).ifBlank { empty }
        fun collapseEditors() {
            roleExpanded = false; instructionExpanded = false
            if (customRole.hasFocus() || identityBackground.hasFocus() || instruction.hasFocus()) {
                body.requestFocus()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(body.windowToken, 0)
            }
        }
        fun controls(generatingRequest: Boolean) {
            val generating = generatingRequest || applyingRole || identitySaving
            val reading = reference.loading
            val ready = reference.context
            busy = generating
            progress.showLoading(when {
                identitySaving -> "正在保存角色…"
                applyingRole -> "正在应用角色…"
                generating && findingTopics -> "正在准备 5 个新话题…"
                generating -> "正在根据 ${ready?.messages?.size ?: 0} 条消息生成回复…"
                reading -> "正在读取最近 ${composition.historyLimit} 条消息…"
                else -> null
            })
            generateButton.isEnabled = !generating && !reading && composition.hasValidRelationship && !hasUnsavedIdentityBackground()
            generateButton.text = when {
                hasUnsavedIdentityBackground() && !generating -> "先保存"
                generating -> if (findingTopics) "生成回复" else "生成中…"
                reading -> "读取中…"
                !composition.hasValidRelationship -> "先填身份"
                ready == null -> "重新读取"
                failed -> "重试生成"
                ready.source == ReplyContextSource.LOADED_PAGE -> "生成回复"
                composition.result == null || composition.result?.topics != null -> "生成回复"
                !composition.canUse -> "重新生成"
                else -> "重新生成"
            }
            shorter.isEnabled = !generating && !reading && ready != null && composition.canUse && !hasUnsavedIdentityBackground()
            shorter.visibility = if (composition.result == null || composition.result?.topics != null) View.GONE else View.VISIBLE
            // 有候选或输入框里有字才谈得上预演；正在生成时不给点。
            val simReady = composition.selectedText?.isNotBlank() == true || editor()?.text?.isNullOrBlank() == false
            simulate.text = if (simMode) "返回回复" else "预演反应"
            simulate.isEnabled = if (simMode) true else (!generating && !reading && ready != null && composition.canUse && simReady)
            simulate.visibility = if (simMode) View.VISIBLE
                else if (composition.result == null || composition.result?.topics != null) View.GONE else View.VISIBLE
            val batch = composition.result?.topics
            val currentTopicKey = ready?.let { topicKey(it.copy(background = composition.background), editor()?.text?.toString().orEmpty(), java.time.LocalDate.now().toString()) }
            val sameTopicInputs = composition.canUse && batch != null && batch.key == currentTopicKey
            topicButton.isEnabled = !generating && !reading && composition.hasValidRelationship && !hasUnsavedIdentityBackground()
            topicButton.text = when {
                generating && findingTopics -> "寻找中…"
                sameTopicInputs -> "换个话题"
                else -> "找找话题"
            }
            topicHint.visibility = if (batch == null) View.GONE else View.VISIBLE
            topicHint.text = when {
                !sameTopicInputs -> "参考内容、对方背景或本次要求已变化，请生成新的一组"
                batch?.hasNext == true -> "本组还有 ${batch.items.size - batch.shownCount} 个，点「换个话题」立即切换"
                else -> "本组已看完，再点「换个话题」生成新的一组"
            }
            instruction.isEnabled = !generating; rolePicker.isEnabled = !generating; stale.isEnabled = !generating
            customRole.isEnabled = !generating
            customRole.visibility = if (composition.relationship == ReplyRelationship.OTHER) View.VISIBLE else View.GONE
            val editingIdentity = composition.relationship != ReplyRelationship.UNSPECIFIED && !group
            val hasRoleDetails = editingIdentity || composition.relationship == ReplyRelationship.OTHER
            roleToggle.visibility = if (hasRoleDetails) View.VISIBLE else View.GONE
            roleEditor.visibility = if (hasRoleDetails && roleExpanded) View.VISIBLE else View.GONE
            instruction.visibility = if (instructionExpanded) View.VISIBLE else View.GONE
            roleToggle.isEnabled = !generating; instructionToggle.isEnabled = !generating
            val roleSectionTitle = if (group) "角色名称" else "角色背景"
            roleToggle.text = "$roleSectionTitle${if (hasUnsavedIdentityBackground()) " · 未保存" else ""} ${if (roleExpanded) "▴" else "▾"}\n" +
                preview(if (group) customRole.text else identityBackground.text, "未填写，点此编辑")
            instructionToggle.text = "本次补充 ${if (instructionExpanded) "▴" else "▾"}\n" + preview(instruction.text, "可选，点此填写")
            roleToggle.contentDescription = "$roleSectionTitle，${if (roleExpanded) "已展开，点击收起" else "已收起，点击展开"}"
            instructionToggle.contentDescription = "本次补充，${if (instructionExpanded) "已展开，点击收起" else "已收起，点击展开"}"
            generateButton.contentDescription = "${generateButton.text}，已读取 ${ready?.messages?.size ?: 0} 条消息"
            identityBackground.visibility = if (editingIdentity) View.VISIBLE else View.GONE
            identityBackground.isEnabled = !generating
            rolePicker.text = roleLabel()
            rolePicker.contentDescription = "选择对方身份，当前${composition.relationship.displayLabel(composition.customRelationship)}"
            historyPicker.isEnabled = !generating
            historyPicker.text = "参考最近 ${composition.historyLimit} 条 ▾"
            historyPicker.contentDescription = "选择参考聊天消息条数，当前最近 ${composition.historyLimit} 条"
            fill.isEnabled = !generating && !reading && composition.canUse
            copy.isEnabled = fill.isEnabled
            copy.text = "复制这条"
            footerActions.visibility = if (composition.result == null) View.GONE else View.VISIBLE
            footerHint.visibility = footerActions.visibility
            footerHint.text = if (!composition.canUse) "身份、背景或参考范围已改变，请重新生成"
                else "填入后可自由修改，发送永远由你点"
            state.visibility = if (state.text.isBlank()) View.GONE else View.VISIBLE
            if (window.isShowing) fitWindow()
            // GONE 时剩下的两格按 weight 平分整行；有文字时三格三等分。
            checkDraft.visibility = if (!generatingRequest && editor()?.text?.isNullOrBlank() == false) View.VISIBLE else View.GONE
            // 预演独占：候选、理由、填入都先让位，屏幕上只有「你说 → 他接」这一条线。
            if (simMode) {
                parts.visibility = View.GONE
                replyTitle.visibility = View.GONE
                topicTitle.visibility = View.GONE
                topicHint.visibility = View.GONE
                resultActions.visibility = View.GONE
                reason.visibility = View.GONE
                targetLine.visibility = View.GONE
                generationActions.visibility = View.GONE
                footerActions.visibility = View.GONE
                footerHint.visibility = View.GONE
            } else {
                parts.visibility = View.VISIBLE
                resultActions.visibility = View.VISIBLE
                targetLine.visibility = View.VISIBLE
                generationActions.visibility = View.VISIBLE
            }
        }
        roleToggle.setOnClickListener {
            if (!ownsDrawer() || busy) return@setOnClickListener
            val expand = !roleExpanded
            collapseEditors(); roleExpanded = expand; controls(false)
        }
        instructionToggle.setOnClickListener {
            if (!ownsDrawer() || busy) return@setOnClickListener
            val expand = !instructionExpanded
            collapseEditors(); instructionExpanded = expand; controls(false)
        }

        fun doRefreshFacts(auto: Boolean) {
            if (!auto && busy) {
                toast("正在生成回复，稍后再整理档案")
                return@doRefreshFacts
            }
            // 档案按会话存：群聊也要能手动整理，不能复用只认私聊的 owner.key。
            val key = ReplyContactKey.of(owner.account, owner.talker, allowGroup = true)
            if (key == null) {
                if (!auto) toast("账号或联系人尚未确认，暂时不能整理档案")
                return@doRefreshFacts
            }
            val settings = ModulePrefs.replySettings()
            if (!settings.isConfigured) {
                if (!auto) toast("请先在「回复」里配置模型")
                return@doRefreshFacts
            }
            if (!auto) {
                state.text = "正在整理关系档案…"
                factsActionRef?.apply { text = "整理中…"; isEnabled = false }
            }
            // 独立 scope：关掉抽屉或切走聊天都不会把这次请求掐断。
            // 但抽取必须用打开时的账号、联系人与消息：异步等待期间切到别的聊天，
            // 再从头读屏幕就会把 B 的消息按 A 的键存下去。
            val snapshot = owner.account?.let { account ->
                RelationFactRefresher.Snapshot(account, owner.talker, live.messages)
            }
            // 结果同时写进按钮和 Toast：底部状态行经常不在视野里，用户以为没点动。
            fun finishFacts(message: String, toastText: String?) {
                factsActionRef?.apply { text = "更新关系档案"; isEnabled = true }
                if (ownsDrawer()) state.text = message
                if (!auto && toastText != null) toast(toastText)
            }
            val started = RelationFactRefresher.launch(activity, key, settings, snapshot = snapshot) { result, error ->
                when {
                    error != null -> {
                        MoodLog.w("RELATION_FACTS_FAILED " + error.javaClass.simpleName)
                        finishFacts("关系档案更新失败：" + (error.message ?: "请重试"),
                            "整理失败：" + (error.message ?: "请重试"))
                    }
                    result == null -> finishFacts("关系档案没有更新", null)
                    result.nothingNew -> finishFacts("最近没有新消息，不用整理", "最近没有新消息")
                    result.facts.isEmpty() -> finishFacts("这次没有提取到新的事实",
                        "看了 ${result.scanned} 条，没有可整理的新事实")
                    else -> {
                        facts = result.facts
                        val changed = result.added + result.replaced + result.superseded > 0
                        val text = (if (changed) "关系档案已更新：" else "") + result.changeText()
                        finishFacts(text, if (changed) "档案已更新：" + result.changeText()
                            else result.changeText())
                    }
                }
            }
            if (!started && !auto) {
                factsActionRef?.text = "整理中…"
                if (ownsDrawer()) state.text = "正在整理，稍等…"
            }
        }
        fun showDraftCheck(check: DraftCheck) {
            if (!check.parsed) {
                draftPanel.visibility = View.GONE
                state.text = "没看懂模型的返回，点一次重试；连续失败就看下模型配置"
                return
            }
            val readings = check.readings
            draftIssues.text = check.issues.joinToString("\n")
            draftIssues.visibility = if (check.issues.isEmpty()) View.GONE else View.VISIBLE
            // 多视角读法即使没挑出毛病也留着：这句话在别的关系里可能完全是另一个意思。
            draftReadings.text = readings.entries.joinToString("\n") { "在${it.key}听来：${it.value}" }
            draftReadings.visibility = if (readings.isEmpty()) View.GONE else View.VISIBLE
            lastRewrite = check.rewrite
            draftRewrite.text = check.rewrite?.let { "可以改成：$it" }.orEmpty()
            draftRewrite.visibility = if (check.rewrite == null) View.GONE else View.VISIBLE
            draftUse.visibility = if (check.rewrite == null) View.GONE else View.VISIBLE
            val shown = check.issues.isNotEmpty() || check.rewrite != null || readings.isNotEmpty()
            draftPanel.visibility = if (shown) View.VISIBLE else View.GONE
            if (!shown && check.verdict == null) {
                // 模型两次都没给结构时用本地判断兜底，避免只给一句「不擅长」。
                state.text = DraftFallback.hint(draftText, draftLastIncoming)
                return
            }
            state.text = if (check.issues.isEmpty() && check.rewrite == null) {
                check.verdict?.let { "参考最近 ${check.referenceCount} 条 · 模型判断：$it" }.orEmpty()
            } else {
                check.verdict?.let { "模型判断：$it" }.orEmpty()
            }
        }
        fun runDraftCheck() {
            if (busy) { MoodLog.i("DRAFT_CHECK_SKIP busy"); return }
            // 检查的是微信聊天输入框里已经写好的话，不是抽屉里的「本次补充要求」。
            val text = editor()?.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                MoodLog.i("DRAFT_CHECK_SKIP empty_input")
                toast("先在微信输入框里写好要发的话")
                return
            }
            // 脏话这类明显问题本地先兜一层，不依赖模型。
            DraftGuard.hint(text)?.let { hint ->
                MoodLog.i("DRAFT_CHECK_LOCAL_HIT chars=${text.length}")
                showDraftCheck(DraftCheck(listOf(hint), null))
                return
            }
            val settings = ModulePrefs.replySettings()
            if (!settings.isConfigured) { MoodLog.i("DRAFT_CHECK_SKIP unconfigured"); toast("请先配置回复模型"); return }
            // 手动点检查就给新结果：缓存会让同一句反复测试时一直显示旧结论。
            val current = reference.context
            draftTargetMedia = current?.targetMedia
            draftText = text
            draftLastIncoming = current?.messages?.lastOrNull { it.speaker != "我" }?.text
            if (current == null) {
                MoodLog.i("DRAFT_CHECK_SKIP no_context")
                toast("先点一次「重新读取聊天记录」，再检查")
                return
            }
            state.text = "正在检查这句…"
            // 先清掉上一次的卡片，避免新结果和旧结论混在一起。
            draftPanel.visibility = View.GONE
            draftIssues.text = ""
            draftReadings.text = ""
            draftRewrite.text = ""
            lastRewrite = null
            busy = true; controls(true)
            scope.launch {
                try {
                    val payload = DraftCheckProtocol.payload(settings, current.copy(facts = facts), text,
                        composition.relationship, composition.activeCustomRelationship, current.targetImageDataUri)
                    val client = ReplyHttpClient()
                    val lastIncoming = current.messages.lastOrNull { it.speaker != "我" }?.text
                    val focus = current.focusText ?: lastIncoming
                    var check = withContext(Dispatchers.IO) { client.request(settings, payload, DraftCheckProtocol::parse) }
                    // 目标校验：模型说回应的片段要和 focus 或对方最后一句对得上，否则重问一次。
                    check.responded?.let { responded ->
                        if (focus != null && RelationFacts.similarity(responded, focus) < 0.2) {
                            MoodLog.i("DRAFT_CHECK_TARGET_MISMATCH len=${responded.length}")
                            check = withContext(Dispatchers.IO) { client.request(settings, payload, DraftCheckProtocol::parse) }
                        }
                    }
                    // 模型偶尔不回结构：自动重试一次，别让用户再点。
                    if (check.isEmpty && check.verdict == null) {
                        MoodLog.i("DRAFT_CHECK_RETRY 首次没有判断依据")
                        check = withContext(Dispatchers.IO) { client.request(settings, payload, DraftCheckProtocol::parse) }
                    }
                    // 两次都不给结构：换成「只要一句改写」的简单问法，至少拿到能用的建议。
                    if (check.isEmpty && check.verdict == null) {
                        MoodLog.i("DRAFT_CHECK_SIMPLE 换简化问法")
                        val simple = DraftCheckProtocol.simplePayload(settings, text, focus, lastIncoming)
                        val rewrite = withContext(Dispatchers.IO) {
                            client.request(settings, simple, DraftCheckProtocol::simpleParse)
                        }
                        if (rewrite != null) {
                            check = DraftCheck(emptyList(), rewrite, verdict = "模型没给出判断，只给了一条改写")
                        }
                    }
                    showDraftCheck(check.copy(referenceCount = current.messages.size))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.text = "检查失败：" + (e.message ?: "请重试") }
                finally { busy = false; controls(false) }
            }
        }
        checkDraftAction = { runDraftCheck() }
        refreshFacts = ::doRefreshFacts

        // ── 对话沙盘：预演这句话发出去，他可能怎么接，我还可以怎么接下去 ──
        // 局部函数之间互相调用，Kotlin 要求先声明后使用，这里统一走 ref，顺序就不影响。
        val simMaxDepth = 3
        // 递归收起：父节点收起时它下面整棵子树一起收。
        fun collapseAllLines(lines: List<SimLine>) {
            lines.forEach { it.collapsed = true; collapseAllLines(it.children) }
        }
        // 请求没真正发出去（未配置、无上下文）时 loading 会留在节点上，
        // 那张卡就会永远显示「正在往下推…」并且按钮消失；渲染前统一清掉。
        fun clearLoading(lines: List<SimLine>) {
            lines.forEach { it.loading = false; clearLoading(it.children) }
        }
        /**
         * 手风琴：展开一条时把其他分支收起，屏幕上永远只有一条线在往下长。
         * 返回 keep 是否在这棵子树里 —— 是的话这一层（含它的祖先）必须保持展开，
         * 否则点开 3.2.1 会把它的父层 3.2 一起收掉，整块直接消失。
         */
        fun collapseOthers(lines: List<SimLine>, keep: SimLine): Boolean {
            var found = false
            lines.forEach { line ->
                if (line === keep) {
                    line.collapsed = false
                    found = true
                } else {
                    val inside = if (line.children.isEmpty()) false else collapseOthers(line.children, keep)
                    line.collapsed = !inside
                    if (inside) found = true
                }
            }
            return found
        }
        var renderSimLinesRef: ((List<SimLine>, Int, String, LinearLayout) -> Unit)? = null
        var advanceSimulationRef: ((SimLine) -> Unit)? = null
        var requestSimulationRef: ((String, String, (SimulationResult) -> Unit) -> Unit)? = null

        fun renderSimulation() {
            simPanel.removeAllViews()
            if (!simMode) { if (window.isShowing) fitWindow(); return }
            if (!simBusy) clearLoading(simLines)
            // 起点：就是你要发的那一句，整条线从这里往下长。
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = theme.shape(theme.soft, 12, theme.accent)
            }
            root.addView(theme.label("你说 · 第 1 句", 11f, theme.accent, bold = true))
            root.addView(theme.label(simRoot, 16f).apply { setPadding(0, dp(4), 0, 0) })
            simPanel.addView(root, LinearLayout.LayoutParams(-1, -2))

            simPanel.addView(theme.label("他可能怎么接 · 模型生成的可能，不是事实", 11f, theme.muted)
                .apply { setPadding(dp(2), dp(10), 0, 0) })
            if (simLines.isEmpty()) {
                simPanel.addView(theme.label(if (simBusy) "正在预演…" else "还没有走向", 12f, theme.muted)
                    .apply { setPadding(dp(2), dp(8), 0, 0) })
            } else {
                renderSimLinesRef?.invoke(simLines, 0, "", simPanel)
            }

            val tools = LinearLayout(activity).apply { setPadding(0, dp(10), 0, 0) }
            tools.addView(action("换一句重推", quiet = true) {
                if (simBusy) return@action
                simLines = emptyList(); simNote = ""
                runSimulationRef?.invoke()
            }.apply { textSize = 12f }, LinearLayout.LayoutParams(0, dp(44), 1f))
            tools.addView(action("收起全部", quiet = true) {
                if (simBusy) return@action
                collapseAllLines(simLines)
                renderSimulation()
            }.apply { textSize = 12f }, LinearLayout.LayoutParams(0, dp(44), 1f))
            tools.addView(action("回到候选", quiet = true) { exitSimulationRef?.invoke() }
                .apply { textSize = 12f }, LinearLayout.LayoutParams(0, dp(44), 1f))
            simPanel.addView(tools)
            if (simNote.isNotBlank())
                simPanel.addView(theme.label(simNote, 11f, theme.muted).apply { setPadding(dp(2), dp(4), 0, 0) })
            if (window.isShowing) fitWindow()
        }

        // 一条分支一张卡，头行可以折叠；继续往下推的结果缩进嵌在这张卡内部。
        // 层次靠「嵌套 + 缩进 + 间隙」表达，序号只是附带，不用盯着数字看。
        fun renderSimLines(lines: List<SimLine>, depth: Int, prefix: String, container: LinearLayout) {
            if (depth >= simMaxDepth) return
            lines.forEachIndexed { index, line ->
                val tag = prefix + (index + 1)
                val outer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

                val box = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(11), dp(7), dp(9), dp(if (line.collapsed) 7 else 10))
                    background = theme.shape(theme.card, 12, theme.border)
                }

                // 头行：箭头 + 序号 + 档位，整行可点折叠。
                val head = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true; isFocusable = true
                    minimumHeight = dp(36)
                    contentDescription = "第 " + tag + " 条走向，" + line.stance +
                        (if (line.collapsed) "，已收起，点击展开" else "，已展开，点击收起")
                    setOnClickListener {
                        if (simBusy) return@setOnClickListener
                        if (line.collapsed) {
                            // 展开前先收掉其他分支：同级兄弟始终连续，不会被谁的孩子撑开。
                            collapseOthers(simLines, line)
                            line.collapsed = false
                        } else {
                            line.collapsed = true
                        }
                        renderSimulation()
                    }
                }
                // 一级标题字号比子层大一档：层级从字号上就能看出，不用靠读序号。
                val headSize = if (depth == 0) 12f else 11f
                head.addView(theme.label(if (line.collapsed) "\u25B8" else "\u25BE", 14f, theme.muted))
                head.addView(theme.label(" " + tag + " ", headSize, theme.accent, bold = true))
                head.addView(theme.label("· " + line.stance, headSize, theme.accent))
                if (line.collapsed)
                    head.addView(theme.label(line.theirs, 12f, theme.muted).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(8), 0, 0, 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(head)

                if (!line.collapsed) {
                    box.addView(theme.label("他：" + line.theirs, 15f).apply { setPadding(0, dp(3), 0, 0) })
                    if (line.basis.isNotBlank())
                        box.addView(theme.label("依据：" + line.basis, 11f, theme.muted)
                            .apply { setPadding(0, dp(3), 0, 0) })
                    // 一条分隔线隔开「他说的」和「我能发的」，避免两句话看混。
                    box.addView(View(activity).apply { setBackgroundColor(theme.border) },
                        LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
                    box.addView(theme.label("我：" + line.mine, 15f, theme.accent))
                    val footNote = buildString {
                        if (line.move.isNotBlank()) append("提示：").append(line.move)
                        if (simBusy && line.loading) { if (isNotEmpty()) append("  "); append("正在往下推…") }
                    }
                    if (footNote.isNotBlank())
                        box.addView(theme.label(footNote, 11f, theme.muted).apply { setPadding(0, dp(4), 0, 0) })
                    if (!simBusy && depth + 1 < simMaxDepth) {
                        val row = LinearLayout(activity)
                        row.addView(action("接着这句往下推", quiet = true) {
                            if (simBusy) return@action
                            advanceSimulationRef?.invoke(line)
                        }.apply { textSize = 12f }, LinearLayout.LayoutParams(0, dp(40), 1f))
                        row.addView(action("用这句回复", primary = true) {
                            if (simBusy) return@action
                            fillIntoEditor(line.mine)
                        }.apply { textSize = 12f }, LinearLayout.LayoutParams(0, dp(40), 1f))
                        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                    }
                }

                // 全列表共用一条主线：竖线固定在同一列（中心 x≈9dp），圆点按层级变小变淡；
                // 子层的卡片缩进，并从主线伸出一小段横线连回自己 —— 2.1 的线就长在 2 的线上，
                // 父子关系一眼能看出来，而不是三条互不相干的平行线。
                // 间距加在 box 的 margin 上、axis 用 MATCH_PARENT，主线才会连成一整条而不在卡片之间断开。
                val indent = when (depth) { 0 -> 18; 1 -> 34; else -> 50 }
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                val axis = FrameLayout(activity)
                axis.addView(View(activity).apply { setBackgroundColor(theme.border) },
                    FrameLayout.LayoutParams(dp(2), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                        gravity = Gravity.TOP or Gravity.LEFT; leftMargin = dp(8)
                    })
                if (depth > 0) {
                    // 分支短横线：从主线（x=9）伸到自己的卡片左边缘。
                    axis.addView(View(activity).apply { setBackgroundColor(theme.border) },
                        FrameLayout.LayoutParams(dp(indent - 9), dp(2)).apply {
                            gravity = Gravity.TOP or Gravity.LEFT
                            leftMargin = dp(9); topMargin = dp(17)
                        })
                }
                val dotSize = when (depth) { 0 -> 10; 1 -> 8; else -> 6 }
                axis.addView(View(activity).apply {
                    background = theme.shape(if (depth == 0) theme.accent else theme.muted, dp(dotSize / 2))
                }, FrameLayout.LayoutParams(dp(dotSize), dp(dotSize)).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    leftMargin = dp(9 - dotSize / 2); topMargin = dp(17 - dotSize / 2)
                })
                row.addView(axis, LinearLayout.LayoutParams(dp(indent), LinearLayout.LayoutParams.MATCH_PARENT))
                row.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    // 一级之间留得更开：它上面可能压着一整段子层，挤在一起就分不出是哪一段。
                    topMargin = dp(if (depth == 0) 20 else 6)
                    bottomMargin = dp(if (depth == 0) 4 else 2)
                })
                outer.addView(row, LinearLayout.LayoutParams(-1, -2))

                // 子层缩进嵌在父卡下面：1.1 物理上就长在 1 里面。
                // 根层卡片被左侧 dp(18) 轴线向右推过，所以第一层子卡必须缩进得比轴线更深，
                // 否则二级会比一级更靠左、看着反而比一级还大（2.9.250 就是这个错）。
                if (line.children.isNotEmpty() && !line.collapsed) {
                    val kids = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        // 缩进由每行自己的 indent 决定，这里不再叠加 padding，主线才对得上同一列。
                        setPadding(0, 0, 0, 0)
                    }
                    outer.addView(kids, LinearLayout.LayoutParams(-1, -2))
                    renderSimLines(line.children, depth + 1, tag + ".", kids)
                }
                container.addView(outer, LinearLayout.LayoutParams(-1, -2))
            }
        }

        // 以某条分支的「我」那句为新起点，再问他可能怎么接。
        fun advanceSimulation(line: SimLine) {
            simBusy = true
            line.loading = true
            renderSimulation()
            requestSimulationRef?.invoke(line.mine, "正在推演「" + shortText(line.mine, 12) + "」之后…") { result ->
                line.loading = false
                // 子层默认收起：三条一行摘要排在父卡下面，同级兄弟不会被撑到看不见。
                line.children = result.branches.map {
                    SimLine(it.stance, it.theirs, it.basis, it.mine, it.move, collapsed = true)
                }
                if (result.branches.isEmpty()) simNote = "这一步没推出可用的走向，换个说法再试。"
            }
        }

        fun runSimulation() {
            if (simBusy) return
            if (!ownsDrawer()) { window.dismiss(); return }
            val settings = ModulePrefs.replySettings()
            if (!settings.isConfigured) { toast("请先配置回复模型"); return }
            val candidate = (composition.selectedText ?: editor()?.text?.toString().orEmpty()).trim()
            if (candidate.isBlank()) { toast("先选一句候选，或在输入框里写好要说的话"); return }
            simMode = true
            simRoot = candidate
            simLines = emptyList()
            simNote = ""
            simBusy = true
            controls(false)
            renderSimulation()
            requestSimulationRef?.invoke(candidate, "正在预演「" + shortText(candidate, 12) + "」之后…") { result ->
                simLines = result.branches.map { SimLine(it.stance, it.theirs, it.basis, it.mine, it.move) }
                simNote = if (result.branches.size < 3)
                    "只给出 " + result.branches.size + " 个走向：其余被判定为雷同或复述原话，没有凑数。" else ""
            }
        }

        fun requestSimulation(candidate: String, note: String, onResult: (SimulationResult) -> Unit) {
            val settings = ModulePrefs.replySettings()
            if (!settings.isConfigured) { toast("请先配置回复模型"); simBusy = false; renderSimulation(); return }
            val base = reference.context
            if (base == null) { toast("先读取聊天记录再预演"); simBusy = false; renderSimulation(); return }
            val current = base.copy(background = composition.background, facts = facts,
                focusMessageId = if (base.focusMessageId > 0) base.focusMessageId else targetFocusId,
                targetImageDataUri = base.targetImageDataUri ?: targetImage)
            val relationship = composition.relationship
            val customRelationship = composition.activeCustomRelationship
            val ticket = simSession.begin(current.talker, current.fingerprint)
            state.text = note; state.setTextColor(theme.muted)
            val client = ReplyHttpClient()
            simJob?.cancel()
            simJob = scope.launch {
                try {
                    val knowledge = withContext(Dispatchers.IO) {
                        ReplyKnowledge.loadFor(activity, relationship,
                            current.messages.joinToString("\n") { it.text } + "\n" + candidate)
                    }
                    suspend fun ask(correction: String) = withContext(Dispatchers.IO) {
                        client.request(settings, SimulationProtocol.payload(settings, current, candidate, knowledge,
                            relationship = relationship, customRelationship = customRelationship, correction = correction),
                            { body -> SimulationProtocol.parse(body, candidate) },
                            kind = dev.jev.wechatmood.core.UsageKind.SIMULATE)
                    }
                    var result = ask("")
                    // 不足两条＝模型在写同义改写；把上次的问题直接告诉它再换一次。
                    if (result.branches.size < 2) {
                        MoodLog.i("SIMULATION_RETRY kept=" + result.branches.size + " dropped=" + result.dropped)
                        result = ask("分支雷同或复述了原话，只有 " + result.branches.size + " 条可用")
                    }
                    if (!simSession.accepts(ticket, MessageSniffer.currentReplyTalker()) || !ownsDrawer()) return@launch
                    if (result.isEmpty) {
                        MoodLog.w("SIMULATION_EMPTY dropped=" + result.dropped)
                        state.text = "这次没给出可用的走向，换个说法或再试一次"
                        state.setTextColor(theme.error)
                        return@launch
                    }
                    MoodLog.i("SIMULATION_OK branches=" + result.branches.size + " dropped=" + result.dropped +
                        " step_len=" + candidate.length)
                    state.text = ""
                    onResult(result)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    MoodLog.w("SIMULATION_FAIL " + e.javaClass.simpleName)
                    if (simSession.accepts(ticket, MessageSniffer.currentReplyTalker()) && ownsDrawer())
                        state.text = "预演失败：" + (e.message ?: "请重试")
                } finally {
                    simBusy = false
                    if (ownsDrawer()) { state.setTextColor(theme.muted); renderSimulation(); controls(false) }
                }
            }
        }

        renderSimLinesRef = { lines, depth, prefix, box -> renderSimLines(lines, depth, prefix, box) }
        advanceSimulationRef = { line -> advanceSimulation(line) }
        requestSimulationRef = { candidate, note, done -> requestSimulation(candidate, note, done) }
        exitSimulationRef = {
            simMode = false; simBusy = false
            // 退出时取消在途请求：否则它还会跑完一整轮，白烧一次调用。
            simJob?.cancel(); simSession.cancel()
            simRoot = ""; simLines = emptyList(); simNote = ""
            simPanel.removeAllViews()
            state.text = ""
            controls(false)
        }
        runSimulationRef = { runSimulation() }
        fun revealReply() {
            collapseEditors(); controls(false)
            scroll.post { if (ownsDrawer()) scroll.smoothScrollTo(0, replyTitle.top) }
        }
        fun renderParts() {
            parts.removeAllViews()
            val result = composition.result
            replyTitle.visibility = if (result == null) View.GONE else View.VISIBLE
            val batch = result?.topics
            topicTitle.text = batch?.current?.title.orEmpty()
            topicTitle.visibility = if (batch == null) View.GONE else View.VISIBLE
            val kind = if (batch == null) "${result?.suggestion?.parts?.size ?: 0} 个方案" else "话题 ${batch.shownCount} / ${batch.items.size}"
            replyTitle.text = result?.let { "${it.relationship.displayLabel(it.customRelationship)} · $kind${if (!composition.canUse) "（上次结果）" else ""} · 查看依据 ›" }.orEmpty()
            result?.suggestion?.parts?.forEachIndexed { index, text ->
                val selected = composition.selectedPart == index
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(48)
                    background = theme.shape(if (selected) theme.soft else theme.card, 12, if (selected) theme.accent else theme.border)
                    addView(theme.label("候选 ${index + 1} / ${result.suggestion.parts.size}${if (selected) " · 已选" else " · 点选"}", 12f, theme.muted))
                    addView(theme.label(text, 16f).apply { setPadding(0, dp(3), 0, 0) })
                    isClickable = true; isFocusable = true
                    contentDescription = "第 ${index + 1} 个方案，共 ${result.suggestion.parts.size} 个${if (selected) "，已选" else ""}，$text"
                    setOnClickListener { composition.select(index); renderParts(); controls(busy) }
                }
                parts.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            reason.text = result?.suggestion?.reason.orEmpty()
            reasonToggle.visibility = if (reason.text.isBlank()) View.GONE else View.VISIBLE
            if (reason.text.isBlank()) reason.visibility = View.GONE
            reasonToggle.text = if (reason.visibility == View.VISIBLE) "收起理由 ▴" else if (batch != null) "为什么聊这个 ▾" else "为什么这样回 ▾"
        }
        var saveRevision = 0L
        fun saveIdentity() {
            if (group || !ownsDrawer()) return
            if (owner.key == null) { toast("账号或联系人尚未确认，本次身份不会保存"); return }
            val revision = ++saveRevision
            val value = ReplyIdentitySetting(composition.relationship, composition.customRelationship)
            // The immutable owner and original verified page remain attached even after closing/switching.
            ReplyIdentityBridge.save(activity, owner, value, { ReplyDatabaseHistory.replyAccount(live) }) { saved ->
                activity.runOnUiThread {
                    if (ownsDrawer() && revision == saveRevision && !saved) toast("身份保存失败，请重新选择后重试")
                }
            }
        }
        identityBackground.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (ownsDrawer()) controls(busy)
                if (applyingRole) return
                // 停止输入 800ms 后自动存到当前联系人，不写角色库、不影响其他聊天。
                backgroundSaveJob?.cancel()
                backgroundSaveJob = scope.launch {
                    delay(800)
                    val key = owner.key ?: return@launch
                    if (!ownsDrawer()) return@launch
                    runCatching {
                        ReplyIdentityBridge.saveBackground(activity, owner, s?.toString().orEmpty(),
                            { ReplyDatabaseHistory.replyAccount(live) })
                    }.onSuccess { saved -> composition.background = saved }
                }
            }
        })
        customRole.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingRole || !ownsDrawer()) return
                composition.customRelationship = s?.toString().orEmpty()
                failed = false; renderParts(); controls(busy)
            }
        })
        fun showRoleMenu(savedRoles: List<ReplyRole>, libraryAvailable: Boolean = true) {
            PopupMenu(activity, rolePicker).apply {
                // Persisted presets and custom roles share the app's catalog; never resurrect deleted presets.
                val choices = if (libraryAvailable) listOf(ReplyRelationship.UNSPECIFIED, ReplyRelationship.OTHER)
                    else ReplyRelationship.entries
                choices.forEach { relationship ->
                    menu.add(0, relationship.ordinal, relationship.ordinal, relationship.label).isChecked = relationship == composition.relationship
                }
                savedRoles.forEachIndexed { index, role -> menu.add(1, 1000 + index, 1000 + index, "角色库 · " + role.name) }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item ->
                    if (item.groupId == 1) {
                        if (!busy && ownsDrawer() && !group) {
                            val selected = savedRoles[item.itemId - 1000]
                            applyingRole = true
                            job?.cancel(); session.cancel(); controls(true)
                            scope.launch {
                                try {
                                    if (!verifyAccount()) return@launch
                                    val applied = ReplyIdentityBridge.applyRole(activity, owner, selected, { ReplyDatabaseHistory.replyAccount(live) })
                                    if (!ownsDrawer()) return@launch
                                    selectedIdentity = applied.identity
                                    composition.relationship = applied.identity.relationship
                                    composition.customRelationship = applied.identity.customText
                                    composition.background = applied.background
                                    identityBackground.setText(applied.background.text)
                                    customRole.setText(applied.identity.customText)
                                    failed = false; renderParts()
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { if (ownsDrawer()) toast("角色应用失败，请重新选择") }
                                finally { applyingRole = false; if (ownsDrawer()) controls(false) }
                            }
                        }
                        return@setOnMenuItemClickListener true
                    }
                    if (!busy && ownsDrawer()) {
                        val wasEditingCustomRole = customRole.hasFocus()
                        composition.relationship = ReplyRelationship.entries[item.itemId]
                        selectedIdentity = ReplyIdentitySetting(composition.relationship)
                        composition.customRelationship = ""
                        composition.background = ContactBackground()
                        customRole.setText("")
                        identityBackground.setText("")
                        roleExpanded = composition.relationship == ReplyRelationship.OTHER
                        instructionExpanded = false
                        saveIdentity()
                        failed = false
                        renderParts(); controls(false)
                        val keyboard = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        if (composition.relationship == ReplyRelationship.OTHER) {
                            customRole.requestFocus(); customRole.setSelection(customRole.text.length)
                            customRole.post { if (window === dialog && customRole.isShown && customRole.hasFocus())
                                keyboard?.showSoftInput(customRole, InputMethodManager.SHOW_IMPLICIT) }
                        } else if (wasEditingCustomRole) {
                            customRole.clearFocus(); body.requestFocus(); keyboard?.hideSoftInputFromWindow(customRole.windowToken, 0)
                        }
                    }
                    true
                }
            }.show()
        }
        var roleMenuLoading = false
        rolePicker.setOnClickListener {
            if (!roleMenuLoading && !busy && ownsDrawer()) {
                roleMenuLoading = true
                scope.launch {
                    try {
                        val saved = if (group || owner.key == null) emptyList() else ReplyIdentityBridge.roles(activity)
                        if (ownsDrawer() && !busy) showRoleMenu(saved, libraryAvailable = !group && owner.key != null)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { if (ownsDrawer()) { toast("角色库暂时不可用，请稍后重试"); showRoleMenu(emptyList()) } }
                    finally { roleMenuLoading = false }
                }
            }
        }
        invalidateRequest = { reference.cancel(); state.text = "请重新配置回复模型"; failed = true; controls(false) }
        fun remember() {
            if (ownsDrawer()) composition.result?.let { history.remember(it, owner.historyScope) }
        }
        fun prepareHistory(limit: Int) {
            val prepAt = android.os.SystemClock.elapsedRealtime()
            fun mark(stage: String) { MoodLog.i("REPLY_TIMING stage=prep_" + stage + " cost=" + (android.os.SystemClock.elapsedRealtime() - prepAt) + "ms") }
            if (busy) return
            if (!ownsDrawer()) { window.dismiss(); return }
            if (!canGenerate()) { configure(); return }
            historyJob?.cancel()
            NativeVoiceBridge.retryFailures()
            composition.historyLimit = limit
            val ticket = reference.begin(limit)
            snapshot = null; stale.visibility = View.GONE
            failed = false
            state.text = ""; state.setTextColor(theme.muted)
            renderParts(); controls(false)
            // 默认目标模式下不能拿打开面板时记住的 targetFocusId 去读：新消息进来后它会一直指向旧的那条，
            // 重新读取也就永远显示同一条。这里重新按「对方最后一条」算一次目标。
            val loaded = runCatching {
                MessageSniffer.replyContext(requireBottom = false, focusId = if (autoTarget) 0 else targetFocusId)
            }.getOrElse {
                reference.fail(ticket); state.text = it.message ?: "读取聊天失败，请重试"; controls(false); return
            }.let { ctx ->
                if (autoTarget) ctx.messages.lastOrNull { it.speaker != "我" && it.id > 0 }?.let { target ->
                    targetFocusId = target.id
                    MoodLog.i("REPLY_AUTO_TARGET refresh ready=${target.id > 0} placeholder=${target.placeholder}")
                }
                targetImage?.let { if (ctx.targetImageDataUri == null) ctx.copy(targetImageDataUri = it) else ctx } ?: ctx
            }
            mark("context")
            if (loaded.talker != selectedTalker) { reference.cancel(); window.dismiss(); return }
            historyJob = scope.launch {
                try {
                    if (!verifyAccount()) { window.dismiss(); return@launch }
                    // 面板打开时的取图移到后台：主线程只碰 View 拿 bitmap，压缩和编码在 IO。
                    val withImage = if (loaded.targetImageDataUri != null || loaded.targetMedia == null || loaded.focusMessageId <= 0) loaded
                        else runCatching { MessageSniffer.captureMediaImage(loaded.focusMessageId) }.getOrNull()
                            ?.let { loaded.copy(targetImageDataUri = it) } ?: loaded
                    val pending = ReplyDatabaseHistory.load(withImage, limit, owner.account)
                    mark("load")
                    // 分母只用真正要调原生转写的条数：已有微信转写的不占额度，超过上限的也不转。
                    var voiceCount = 0
                    var voiceIndex = 0
                    val current = VoicePreparation.reply(pending,
                        existing = { source -> NativeVoiceBridge.existingText(source) },
                        onPlan = { voiceCount = it }) { source ->
                        ensureActive()
                        state.text = if (voiceCount > 0) "正在转写语音 ${++voiceIndex}/$voiceCount…"
                            else "正在转写语音…"
                        val voiceAt = android.os.SystemClock.elapsedRealtime()
                        try {
                            NativeVoiceBridge.transcribe(source) {
                                ownsDrawer() && ModulePrefs.canGenerateReply && reference.isCurrent(ticket)
                            }
                        } finally {
                            MoodLog.i("REPLY_TIMING stage=voice cost=" + (android.os.SystemClock.elapsedRealtime() - voiceAt) + "ms")
                        }
                    }
                    ensureActive()
                    if (!ownsDrawer() || !ModulePrefs.canGenerateReply) return@launch
                    if (!verifyAccount()) { window.dismiss(); return@launch }
                    if (current.messages.isEmpty() ||
                        current.messages.all { it.voiceState == VoiceState.FAILED || it.voiceState == VoiceState.SKIPPED }) {
                        reference.fail(ticket)
                        state.text = "尚无可用正文，语音可能未能转写，请在条数菜单中重试"
                        return@launch
                    }
                    if (!reference.complete(ticket, current.copy(facts = facts), MessageSniffer.currentReplyTalker())) return@launch
                    snapshot = current
                    targetLine.text = replyTargetText(current)
                    state.text = when {
                        current.messages.any { it.voiceState == VoiceState.FAILED } ->
                            "${current.messages.count { it.voiceState == VoiceState.FAILED }} 条语音未能转写，将明确标注缺失；可在条数菜单中重试。"
                        current.messages.any { it.voiceState == VoiceState.SKIPPED } ->
                            "${current.messages.count { it.voiceState == VoiceState.SKIPPED }} 条语音本轮未转写（已到上限），切到更大条数可再读一次。"
                        current.targetMedia != null && current.targetImageDataUri == null ->
                            "对方这条${current.targetMedia}没有取到内容，回复会只按上下文判断。"
                        current.historyFailure != null -> "仅读取到页面 ${current.messages.size} 条。历史暂不可用，可切换条数重试。"
                        current.messages.size < limit -> "本次读取到 ${current.messages.size} 条消息（含语音转写），将按实际内容回复。"
                        else -> ""
                    }
                    updateNotice()
                    mark("done")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (reference.isCurrent(ticket) && ownsDrawer()) state.text = "历史读取失败，请重试"
                } finally {
                    // 无论界面是否还开着都要清 loading，否则 reference.loading 卡住后，
                    // 生成、找话题、重新读取都会被开头的 loading 判断直接挡掉。
                    reference.fail(ticket)
                    if (reference.isCurrent(ticket) && ownsDrawer()) controls(busy)
                }
            }
        }
        var limitSaveRevision = 0L
        var limitDialog: AlertDialog? = null
        fun selectHistory(limit: Int) {
            if (busy || !ownsDrawer()) return
            composition.historyLimit = limit
            historyJob?.cancel()
            reference.cancel(); snapshot = null; stale.visibility = View.GONE
            failed = false; state.text = ""; renderParts(); controls(false)
            val revision = ++limitSaveRevision
            if (owner.account == null) toast("账号尚未确认，本次条数不会保存")
            else ReplyLimitBridge.save(activity, owner, limit, { ReplyDatabaseHistory.replyAccount(live) }) { saved ->
                activity.runOnUiThread {
                    if (ownsDrawer() && revision == limitSaveRevision && !saved) toast("条数保存失败，请重新选择后重试")
                }
            }
            prepareHistory(limit)
        }
        fun customHistory() {
            if (busy || !ownsDrawer()) return
            val input = EditText(activity).apply {
                // Numeric keyboard, but preserve pasted decimals/signs so validation rejects them,
                // rather than silently turning e.g. 1.5 into 15 through DigitsKeyListener.
                inputType = InputType.TYPE_CLASS_TEXT
                setRawInputType(InputType.TYPE_CLASS_NUMBER)
                setSingleLine(true); isSaveEnabled = false
                hint = "1～100"; contentDescription = "自定义参考消息条数，1 到 100"
                setText(composition.historyLimit.toString()); selectAll()
            }
            limitDialog?.dismiss()
            val chooser = AlertDialog.Builder(activity).setTitle("自定义参考条数")
                .setView(input).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
            limitDialog = chooser
            chooser.setOnDismissListener { if (limitDialog === chooser) limitDialog = null }
            chooser.show()
            chooser.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!ownsDrawer() || busy) { chooser.dismiss(); return@setOnClickListener }
                val value = try { requireNotNull(ReplyHistoryLimit.parse(input.text.toString())) }
                    catch (error: IllegalArgumentException) { input.error = error.message; return@setOnClickListener }
                selectHistory(value)
                chooser.dismiss()
            }
            input.requestFocus()
            chooser.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        historyPicker.setOnClickListener {
            PopupMenu(activity, historyPicker).apply {
                ReplyHistorySelection.OPTIONS.forEach { limit ->
                    menu.add(0, limit, limit, "最近 $limit 条消息").isChecked = limit == composition.historyLimit
                }
                menu.add(0, -1, 101, "自定义…").isChecked = composition.historyLimit !in ReplyHistorySelection.OPTIONS
                menu.setGroupCheckable(0, true, true)
                reference.context?.let { menu.add(1, 1, 102, "查看参考内容（${it.messages.size} 条）") }
                menu.add(1, 2, 103, "重新读取聊天记录")
                setOnMenuItemClickListener { item ->
                    if (ownsDrawer() && !busy) when {
                        item.groupId == 1 && item.itemId == 1 -> reference.context?.let(::showEvidence)
                        item.groupId == 1 && item.itemId == 2 -> prepareHistory(composition.historyLimit)
                        item.itemId == -1 -> customHistory()
                        else -> selectHistory(item.itemId)
                    }
                    true
                }
            }.show()
        }
        fun generate(direction: String = "", findTopics: Boolean = false) {
            if (busy || reference.loading) return
            if (hasUnsavedIdentityBackground()) { toast("请先保存身份和背景"); return }
            if (!composition.hasValidRelationship) { customRole.requestFocus(); toast("请先填写对方身份"); return }
            if (!ownsDrawer()) { window.dismiss(); return }
            if (!canGenerate()) { configure(); return }
            val current = reference.context?.let { ctx ->
                // 历史重建后 focus 可能丢，按打开抽屉时记住的目标补回来。
                ctx.copy(background = composition.background, facts = facts,
                    focusMessageId = if (ctx.focusMessageId > 0) ctx.focusMessageId else targetFocusId,
                    targetImageDataUri = ctx.targetImageDataUri ?: targetImage)
            } ?: run { prepareHistory(composition.historyLimit); return }
            targetLine.text = replyTargetText(current)
            val latest = MessageSniffer.replyBoundary()
            if (latest != null && latest != current.latestLoadedId && current.messages.none { it.id == latest }) {
                prepareHistory(composition.historyLimit); toast("聊天有更新，读取完成后再点生成"); return
            }
            val input = editor() ?: run { toast("请先切换到文字输入"); return }
            // Read existing draft for context only. Never write to the host's chat editor.
            val baselineDraft = input.text.toString()
            val relationship = composition.relationship
            val customRelationship = composition.activeCustomRelationship
            val notes = instruction.text.toString()
            val time = if (findTopics) TopicCalendar.current() else null
            val requestedTopicKey = time?.let { topicKey(current, baselineDraft, it.date) }
            if (requestedTopicKey != null && composition.nextTopic(requestedTopicKey)) {
                failed = false; state.text = ""; reason.visibility = View.GONE
                reasonToggle.text = "为什么这样回 ▾"
                renderParts(); revealReply(); remember(); return
            }
            job?.cancel()
            // 生成前清掉上一次的检查卡片，避免和新结果混在一起。
            draftPanel.visibility = View.GONE
            val config = ModulePrefs.replySettings()
            val ticket = session.begin(current.talker, current.fingerprint)
            val previous = if (composition.result?.topics == null) composition.previousText else ""
            val previousTopics = composition.result?.takeIf { composition.canUse }?.topics?.items.orEmpty()
            state.text = ""; state.setTextColor(theme.muted)
            failed = false; findingTopics = findTopics
            collapseEditors()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(instruction.windowToken, 0)
            body.requestFocus()
            controls(true)
            job = scope.launch {
                try {
                    if (!verifyAccount()) { window.dismiss(); return@launch }
                    // 长按图片时先让视觉模型看一句描述（同图只调用一次），
                    // 拿到就随提示词带上；失败不影响生成，退回「没取到内容」的措辞。
                    val digested = if (current.targetImageDataUri == null || current.targetImageDigest != null) current
                    else {
                        state.text = "正在看这张${current.targetMedia ?: "图片"}…"
                        state.setTextColor(theme.muted)
                        ImageDigest.describe(activity, config, current.talker, current.focusMessageId,
                            current.targetImageDataUri!!)
                            ?.let { current.copy(targetImageDigest = it) } ?: current
                    }
                    // 这张图的描述顺手进档案：截图里写的时间地点，不该只在这一次生成里用一次就丢。
                    digested.targetImageDigest
                        ?.takeIf { it.isNotBlank() && it != current.targetImageDigest }
                        ?.let { fresh ->
                            DigestFactQueue.offer(activity, owner.account, current.talker, "图片描述", fresh,
                                captured = current)
                        }
                    val knowledge = withContext(Dispatchers.IO) {
                        ReplyKnowledge.loadFor(activity, relationship,
                            current.messages.joinToString("\n") { it.text } + "\n" + baselineDraft + "\n" + notes)
                    }
                    // Upload only the prepared evidence, after rechecking configuration, conversation and settings.
                    ensureActive()
                    if (!session.accepts(ticket, MessageSniffer.currentReplyTalker()) || !ownsDrawer() || !ModulePrefs.canGenerateReply || current.background != composition.background) return@launch
                    val beforeSend = ModulePrefs.replySettings()
                    if (beforeSend.endpoint != config.endpoint || beforeSend.model != config.model || beforeSend.apiKey != config.apiKey) {
                        state.text = "配置已改变，请重试"; return@launch
                    }
                    val client = ReplyHttpClient()
                    // 走 TopicRequest 与关系温度共用同一条请求准备：参数原样传，行为不变。
                    val topics = if (findTopics) TopicRequest.find(activity, config, current, baselineDraft, notes,
                        relationship, customRelationship, knowledge, requireNotNull(time), previousTopics, client)
                        else null
                    // 一次性返回：用户不需要逐条蹦出的过程，关掉流式以后也少一次参数兼容重试。
                    state.text = if (findTopics) "正在找话题…" else "正在生成回复…"
                    state.setTextColor(theme.muted)
                    val suggestion = if (findTopics) null else client.generate(config, digested, baselineDraft, direction,
                        knowledge, previous, focusId, relationship, customRelationship)
                    val activeConfig = ModulePrefs.replySettings()
                    if (!session.accepts(ticket, MessageSniffer.currentReplyTalker()) || !ownsDrawer() || !ModulePrefs.canGenerateReply) return@launch
                    if (!verifyAccount()) { window.dismiss(); return@launch }
                    if (activeConfig.endpoint != config.endpoint || activeConfig.model != config.model || activeConfig.apiKey != config.apiKey) {
                        state.text = "配置已改变，请重试"; return@launch
                    }
                    val accepted = if (topics != null) composition.acceptTopics(current, topics, requireNotNull(requestedTopicKey), focusId)
                        else composition.accept(current, requireNotNull(suggestion), notes, focusId, relationship, customRelationship)
                    if (!accepted) return@launch
                    snapshot = current
                    reason.visibility = View.GONE; reasonToggle.text = "为什么这样回 ▾"; renderParts()
                    state.text = ""
                    revealReply()
                    remember(); updateNotice()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (session.accepts(ticket, MessageSniffer.currentReplyTalker()) && ownsDrawer()) {
                        failed = true
                        state.text = "${if (findTopics) "找话题" else "生成"}失败${if (composition.result == null) "" else " · 保留上次建议"}"
                        state.setTextColor(theme.error)
                        toast(e.message ?: "暂时无法生成，请重试")
                    }
                } finally {
                    if (session.accepts(ticket, talker) && ownsDrawer()) controls(false)
                }
            }
        }
        generateButton.setOnClickListener {
            generate(instruction.text.toString().ifBlank { if (composition.canUse) "换一种自然表达，不要重复上一组建议" else "" })
        }
        topicButton.setOnClickListener { generate(findTopics = true) }
        instruction.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { controls(busy) }
        })
        shorter.setOnClickListener { generate(instruction.text.toString() + "\n保持原意，更简短一点") }
        stale.setOnClickListener { prepareHistory(composition.historyLimit) }
        renderParts(); controls(false)
        window.setContentView(body)
        window.setOnDismissListener {
            limitDialog?.dismiss()
            if (dialog !== window) return@setOnDismissListener
            remember(); job?.cancel(); historyJob?.cancel(); simJob?.cancel(); reference.cancel(); session.cancel(); simSession.cancel()
            progress.showLoading(null)
            if (dialog === window) { dialog = null; newMessageNotice = null; snapshot = null; invalidateRequest = null }
        }
        window.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.32f)
        }
        window.show()
        // 开关打开时按条件静默整理一次：距上次超过 3 天，或这段时间新增了 30 条以上消息。
        val factsKey = owner.key
        val factsEnabled = RelationFactBridge.enabled(activity)
        // 这里以前什么都不记，出问题时只能看到「档案没更新」。两个前置条件都写进日志。
        MoodLog.i("RELATION_FACT_AUTO enabled=$factsEnabled key=${factsKey != null}")
        if (factsEnabled && factsKey != null) {
            scope.launch {
                val meta = withContext(Dispatchers.IO) { RelationFactBridge.meta(activity, factsKey) }
                val gap = System.currentTimeMillis() - meta.lastAt
                // 页面只加载十几条，拿它数增量永远到不了 30 条；与进聊天那处统一，按库里的真实新增算。
                val window = withContext(Dispatchers.IO) {
                    runCatching { ReplyDatabaseHistory.load(live, ReplyContext.MAX_MESSAGES) }
                        .getOrNull()?.messages
                }.orEmpty()
                val fresh = window.count { it.id > meta.lastMessageId }
                // 之前先判「不到 24 小时就返回」，导致新增几十条也永远不抽；两个条件应当并列。
                val due = gap > 3 * 24 * 3600_000L || fresh >= 30
                MoodLog.i("RELATION_FACT_AUTO meta_last=${meta.lastAt} gap_hours=${gap / 3600_000L} " +
                    "fresh=$fresh window=${window.size} due=$due")
                if (due) refreshFacts(true)
            }
        }
        fitWindow()
        body.requestFocus(); updateNotice()
        if (reference.context == null && canGenerate()) prepareHistory(composition.historyLimit)
        return true
    }
    private fun canGenerate() = ModulePrefs.canGenerateReply
    /**
     * 「还没连模型」的引导。
     *
     * 这里保留系统弹窗：PanelKit.info() 只给一个「关闭」按钮、picker() 只给选项行，
     * 而这段要「一句说明 + 去配置 / 稍后两个按钮」，自绘要为此重做一套双按钮外壳，
     * 收益不抵风险。正文很短，深浅色差异只影响这一处的一次性引导。
     * 长正文的依据弹窗（[showEvidence]）已经换成自绘的了。
     */
    private fun configure() {
        AlertDialog.Builder(activity).setTitle("先连接回复模型")
            .setMessage("打开言外「回复」，进入模型配置，保存回复模型后即可手动生成。")
            .setPositiveButton("去配置") { _, _ -> openSettings() }.setNegativeButton("稍后", null).show()
    }
    private fun contextSummary(context: ReplyContext): String {
        val first = context.messages.firstOrNull()?.time ?: 0
        val last = context.messages.lastOrNull()?.time ?: 0
        fun time(value: Long) = if (value <= 0) "时间未知" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(value))
        val voices = context.messages.count { it.voiceState == VoiceState.READY }
        val missing = context.messages.count { it.voiceState == VoiceState.FAILED }
        val skipped = context.messages.count { it.voiceState == VoiceState.SKIPPED }
        return "${time(first)} — ${time(last)} · 含 $voices 条语音转写${if (missing > 0) " · $missing 条转写失败" else ""}" +
            (if (skipped > 0) " · $skipped 条未转写" else "") + "${if (context.trimmed) " · 已截取" else ""}" +
            (context.historyFailure?.let { "\n$it" } ?: "")
    }
    private fun showEvidence(context: ReplyContext) {
        val source = if (context.source == ReplyContextSource.LOCAL_HISTORY) "本机聊天历史 · 选择最近 ${context.requestedMessages} 条，实际 ${context.messages.size} 条"
            else "仅页面已加载片段 · 跳过 ${context.omittedMedia} 条其他媒体"
        val header = "$source\n${contextSummary(context)}\n\n"
        // 图片和表情的描述缓存在 SharedPreferences 里，读盘放后台，别卡住聊天页。
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                context.messages.joinToString("\n\n") { message ->
                    "${message.speaker} · ${ReplyProtocol.formatTime(message.time)}" +
                        "${if (message.voice != null) " · 语音转写" else ""}\n${evidenceText(context, message)}"
                }
            }
            // 读缓存期间抽屉已关或换了页面就不再弹，避免旧聊天的依据挡住当前聊天。
            if (activity.isFinishing || activity.isDestroyed || dialog?.isShowing != true) return@launch
            // 依据正文可能上万字：交给 PanelKit 的自绘面板，能滚动、深浅色跟微信一致，
            // 只有一个「关闭」按钮，不丢交互；旧的那一个先收掉，同时避免留两个窗口。
            kit.dismissAll()
            kit.info("本次参考的聊天", header + body)
        }
    }

    /**
     * 依据弹窗里的正文：图片、表情这类占位不能只写【图片】，要让人知道这次到底有没有取到内容。
     * 有缓存描述就显示描述，没有就明确写「本次没取到内容」。
     */
    private fun evidenceText(context: ReplyContext, message: ReplyMessage): String {
        if (!message.placeholder) return message.text
        // 只有图片和表情会去取图和生成描述；名片、视频、位置本来就不取，别写成「没取到」。
        if (message.type != 3 && message.type != 47) return message.text
        val label = ReplyContext.mediaLabel(message.type)
        val digest = runCatching { ImageDigest.cached(activity, context.talker, message.id) }.getOrNull()
        return if (!digest.isNullOrBlank()) "【$label：$digest】" else "【$label（本次没取到内容）】"
    }
    private fun openSettings() = runCatching {
        activity.startActivity(Intent().setComponent(ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity"))
            .putExtra("reply_tab", true))
    }.onFailure { toast("请从桌面打开言外，进入回复建议") }
    fun hide() {
        plusEntry.clear()
        openingJob?.cancel(); ownerEpoch = null
        // 口吻画像、依据弹窗都是独立窗口，抽屉关掉后不能留在屏幕上。
        styleDialog?.dismiss(); styleDialog = null
        // 依据弹窗交给 PanelKit 管：抽屉收起时一起收掉，不留一个脱离抽屉的窗口。
        kit.dismissAll()
        factsActionRef = null
        dialog?.dismiss(); job?.cancel(); historyJob?.cancel(); session.cancel()
        talker = null
        // 目标（尤其是长按的那张图）只属于打开它的聊天，切走后不能留给下一个群。
        targetFocusId = 0
        targetImage = null
        draftTargetMedia = null
        draftText = ""
        draftLastIncoming = null
    }
    fun dispose() { hide(); scope.cancel() }
    private fun views(root: View): List<View> = buildList {
        fun visit(v: View, depth: Int) {
            if (depth > 40 || size > 4000 || v.visibility != View.VISIBLE) return
            add(v); if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i), depth + 1)
        }
        visit(root, 0)
    }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
