package dev.jev.wechatmood

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import dev.jev.wechatmood.core.EmotionSettings
import dev.jev.wechatmood.core.IntentSettings
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.core.SettingsProvider
import dev.jev.wechatmood.core.UsageMeter
import dev.jev.wechatmood.databinding.ActivityMainBinding
import dev.jev.wechatmood.reply.ReplySettings
import dev.jev.wechatmood.ui.AnalysisConnectionUi
import dev.jev.wechatmood.ui.ProbeState
import dev.jev.wechatmood.ui.SetupPresenter
import dev.jev.wechatmood.ui.SettingsStatus
import dev.jev.wechatmood.ui.StatusTone
import dev.jev.wechatmood.updates.UpdateNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var updateNotice: UpdateNotice
    private var syncingSwitches = false
    private val pagePositions = mutableMapOf<Int, Int>()
    private var currentPage = R.id.tabHome
    private var modelsReturnPage = R.id.tabHome
    private var roleManagerUi: dev.jev.wechatmood.ui.ReplyRoleManagerUi? = null
    private var connectionUi: AnalysisConnectionUi? = null

    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (currentPage == R.id.modelsPanel) showPage(modelsReturnPage)
            else binding.navigation.selectedItemId = R.id.tabHome
        }
    }
    private val refreshAfterSettings = Runnable { if (!isFinishing && !isDestroyed) refresh() }
    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Saving one connection updates several keys. Render once after the entire edit.
        binding.root.removeCallbacks(refreshAfterSettings)
        binding.root.post(refreshAfterSettings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API Key 展开成明文时不允许截图/录屏/最近任务缩略图留底。
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = getString(R.string.app_title_version, BuildConfig.VERSION_NAME)
        onBackPressedDispatcher.addCallback(this, backToHome)
        dev.jev.wechatmood.ui.ReplySettingsUi(this, binding.replySettings, uiScope, ::openHelp)
        binding.navigation.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        val selectedTab = savedInstanceState?.getInt("selected_tab")
            ?.takeIf { it in listOf(R.id.tabHome, R.id.tabEmotion, R.id.tabReply, R.id.tabAbout) }
            ?: if (intent.getBooleanExtra("reply_tab", false)) R.id.tabReply else R.id.tabHome
        binding.navigation.selectedItemId = selectedTab
        bindDisplaySettings()
        binding.toggleContact.setOnClickListener {
            val open = binding.contactPanel.visibility != View.VISIBLE
            binding.contactPanel.visibility = if (open) View.VISIBLE else View.GONE
            binding.toggleContact.text = if (open) "联系作者  ▴" else "联系作者  ▾"
        }
        binding.toggleSource.setOnClickListener {
            val open = binding.sourcePanel.visibility != View.VISIBLE
            binding.sourcePanel.visibility = if (open) View.VISIBLE else View.GONE
            binding.toggleSource.text = if (open) "免费与开源  ▴" else "免费与开源  ▾"
        }
        binding.knowledgeSource.setOnClickListener { openHelp(dev.jev.wechatmood.reply.ReplyKnowledge.SOURCE_URL) }
        binding.knowledgeLicense.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("狗头军师 · MIT License")
                .setMessage(assets.open("goutoujunshi/LICENSE").bufferedReader().use { it.readText() })
                .setPositiveButton("关闭", null).show()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
        MoodLog.init(this)
        MoodLog.i("ENVIRONMENT\n${Diagnostics.environment(this)}")
        // Makes the settings provider visible to WeChat on Android 11+.
        // The provider validates the caller UID before sharing settings with WeChat.
        runCatching {
            grantUriPermission("com.tencent.mm", SettingsProvider.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onSuccess { MoodLog.i("BRIDGE_VISIBILITY_GRANTED 微信读取授权已授予") }
            .onFailure { MoodLog.e("BRIDGE_VISIBILITY_GRANT_FAILED", it) }
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        prefs.all.filterKeys { it == ModulePrefs.KEY_API_KEY || it.endsWith("_key") }
            .values.filterIsInstance<String>().forEach(MoodLog::protect)
        ModulePrefs.init(this)
        SettingsProvider.publish(this)
        roleManagerUi = dev.jev.wechatmood.ui.ReplyRoleManagerUi(this, binding.roleManager, uiScope, ::scrollTo)
        binding.roleManager.toggleRoles.setOnClickListener {
            val open = binding.roleManager.roleListGroup.visibility != View.VISIBLE
            binding.roleManager.roleListGroup.visibility = if (open) View.VISIBLE else View.GONE
            binding.roleManager.toggleRoles.text = if (open) "收起" else "展开"
        }
        if (currentPage == R.id.tabReply) roleManagerUi?.onShown()
        connectionUi = AnalysisConnectionUi(this, binding.connectionSettings, uiScope, ::openHelp) { renderOverview() }
        if (currentPage == R.id.modelsPanel) connectionUi?.onShown()
        binding.entryAnalysisConnection.setOnClickListener { openModels() }
        binding.entryReplyConnection.setOnClickListener { openModels() }
        binding.connectionSettings.analysisFormSummary.setOnClickListener { openModels() }
        binding.replySettings.replyFormSummary.setOnClickListener { openModels() }
        binding.connectionSettings.toggleAnalysisForm.setOnClickListener {
            toggleForm(binding.connectionSettings.analysisForm, binding.connectionSettings.toggleAnalysisForm)
        }
        binding.replySettings.toggleReplyForm.setOnClickListener {
            toggleForm(binding.replySettings.replyFormGroup, binding.replySettings.toggleReplyForm)
        }
        binding.toolbar.setNavigationOnClickListener { backToHome.handleOnBackPressed() }
        if (savedInstanceState?.getBoolean("models_open") == true) {
            modelsReturnPage = selectedTab
            showPage(R.id.modelsPanel)
        }
        binding.switchExplore.isChecked = prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false)
        binding.switchExplore.setOnCheckedChangeListener { _, value ->
            if (!syncingSwitches) {
                save(ModulePrefs.KEY_EXPLORE, value)
                Toast.makeText(this, "重新启动微信后生效", Toast.LENGTH_SHORT).show()
            }
        }
        binding.intentSettings.switchChatDefault.isChecked = prefs.getBoolean(ModulePrefs.KEY_ANALYSIS_DEFAULT, false)
        binding.intentSettings.switchChatDefault.setOnCheckedChangeListener { _, value ->
            if (!syncingSwitches) {
                save(ModulePrefs.KEY_ANALYSIS_DEFAULT, value)
                Toast.makeText(this,
                    if (value) "所有聊天都会自动分析；微信加载新设置后生效" else "已关闭自动分析",
                    Toast.LENGTH_SHORT).show()
            }
        }
        val factsSwitch = binding.switchRelationFacts
        factsSwitch.isChecked = prefs.getBoolean(ModulePrefs.KEY_RELATION_FACTS, true)
        factsSwitch.setOnCheckedChangeListener { _, value ->
            if (!syncingSwitches) {
                save(ModulePrefs.KEY_RELATION_FACTS, value)
                Toast.makeText(this,
                    if (value) "下次打开「帮我回」时整理，每个联系人每天最多一次" else "已关闭自动整理",
                    Toast.LENGTH_SHORT).show()
            }
        }
        binding.buttonClearFacts.setOnClickListener {
            dev.jev.wechatmood.core.ReplyIdentityProvider.clearAllFacts(this)
            // 广播在 Android 9–13 上收不到发送方、微信没运行时也收不到，
            // 所以请求已经落盘，等微信进程连上设置服务后执行并回执。
            binding.factsStatus.text = "已记录清空请求；微信运行中会立即执行，没运行则下次启动时执行"
        }
        binding.buttonDebug.setOnClickListener {
            val open = binding.debugPanel.visibility != View.VISIBLE
            binding.debugPanel.visibility = if (open) View.VISIBLE else View.GONE
            binding.buttonDebug.text = if (open) "收起详细排查信息" else "查看详细排查信息"
            if (open) binding.textLog.text = Diagnostics.collect(this)
        }
        binding.buttonSetupGuide.setOnClickListener {
            val open = binding.setupGuidePanel.visibility != View.VISIBLE
            showSetupGuide(open)
            if (open) scrollTo(binding.setupGuidePanel)
        }
        binding.buttonOpenWechat.setOnClickListener { openWechat() }
        binding.buttonRefreshLog.setOnClickListener { refresh() }
        binding.buttonCopyLog.setOnClickListener { Diagnostics.copy(this) }
        binding.buttonExportLog.setOnClickListener { Diagnostics.export(this) }
        binding.buttonOpenSource.setOnClickListener { openHelp("https://github.com/YIRC99/yanwai") }
        binding.buttonCopyAuthor.setOnClickListener {
            runCatching {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("作者微信号", "YIRC99"))
            }.onSuccess { Toast.makeText(this, "已复制微信号 YIRC99", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "复制失败，请长按上方微信号手动复制", Toast.LENGTH_LONG).show() }
        }
        updateNotice = UpdateNotice(this, binding, uiScope, ::openHelp)
        refresh()
    }

    private fun save(key: String, value: Boolean) {
        if (!SettingsProvider.save(this) { putBoolean(key, value) }) {
            Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
        }
        ModulePrefs.reload(force = true)
        refresh()
    }

    private fun openHelp(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "未找到浏览器，请先安装浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (currentPage == R.id.tabReply) roleManagerUi?.onShown()
        // Lifecycle dispatch finishes after onResume; cached notices also need RESUMED.
        binding.root.post {
            if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                updateNotice.onResume()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(stateListener)
        getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(stateListener)
    }

    override fun onStop() {
        binding.root.removeCallbacks(refreshAfterSettings)
        getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(stateListener)
        getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(stateListener)
        super.onStop()
    }

    private fun refresh() {
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        syncingSwitches = true
        binding.switchExplore.isChecked = prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false)
        binding.switchRelationFacts.isChecked = prefs.getBoolean(ModulePrefs.KEY_RELATION_FACTS, true)
        binding.intentSettings.switchChatDefault.isChecked = prefs.getBoolean(ModulePrefs.KEY_ANALYSIS_DEFAULT, false)
        syncingSwitches = false
        val wechat = runCatching {
            @Suppress("DEPRECATION")
            "微信 ${packageManager.getPackageInfo("com.tencent.mm", 0).versionName}"
        }.getOrDefault("未检测到微信")
        val runtime = getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE)
        val last = runtime.getLong("last_seen", 0)
        val whenText = if (last <= 0) "还没有收到微信的运行记录，可查看启用指南。"
        else "上次运行：" + formatLastRun(last)
        val status = runtime.getString("status", "").orEmpty()
        binding.textFrameworkStatus.text = listOf(wechat, whenText, status).filter { it.isNotBlank() }.joinToString("\n")
        val host = SetupPresenter.resolve(false, false, ProbeState.UNTESTED, last, System.currentTimeMillis())
        binding.textHostBadge.text = host.hostLabel
        tintStatus(binding.textHostBadge, host.hostTone)
        if (binding.debugPanel.visibility == View.VISIBLE) binding.textLog.text = Diagnostics.collect(this)
        renderOverview()
    }

    /** 两件事：分析连接有没有、回复连接有没有。首页和各自页面都只显示这一句。 */
    private fun formatLastRun(last: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = last }
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(last))
        fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay(then, now)) return "今天 $hm"
        val yesterday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(then, yesterday)) return "昨天 $hm"
        return java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault()).format(java.util.Date(last))
    }

    private fun renderOverview() {
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        val replyKey = prefs.getString(ReplySettings.KEY_API_KEY, "").orEmpty()
        val replyModel = prefs.getString(ReplySettings.KEY_MODEL, "").orEmpty()
        val replyReady = replyKey.isNotBlank()
        SettingsStatus.show(binding.replySummary,
            if (replyReady) "当前模型：$replyModel" else "还没有配置回复连接",
            if (replyReady) R.color.status_neutral else R.color.status_warning)

        val source = prefs.getString(EmotionSettings.KEY_SOURCE, null)
        val reuse = prefs.getString(EmotionSettings.KEY_REUSE_REPLY, null) == "true"
        val jevReady = !prefs.getString(ModulePrefs.KEY_API_KEY, "").isNullOrBlank()
        val intentReady = !prefs.getString(IntentSettings.KEY_API_KEY, "").isNullOrBlank()
        val analysisLabel = when {
            source == null || source == "jev" -> if (jevReady) "JEV 官方" else ""
            reuse -> if (replyReady && replyModel.isNotBlank()) "与回复相同（$replyModel）" else ""
            else -> if (intentReady) prefs.getString(IntentSettings.KEY_MODEL, "").orEmpty().ifBlank { "已配置" } else ""
        }
        val analysisReady = analysisLabel.isNotBlank()
        SettingsStatus.show(binding.intentSettings.connectionSummary,
            if (analysisReady) "已配置：$analysisLabel" else "还没有配置，回首页点「分析连接」。",
            if (analysisReady) R.color.status_neutral else R.color.status_warning)
        SettingsStatus.show(binding.textAnalysisConnection,
            "分析连接：${if (analysisReady) analysisLabel else "未配置"}",
            if (analysisReady) R.color.status_success else R.color.status_warning)
        SettingsStatus.show(binding.textReplyConnection,
            "回复连接：${if (replyReady && replyModel.isNotBlank()) replyModel else "未配置"}",
            if (replyReady) R.color.status_success else R.color.status_warning)
        binding.connectionSettings.analysisFormSummary.text =
            if (analysisReady) "$analysisLabel · 已保存" else "未配置"
        binding.replySettings.replyFormSummary.text =
            if (replyReady && replyModel.isNotBlank()) "$replyModel · 已保存" else "未配置"
        binding.usageSummary.text = UsageMeter.summary(this)
    }

    private fun showPage(id: Int) {
        pagePositions[currentPage] = binding.pageScroll.scrollY
        currentFocus?.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        currentPage = id
        backToHome.isEnabled = id != R.id.tabHome
        if (id == R.id.tabReply) roleManagerUi?.onShown()
        if (id == R.id.modelsPanel) connectionUi?.onShown()
        binding.homePanel.visibility = if (id == R.id.tabHome) View.VISIBLE else View.GONE
        binding.emotionPanel.visibility = if (id == R.id.tabEmotion) View.VISIBLE else View.GONE
        binding.replyPanel.visibility = if (id == R.id.tabReply) View.VISIBLE else View.GONE
        binding.modelsPanel.visibility = if (id == R.id.modelsPanel) View.VISIBLE else View.GONE
        binding.navigation.visibility = if (id == R.id.modelsPanel) View.GONE else View.VISIBLE
        binding.toolbar.navigationIcon = if (id == R.id.modelsPanel) androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_back) else null
        binding.toolbar.navigationContentDescription = "返回上一页"
        binding.aboutPanel.visibility = if (id == R.id.tabAbout) View.VISIBLE else View.GONE
        binding.pageScroll.post { if (currentPage == id) binding.pageScroll.scrollTo(0, pagePositions[id] ?: 0) }
    }

    private fun bindDisplaySettings() {
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        listOf(binding.intentSettings.showIntent to dev.jev.wechatmood.core.CardDisplaySettings.KEY_INTENT,
            binding.intentSettings.showConcern to dev.jev.wechatmood.core.CardDisplaySettings.KEY_CONCERN,
            binding.intentSettings.showTone to dev.jev.wechatmood.core.CardDisplaySettings.KEY_TONE).forEach { (toggle, key) ->
            toggle.isChecked = prefs.getBoolean(key, true)
            ViewCompat.setStateDescription(toggle, if (toggle.isChecked) "显示" else "隐藏")
            var restoring = false
            toggle.setOnCheckedChangeListener { _, enabled ->
                if (!restoring) {
                    val saved = SettingsProvider.save(this) { putBoolean(key, enabled) }
                    if (saved) ModulePrefs.reload(force = true) else {
                        restoring = true; toggle.isChecked = prefs.getBoolean(key, true); restoring = false
                    }
                    ViewCompat.setStateDescription(toggle, if (toggle.isChecked) "显示" else "隐藏")
                    SettingsStatus.show(binding.intentSettings.cardDisplayStatus,
                        if (saved) "已保存，返回聊天即可查看。" else "保存失败，请重试。",
                        if (saved) R.color.status_success else R.color.status_error)
                }
            }
        }
    }

    private fun tintStatus(view: TextView, tone: StatusTone) {
        val color = when (tone) {
            StatusTone.SUCCESS -> R.color.status_success
            StatusTone.WARNING -> R.color.status_warning
            StatusTone.ERROR -> R.color.status_error
            StatusTone.NEUTRAL -> R.color.status_neutral
        }
        SettingsStatus.show(view, view.text.toString(), color)
    }

    private fun scrollTo(view: View) {
        binding.pageScroll.post {
            val content = binding.pageScroll.getChildAt(0) as android.view.ViewGroup
            val bounds = Rect()
            view.getDrawingRect(bounds)
            content.offsetDescendantRectToMyCoords(view, bounds)
            binding.pageScroll.smoothScrollTo(0, bounds.top)
        }
    }

    private fun toggleForm(form: View, button: TextView) {
        val open = form.visibility != View.VISIBLE
        form.visibility = if (open) View.VISIBLE else View.GONE
        button.text = if (open) "收起" else "修改"
    }

    private fun openModels() {
        if (currentPage != R.id.modelsPanel) modelsReturnPage = currentPage
        pagePositions[R.id.modelsPanel] = 0
        showPage(R.id.modelsPanel)
    }

    private fun showSetupGuide(open: Boolean) {
        binding.setupGuidePanel.visibility = if (open) View.VISIBLE else View.GONE
        binding.buttonSetupGuide.text = if (open) "收起使用指南" else "查看启用与使用指南"
    }

    private fun openWechat() {
        runCatching {
            startActivity(requireNotNull(packageManager.getLaunchIntentForPackage("com.tencent.mm")) { "未找到当前空间的微信" })
        }.onFailure {
            showSetupGuide(true)
            binding.navigation.selectedItemId = R.id.tabHome
            scrollTo(binding.setupGuidePanel)
            Toast.makeText(this, "无法打开微信，请检查是否安装在同一空间", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_tab", binding.navigation.selectedItemId)
        outState.putBoolean("models_open", currentPage == R.id.modelsPanel)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
