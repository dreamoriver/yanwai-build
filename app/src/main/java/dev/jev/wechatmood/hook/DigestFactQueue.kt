package dev.jev.wechatmood.hook

import android.content.Context
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.ReplyContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 转发摘要、图片描述这类「另一个模型写出来的转述」入档的唯一入口。
 *
 * 整理要读当前聊天，所以页面快照必须在用户还在这个聊天时抓下来：长按后立刻切走，
 * 晚一点再读会把 B 的会话按 A 的键写下去。快照对不上就整次放弃，不猜。
 * 转述本身经 RelationFactRefresher 的 extra 通道进提示词，不伪造聊天依据。
 */
internal object DigestFactQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 同一联系人正在整理时等一次再试；仍占用就放弃，不排队反复请求模型。 */
    private const val RETRY_DELAY_MS = 5_000L
    private const val MAX_TEXT = 1200
    /** 同一段转述半小时内只处理一次：每点一次「摘要」都重抽一遍，等于白花一次模型钱。 */
    private const val DEDUPE_MS = 30 * 60 * 1000L
    /** 只记最近 32 条指纹，够挡住反复点同一条就行。 */
    private val recent = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 32
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)

    /**
     * @param account 已知账号时直接给；为 null 时按页面快照反查，查不到就放弃。
     * @param talker 当前聊天；为 null 时取页面快照里的 talker。
     * @param captured 调用方已经抓好的页面快照，省一次视图遍历，也保证是点按那一刻的聊天。
     */
    fun offer(context: Context, account: String?, talker: String?, label: String, text: String,
        captured: ReplyContext? = null,
        /** 用户主动点「转成待办」时传 false：那是明确要求再抽一次，不该被去重挡掉。 */
        dedupe: Boolean = true,
        onResult: ((Boolean) -> Unit)? = null) {
        val body = text.trim()
        if (body.isBlank()) {
            onResult?.invoke(false)
            return
        }
        val line = "【" + label + "】" + body.take(MAX_TEXT)
        val page = captured ?: runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull()
        val resolvedTalker = talker ?: page?.talker
        if (page == null || resolvedTalker == null || page.talker != resolvedTalker) {
            MoodLog.i("DIGEST_FACT_SKIP page=" + (page != null) + " talker=" + (resolvedTalker != null))
            onResult?.invoke(false)
            return
        }
        val print = resolvedTalker + "|" + fingerprint(line)
        val now = System.currentTimeMillis()
        if (dedupe) {
            val last = synchronized(recent) { recent[print] }
            if (last != null && now - last < DEDUPE_MS) {
                MoodLog.i("DIGEST_FACT_DEDUPE age=${(now - last) / 1000}s")
                onResult?.invoke(true)
                return
            }
        }
        // 先记指纹再启动，避免连点两次同时漏过判断。
        synchronized(recent) { recent[print] = now }
        val app = context.applicationContext ?: context
        scope.launch {
            val settings = ModulePrefs.replySettings()
            if (!settings.isConfigured) {
                MoodLog.i("DIGEST_FACT_SKIP unconfigured")
                onResult?.invoke(false)
                return@launch
            }
            val resolvedAccount = withContext(Dispatchers.IO) {
                account ?: runCatching { ReplyDatabaseHistory.replyAccount(page) }.getOrNull()
            }
            val key = ReplyContactKey.of(resolvedAccount, resolvedTalker, allowGroup = true)
            if (key == null || resolvedAccount == null) {
                MoodLog.i("DIGEST_FACT_SKIP key account=" + (resolvedAccount != null))
                onResult?.invoke(false)
                return@launch
            }
            val snapshot = RelationFactRefresher.Snapshot(resolvedAccount, resolvedTalker, page.messages)
            // 只抽这条转述，不把整个聊天窗口再上传一次：
            // 否则会推进整理水位、把群聊「首次不自动整理」的门槛打正，还会跳过积压中间那段。
            val extra = listOf(line)
            val finish: (RelationFactRefresher.Result?, Throwable?) -> Unit = { result, error ->
                report(label, result, error)
                onResult?.invoke(error == null && result != null)
            }
            val started = RelationFactRefresher.launch(app, key, settings, incremental = false,
                snapshot = snapshot, extra = extra, digestOnly = true, onDone = finish)
            if (!started) {
                delay(RETRY_DELAY_MS)
                val retried = RelationFactRefresher.launch(app, key, settings, incremental = false,
                    snapshot = snapshot, extra = extra, digestOnly = true, onDone = finish)
                if (!retried) {
                    MoodLog.i("DIGEST_FACT_SKIP busy")
                    onResult?.invoke(false)
                }
            }
        }
    }

    private fun report(label: String, result: RelationFactRefresher.Result?, error: Throwable?) {
        MoodLog.i("DIGEST_FACT_DONE label=" + label + " added=" + (result?.added ?: -1) +
            " scanned=" + (result?.scanned ?: -1) +
            " error=" + (error?.javaClass?.simpleName ?: "none"))
    }
}
