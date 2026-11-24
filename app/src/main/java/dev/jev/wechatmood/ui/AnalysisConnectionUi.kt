package dev.jev.wechatmood.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import dev.jev.wechatmood.R
import dev.jev.wechatmood.analysis.ChatAnalysis
import dev.jev.wechatmood.analysis.JevHttpClient
import dev.jev.wechatmood.analysis.LlmEmotionProtocol
import dev.jev.wechatmood.core.ApiProfiles
import dev.jev.wechatmood.core.ApiSettings
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ContextMessage
import dev.jev.wechatmood.core.EmotionSettings
import dev.jev.wechatmood.core.EmotionSource
import dev.jev.wechatmood.core.IntentProfiles
import dev.jev.wechatmood.core.IntentRoute
import dev.jev.wechatmood.core.IntentSettings
import dev.jev.wechatmood.core.JevProvider
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.SettingsProvider
import dev.jev.wechatmood.core.UsageKind
import dev.jev.wechatmood.databinding.ConnectionSettingsBinding
import dev.jev.wechatmood.reply.ReplyHttpClient
import dev.jev.wechatmood.reply.ReplyModelsClient
import dev.jev.wechatmood.reply.ReplyProvider
import dev.jev.wechatmood.reply.ReplySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 一条「分析连接」覆盖 JEV 与 OpenAI 兼容两类协议：渠道下拉决定保存到哪套凭据，
 * 情绪分析与意图解读始终共用这里选定的连接，不再单独让人做选择题。
 */
class AnalysisConnectionUi(
    private val activity: AppCompatActivity,
    private val binding: ConnectionSettingsBinding,
    private val scope: CoroutineScope,
    private val openUrl: (String) -> Unit,
    private val onChanged: () -> Unit,
) {
    private sealed class Option(val id: String, val label: String) {
        class Jev(val provider: JevProvider) : Option("jev:${provider.id}", provider.label)
        class Llm(val provider: ReplyProvider) : Option("llm:${provider.id}", provider.label)
        object Same : Option("same", "与回复连接相同（推荐）")
    }

    private class Draft(val endpoint: String, val key: String, val model: String)

    private val prefs = activity.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
    private val options: List<Option> = buildList {
        add(Option.Same)
        JevProvider.entries.forEach { add(Option.Jev(it)) }
        ReplyProvider.entries.forEach { add(Option.Llm(it)) }
    }
    private val drafts = mutableMapOf<String, Draft>()
    private var option: Option = Option.Same
    private var rendering = false
    private var busy = false
    private var modelOptions = emptyList<String>()

    init {
        option = currentOption()
        binding.analysisChannel.setSimpleItems(options.map { it.label }.toTypedArray())
        binding.analysisChannel.setOnItemClickListener { _, _, position, _ ->
            if (busy) return@setOnItemClickListener
            remember()
            option = options.getOrElse(position) { Option.Same }
            modelOptions = emptyList()
            render()
            dirty()
        }
        listOf(binding.endpoint, binding.apiKey, binding.model).forEach { field ->
            field.doAfterTextChanged {
                if (!rendering && !busy) {
                    dirty()
                    if (field != binding.model) setModels(emptyList())
                }
            }
        }
        binding.providerConsole.setOnClickListener { consoleUrl()?.let(openUrl) }
        binding.fetchModels.setOnClickListener { fetchModels() }
        binding.testConnection.setOnClickListener { test() }
        render()
    }

    /** 打开模型页时按已保存的配置重画，避免停留在上一次未保存的渠道。 */
    fun onShown() {
        if (busy) return
        option = currentOption()
        drafts.clear()
        render()
    }

    private fun currentOption(): Option {
        val emotion = EmotionSettings.load { prefs.getString(it, null) }
        val route = IntentRoute.resolve(prefs.getString(IntentSettings.KEY_ROUTE, null))
        if (emotion.source == EmotionSource.LLM && emotion.reuseReply) return Option.Same
        val jevProvider = JevProvider.resolve(prefs.getString(ModulePrefs.KEY_API_PROVIDER, null),
            prefs.getString(ModulePrefs.KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT).orEmpty())
        val jevKey = prefs.getString(ModulePrefs.KEY_API_KEY, "").orEmpty()
        val intentEndpoint = prefs.getString(IntentSettings.KEY_ENDPOINT, "").orEmpty()
        val intentKey = prefs.getString(IntentSettings.KEY_API_KEY, "").orEmpty()
        if (emotion.source == EmotionSource.JEV || route == IntentRoute.JEV) {
            if (jevKey.isNotBlank() || intentKey.isBlank()) return Option.Jev(jevProvider)
        }
        if (intentKey.isNotBlank()) {
            return Option.Llm(ReplyProvider.resolve(prefs.getString(IntentProfiles.KEY_PROVIDER, null), intentEndpoint))
        }
        return Option.Jev(jevProvider)
    }

    private fun loadDraft(target: Option): Draft = when (target) {
        is Option.Jev -> {
            val provider = target.provider
            val savedEndpoint = prefs.getString("channel_${provider.id}_endpoint", null)
            val savedKey = prefs.getString("channel_${provider.id}_key", null)
            val savedModel = prefs.getString("channel_${provider.id}_model", null)
            val active = JevProvider.resolve(prefs.getString(ModulePrefs.KEY_API_PROVIDER, null),
                prefs.getString(ModulePrefs.KEY_API_BASE, "").orEmpty()) == provider
            Draft(
                savedEndpoint ?: if (provider == JevProvider.CUSTOM) prefs.getString(ModulePrefs.KEY_API_BASE, "").orEmpty() else provider.endpoint,
                savedKey ?: if (active) prefs.getString(ModulePrefs.KEY_API_KEY, "").orEmpty() else "",
                savedModel ?: if (provider == JevProvider.CUSTOM && active) prefs.getString(ModulePrefs.KEY_API_MODEL, "").orEmpty() else provider.model,
            )
        }
        is Option.Llm -> IntentProfiles.load(target.provider) { prefs.getString(it, null) }
            .let { Draft(it.endpoint, it.apiKey, it.model) }
        Option.Same -> Draft("", "", "")
    }

    private fun remember() {
        if (option == Option.Same) return
        drafts[option.id] = Draft(binding.endpoint.text.toString(), binding.apiKey.text.toString(), binding.model.text.toString())
    }

    private fun render() {
        rendering = true
        val target = option
        binding.analysisChannel.setText(target.label, false)
        val draft = drafts[target.id] ?: loadDraft(target)
        binding.endpoint.setText(draft.endpoint)
        binding.apiKey.setText(draft.key)
        setModels(modelOptions)
        binding.model.setText(draft.model, false)
        val same = target == Option.Same
        val jev = (target as? Option.Jev)?.provider
        val llm = (target as? Option.Llm)?.provider
        val custom = jev == JevProvider.CUSTOM || llm == ReplyProvider.CUSTOM
        val editableModel = llm != null || jev == JevProvider.CUSTOM
        binding.endpointLayout.visibility = if (custom) View.VISIBLE else View.GONE
        binding.endpointLayout.helperText = when {
            jev == JevProvider.CUSTOM -> "填写完整的 Jev 兼容接口地址，不会自动补路径。"
            llm == ReplyProvider.CUSTOM -> "填写 Base URL（如 https://example.com/v1）或完整的 /chat/completions 地址。"
            else -> null
        }
        binding.keyLayout.visibility = if (same) View.GONE else View.VISIBLE
        binding.modelLayout.visibility = if (editableModel) View.VISIBLE else View.GONE
        binding.fetchModels.visibility = if (llm != null) View.VISIBLE else View.GONE
        binding.modelLayout.isEndIconVisible = modelOptions.isNotEmpty()
        binding.providerConsole.visibility = if (consoleUrl() == null) View.GONE else View.VISIBLE
        binding.endpointLayout.error = null
        binding.keyLayout.error = null
        binding.modelLayout.error = null
        binding.channelHint.text = when {
            same -> {
                val reply = prefs.getString(ReplySettings.KEY_MODEL, "").orEmpty()
                if (reply.isBlank()) "回复连接还没有模型，先去上面配置回复连接。" else "复用回复连接：$reply"
            }
            jev != null -> jev.guide
            llm != null -> llm.hint
            else -> ""
        }
        binding.analysisFormSummary.text = if (same) "与回复连接相同" else target.label
        binding.modelsStatus.text = if (llm == null) "" else if (modelOptions.isEmpty()) "填写 Key 后可获取列表，也可直接填写模型 ID。" else "已获取 ${modelOptions.size} 个候选模型。"
        rendering = false
        status(if (same) "分析与回复共用一条连接" else "已保存 · 尚未检测", R.color.status_neutral)
        binding.connectionResult.visibility = View.GONE
    }

    private fun consoleUrl(): String? = when (option) {
        is Option.Jev -> (option as Option.Jev).provider.keyUrl
        is Option.Llm -> (option as Option.Llm).provider.consoleUrl.ifBlank { null }
        Option.Same -> null
    }

    private fun dirty() {
        status("有未保存修改", R.color.status_warning)
        binding.connectionResult.visibility = View.GONE
    }

    private fun setModels(models: List<String>) {
        modelOptions = models
        binding.model.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, models))
        binding.modelLayout.isEndIconVisible = models.isNotEmpty()
    }

    private fun fetchModels() {
        val target = option as? Option.Llm ?: return
        if (busy) return
        val settings = readLlm(target) ?: return
        busy = true
        setBusy(true)
        SettingsStatus.show(binding.modelsStatus, "正在获取模型列表…", R.color.status_info)
        scope.launch {
            try {
                val models = ReplyModelsClient().list(settings)
                setModels(models)
                SettingsStatus.show(binding.modelsStatus,
                    if (models.isEmpty()) "没有取到候选模型，可以直接填写模型 ID。" else "已获取 ${models.size} 个候选模型；选定后请检测。",
                    if (models.isEmpty()) R.color.status_warning else R.color.status_success)
                binding.model.showDropDown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SettingsStatus.show(binding.modelsStatus, e.message ?: "获取失败，可重试或直接填写模型 ID", R.color.status_error)
            } finally {
                busy = false
                setBusy(false)
            }
        }
    }

    private fun readJev(target: Option.Jev): ApiSettings? {
        val provider = target.provider
        binding.endpointLayout.error = null
        binding.keyLayout.error = null
        binding.modelLayout.error = null
        val endpoint = if (provider == JevProvider.CUSTOM) binding.endpoint.text.toString() else provider.endpoint
        val key = binding.apiKey.text.toString()
        val model = if (provider == JevProvider.CUSTOM) binding.model.text.toString() else provider.model
        if (key.isBlank()) {
            binding.keyLayout.error = "请填写这个渠道的 API Key"
            return null
        }
        return try {
            val settings = ApiSettings.fromInput(endpoint, key, provider.id, model)
            MoodLog.protect(settings.apiKey)
            settings
        } catch (e: IllegalArgumentException) {
            binding.endpointLayout.error = e.message
            null
        }
    }

    private fun readLlm(target: Option.Llm): ReplySettings? {
        val provider = target.provider
        binding.endpointLayout.error = null
        binding.keyLayout.error = null
        binding.modelLayout.error = null
        val endpoint = if (provider == ReplyProvider.CUSTOM) binding.endpoint.text.toString() else provider.endpoint
        val key = binding.apiKey.text.toString()
        val model = binding.model.text.toString()
        if (endpoint.isBlank()) {
            binding.endpointLayout.error = "请填写 API 地址"
            return null
        }
        if (key.isBlank()) {
            binding.keyLayout.error = "请填写这个渠道的 API Key"
            return null
        }
        if (model.isBlank()) {
            binding.modelLayout.error = "请选择或填写模型 ID"
            return null
        }
        return try {
            ReplySettings.fromInput(endpoint, key, model).also { MoodLog.protect(it.apiKey) }
        } catch (e: IllegalArgumentException) {
            binding.endpointLayout.error = e.message
            null
        }
    }

    private fun save(): Boolean {
        if (busy) return false
        val target = option
        val updates = linkedMapOf<String, String>()
        when (target) {
            Option.Same -> {
                updates[EmotionSettings.KEY_SOURCE] = EmotionSource.LLM.id
                updates[EmotionSettings.KEY_REUSE_REPLY] = "true"
                updates[IntentSettings.KEY_ROUTE] = IntentRoute.LLM.id
            }
            is Option.Jev -> {
                val settings = readJev(target) ?: return false
                updates.putAll(ApiProfiles.valuesToSave(settings) { prefs.getString(it, null) })
                updates[EmotionSettings.KEY_SOURCE] = EmotionSource.JEV.id
                updates[EmotionSettings.KEY_REUSE_REPLY] = "false"
                updates[IntentSettings.KEY_ROUTE] = IntentRoute.JEV.id
            }
            is Option.Llm -> {
                val settings = readLlm(target) ?: return false
                updates.putAll(IntentProfiles.valuesToSave(target.provider, settings) { prefs.getString(it, null) })
                updates[EmotionSettings.KEY_SOURCE] = EmotionSource.LLM.id
                updates[EmotionSettings.KEY_REUSE_REPLY] = "false"
                updates[IntentSettings.KEY_ROUTE] = IntentRoute.LLM.id
            }
        }
        val saved = SettingsProvider.save(activity) { updates.forEach { (key, value) -> putString(key, value) } }
        if (!saved) {
            status("保存失败，请重试", R.color.status_error)
            return false
        }
        ModulePrefs.reload(force = true)
        onChanged()
        return true
    }

    private fun test() {
        if (busy) return
        if (!save()) return
        val tested = option
        busy = true
        setBusy(true)
        status("正在检测…", R.color.status_info)
        result("正在用示例聊天检测，请稍等。")
        scope.launch {
            try {
                val detail = when (tested) {
                    Option.Same -> testLlm(ModulePrefs.replySettings())
                    is Option.Jev -> testJev()
                    is Option.Llm -> testLlm(ModulePrefs.analysisSettings()?.intent?.llm
                        ?: ModulePrefs.replySettings())
                }
                if (option != tested) return@launch
                status("连接成功", R.color.status_success)
                result(detail)
                MoodLog.i("分析连接检测成功")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (option != tested) return@launch
                status("连接失败 · 点击重试", R.color.status_error)
                result(failureText(e), error = true)
                // 原文可能是 "Failed requirement." 这类英文，界面上换成人话，细节留在日志里。
                MoodLog.e("分析连接检测失败：${e.javaClass.simpleName} ${e.message}")
            } finally {
                busy = false
                setBusy(false)
                if (option != tested) {
                    status("配置已变化 · 请重新检测", R.color.status_warning)
                    binding.connectionResult.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun testJev(): String {
        val settings = ModulePrefs.apiSettings()
        val sample = AnalysisInput("这还差不多。", "sample", listOf(
            ContextMessage("对方", "你是不是忘了周末吃饭的事？"),
            ContextMessage("我", "记得，这次我来安排，明天把餐厅和时间告诉你。")))
        val mood = ChatAnalysis.analyzeSuspending(sample, settings.model, exchange = { payload ->
            // 检测按钮会反复点，复用同一个客户端，别每次都新建连接池与线程池。
            JevHttpClient.shared.exchangeSuspending(payload, settings)
        })
        return mood.detail
    }

    private suspend fun testLlm(settings: ReplySettings): String {
        if (!settings.isConfigured) throw IllegalStateException("这条连接还没有填 Key")
        val input = AnalysisInput("睡吧睡吧", "sample", listOf(ContextMessage("我", "累了一天，终于躺下了。")))
        val mood = ReplyHttpClient(kind = UsageKind.CHECK).request(settings, LlmEmotionProtocol.payload(input, settings),
            parse = LlmEmotionProtocol::parse)
        return mood.detail
    }

    private fun setBusy(value: Boolean) {
        binding.progressConnection.visibility = if (value) View.VISIBLE else View.GONE
        listOf(binding.channelLayout, binding.endpointLayout, binding.keyLayout,
            binding.modelLayout, binding.fetchModels, binding.providerConsole, binding.testConnection)
            .forEach { it.isEnabled = !value }
    }

    private fun status(text: String, color: Int) {
        SettingsStatus.show(binding.connectionStatus, text, color)
        val label = if (option == Option.Same) "与回复连接相同" else option.label
        binding.analysisFormSummary.text = "$label · $text"
        SettingsStatus.show(binding.testConnection, when (color) {
            R.color.status_success -> "连接成功 · 重新检测"
            R.color.status_error -> "连接失败 · 点击重试"
            R.color.status_info -> "正在检测…"
            else -> "保存并检测分析连接"
        }, color)
    }

    /** 中文提示原样用；纯 ASCII 的技术性异常（require/JSON 解析）统一换成人话，细节只进日志。 */
    private fun failureText(error: Throwable): String {
        val message = error.message.orEmpty()
        if (message.isBlank() || message.none { it.code > 127 }) return "模型返回的数据格式不对，请检查接口地址与模型"
        return message
    }

    private fun result(text: String, error: Boolean = false) {
        binding.connectionResult.text = text
        binding.connectionResult.visibility = View.VISIBLE
        SettingsStatus.show(binding.connectionResult, text, if (error) R.color.status_error else R.color.status_neutral)
    }
}
