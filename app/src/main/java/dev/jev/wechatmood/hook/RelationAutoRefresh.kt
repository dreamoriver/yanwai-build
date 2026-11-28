package dev.jev.wechatmood.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 进入私聊页后静默整理一次：不打开「帮我回」也让档案跟上。
 *
 * 门槛与抽屉里的自动整理一致：距上次整理超过 3 天，或这段时间新增了 30 条以上消息。
 * 只读页面消息判断数量，真正发请求前已经由 [RelationFactRefresher] 做去重与合并。
 */
internal object RelationAutoRefresh {
    private const val DELAY_MS = 6_000L
    /** 私聊常规门槛：一天一次，或新增 10 条。原来 3 天/30 条太钝，约定两三天后才入库。 */
    private const val STALE_MS = 24 * 3600_000L
    private const val FRESH_MESSAGES = 10
    /**
     * 这段聊天里出现了时间线索（明天、周五、下午三点、五号）时收紧到这一档。
     *
     * 提醒是按库里的绝对时间筛的，条目晚一天入库，事情往往已经过去，通知只能带「（已过）」。
     * 所以只要刚看见时间词，就趁约定还没发生先提炼一次。
     */
    private const val URGENT_STALE_MS = 30 * 60_000L
    private const val URGENT_FRESH_MESSAGES = 3
    /** 群聊更贵也更吵：一周一次、或新增 50 条才整理，避免每个活跃群都反复请求模型。 */
    private const val GROUP_STALE_MS = 7 * 24 * 3600_000L
    private const val GROUP_FRESH_MESSAGES = 50
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Runnable? = null

    /** 每次 Activity resume 都排一次；6 秒内又切页就作废上一次。 */
    fun schedule(activity: Activity) {
        cancel()
        val job = Runnable { run(activity) }
        pending = job
        handler.postDelayed(job, DELAY_MS)
    }

    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /**
     * 时间线索：只用来决定「要不要提前整理一次」，不参与事实提取。
     * 命中只说明这段聊天可能刚产生了约定，最终有没有约定仍由模型按证据判断。
     */
    private val TIME_CUE = Regex(
        "(明天|后天|今晚|明早|明晚|上午|下午|早上|晚上|中午|凌晨|" +
            "周[一二三四五六日天]|星期[一二三四五六日天]|下周|这周|周末|" +
            "\\d{1,2}\\s*[点時时]|\\d{1,2}\\s*号|\\d{1,2}\\s*月|\\d{1,2}:\\d{2})"
    )

    /** 只看这轮消息正文；空消息与纯媒体占位不算。 */
    private fun hasTimeCue(messages: List<ReplyMessage>): Boolean =
        messages.any { it.text.isNotBlank() && TIME_CUE.containsMatchIn(it.text) }

    private fun run(activity: Activity) {
        pending = null
        scope.launch {
            try {
                check(activity)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                MoodLog.w("RELATION_FACT_AUTOPAGE_FAILED " + error.javaClass.simpleName)
            }
        }
    }

    private suspend fun check(activity: Activity) {
        val talker = MessageSniffer.currentReplyTalker() ?: return
        val group = talker.endsWith("@chatroom")
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull() ?: return
        if (live.talker != talker) return
        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) return
        val account = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(live) } ?: return
        val key = ReplyContactKey.of(account, talker, allowGroup = true) ?: return
        // 6 秒延迟期间用户可能已经切走：整理只认这次读到的账号、联系人与消息。
        val snapshot = RelationFactRefresher.Snapshot(account, talker, live.messages)
        // 到期提醒只受「显示档案」本身影响，关掉自动整理不该把提醒一起停掉。
        val facts = withContext(Dispatchers.IO) { runCatching { RelationFactBridge.load(activity, key) }.getOrDefault(emptyList()) }
        // 哈希键换不回聊天对象：顺手把聊天对象和备注名记一份，提醒点开才能进对会话。
        withContext(Dispatchers.IO) {
            val name = runCatching {
                ReplyDatabaseHistory.contactNames(listOf(talker))[talker]
                    ?: ReplyDatabaseHistory.groupNames(listOf(talker))[talker]
            }.getOrNull()
            RelationFactBridge.rememberContact(activity, key, talker, name.orEmpty())
        }
        RelationReminder.check(activity, key, talker, facts)
        if (!RelationFactBridge.enabled(activity)) return
        // 上一次抽取刚失败过：半小时内别再花一次请求，等用户主动点整理或冷却结束。
        if (RelationFactRefresher.recentFailure(key)) {
            MoodLog.i("RELATION_FACT_AUTOPAGE skipped recent_failure")
            return
        }
        val meta = withContext(Dispatchers.IO) { runCatching { RelationFactBridge.meta(activity, key) }.getOrNull() } ?: return
        val gap = System.currentTimeMillis() - meta.lastAt
        // 页面往往只有十几条，拿它数增量永远到不了 30 条；按库里的真实新增来算。
        val window = withContext(Dispatchers.IO) {
            runCatching { ReplyDatabaseHistory.load(live, ReplyContext.MAX_MESSAGES) }.getOrNull()?.messages
        }.orEmpty()
        val fresh = window.count { it.id > meta.lastMessageId }
        // 刚聊到「明天几点」「周五」这类时间词，说明可能刚说定了事：不走常规的一天/10 条门槛，
        // 半小时内就提炼一次。否则条目要等到下次大间隔才入库，提醒必然落在事情之后。
        val urgent = !group && hasTimeCue(window)
        val stale = when {
            group -> GROUP_STALE_MS
            urgent -> URGENT_STALE_MS
            else -> STALE_MS
        }
        val freshTarget = when {
            group -> GROUP_FRESH_MESSAGES
            urgent -> URGENT_FRESH_MESSAGES
            else -> FRESH_MESSAGES
        }
        // 群聊第一次不进自动整理：一个没见过的群上传 50 条消息给模型，隐私和费用都超出预期。
        // 用户在「附加 · 群聊纪要」点过一次「整理一次」后，才开始按 7 天/50 条自动更新。
        val due = if (group && meta.lastAt <= 0) false
            else meta.lastAt <= 0 || gap > stale || fresh >= freshTarget
        MoodLog.i("RELATION_FACT_AUTOPAGE due=$due gap_hours=${gap / 3600_000L} fresh=$fresh " +
            "window=${window.size} urgent=$urgent")
        if (!due) return
        val started = RelationFactRefresher.launch(activity, key, settings, snapshot = snapshot) { result, error ->
            when {
                error != null -> MoodLog.w("RELATION_FACT_AUTOPAGE_ERROR " + error.javaClass.simpleName)
                result == null -> Unit
                result.nothingNew -> MoodLog.i("RELATION_FACT_AUTOPAGE nothing_new")
                else -> MoodLog.i("RELATION_FACT_AUTOPAGE saved=" + result.facts.size)
            }
        }
        if (!started) MoodLog.i("RELATION_FACT_AUTOPAGE skipped running")
    }
}
