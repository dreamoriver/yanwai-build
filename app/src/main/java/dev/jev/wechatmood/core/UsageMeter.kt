package dev.jev.wechatmood.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Executors

data class UsageToday(val requests: Int = 0, val promptTokens: Long = 0, val completionTokens: Long = 0) {
    val totalTokens: Long get() = promptTokens + completionTokens
    operator fun plus(other: UsageToday) = UsageToday(requests + other.requests,
        promptTokens + other.promptTokens, completionTokens + other.completionTokens)
}

/** 用量类别。上报侧和展示侧共用这套键，认不出的值一律按回复算。 */
object UsageKind {
    const val REPLY = "reply"
    const val DIGEST = "digest"
    const val RELATION = "relation"
    const val IMAGE = "image"
    const val ANALYSIS = "analysis"
    const val CHECK = "check"
    const val SIMULATE = "simulate"

    private val known = setOf(REPLY, DIGEST, RELATION, IMAGE, ANALYSIS, CHECK, SIMULATE)

    fun normalize(kind: String?): String = if (kind != null && kind in known) kind else REPLY

    fun label(kind: String): String = when (normalize(kind)) {
        DIGEST -> "摘要"
        RELATION -> "关系整理"
        IMAGE -> "图片描述"
        ANALYSIS -> "分析"
        CHECK -> "检测"
        SIMULATE -> "预演"
        else -> "回复"
    }
}

/**
 * 本地用量统计：只记次数与 token，不记内容。
 *
 * 请求发生在微信进程，由 [UsageReporter] 通过设置服务上报到这里。
 * 按天、按类别保存，只留最近 [KEEP_DAYS] 天：{"2026-11-23":{"reply":{"r":3,"p":100,"c":50}}}。
 * 旧版的 day/requests/prompt/completion 键不再读，也不迁移历史。
 */
object UsageMeter {
    private const val FILE = "usage_stats"
    private const val KEY_DAYS = "days"
    private const val KEEP_DAYS = 30
    const val SUMMARY_DAYS = 7

    @Synchronized fun add(context: Context, prompt: Long, completion: Long, kind: String = UsageKind.REPLY) {
        val prefs = context.getSharedPreferences(FILE, 0)
        val days = load(prefs)
        val today = LocalDate.now()
        val day = days.optJSONObject(today.toString()) ?: JSONObject().also { days.put(today.toString(), it) }
        val name = UsageKind.normalize(kind)
        val bucket = day.optJSONObject(name) ?: JSONObject().also { day.put(name, it) }
        bucket.put("r", bucket.optInt("r", 0) + 1)
        bucket.put("p", bucket.optLong("p", 0L) + prompt.coerceAtLeast(0))
        bucket.put("c", bucket.optLong("c", 0L) + completion.coerceAtLeast(0))
        prune(days, today)
        prefs.edit().putString(KEY_DAYS, days.toString()).apply()
    }

    fun today(context: Context): UsageToday = recent(context, 1)

    /** 最近 [days] 天（含今天）的总量。 */
    fun recent(context: Context, days: Int): UsageToday =
        byKind(context, days).values.fold(UsageToday(), UsageToday::plus)

    /** 最近 [days] 天按类别汇总，按请求数从多到少，零项不返回。 */
    fun byKind(context: Context, days: Int): Map<String, UsageToday> {
        val stored = load(context.getSharedPreferences(FILE, 0))
        val from = LocalDate.now().minusDays((days.coerceAtLeast(1) - 1).toLong())
        val total = HashMap<String, UsageToday>()
        for (key in stored.keys().asSequence().toList()) {
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
            if (date.isBefore(from)) continue
            val buckets = stored.optJSONObject(key) ?: continue
            for (name in buckets.keys().asSequence().toList()) {
                val bucket = buckets.optJSONObject(name) ?: continue
                val usage = UsageToday(bucket.optInt("r", 0), bucket.optLong("p", 0L), bucket.optLong("c", 0L))
                if (usage.requests == 0 && usage.totalTokens == 0L) continue
                total[UsageKind.normalize(name)] = (total[UsageKind.normalize(name)] ?: UsageToday()) + usage
            }
        }
        return total.entries.sortedByDescending { it.value.requests }.associate { it.key to it.value }
    }

    fun summary(context: Context): String {
        val week = recent(context, SUMMARY_DAYS)
        if (week.requests == 0) return "今天还没有调用模型"
        return "今天 ${today(context).requests} 次 · 近 $SUMMARY_DAYS 天 ${week.requests} 次 · ${week.totalTokens} tokens"
    }

    /** 按类别拆成多行，只列非零项；给「更多」页的用量详情用。 */
    fun detail(context: Context, days: Int = SUMMARY_DAYS): String {
        val kinds = byKind(context, days)
        if (kinds.isEmpty()) return "近 $days 天还没有调用模型"
        return "近 $days 天用量：\n" + kinds.entries.joinToString("\n") { (kind, usage) ->
            "${UsageKind.label(kind)} ${usage.requests} 次 · ${usage.totalTokens} tokens"
        }
    }

    /** 读不出来就当空：旧格式、写坏的 JSON 都不该让界面崩。 */
    private fun load(prefs: SharedPreferences): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_DAYS, null) ?: "{}") }.getOrElse { JSONObject() }

    /** 只留最近 [KEEP_DAYS] 天；键是 ISO 日期，字符串比较即可排序。 */
    private fun prune(days: JSONObject, today: LocalDate) {
        val oldest = today.minusDays((KEEP_DAYS - 1).toLong()).toString()
        for (key in days.keys().asSequence().toList()) {
            if (key < oldest || runCatching { LocalDate.parse(key) }.isFailure) days.remove(key)
        }
    }
}

/** 微信进程里的上报入口；拿不到设置服务时直接放弃，不影响模型请求。 */
object UsageReporter {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "yanwai-usage").apply { isDaemon = true } }
    @Volatile private var context: Context? = null

    fun init(context: Context) { this.context = context.applicationContext }

    fun report(prompt: Long = 0, completion: Long = 0, kind: String = UsageKind.REPLY) {
        val ctx = context ?: return
        val name = UsageKind.normalize(kind)
        val promptTokens = prompt.coerceAtLeast(0)
        val completionTokens = completion.coerceAtLeast(0)
        worker.execute {
            runCatching {
                // 微信进程碰不到 App 的 SharedPreferences，只能经设置服务中转；
                // App 进程自己发起的检测、示例自己就是主人，记本地即可。
                if (ctx.packageName == "com.tencent.mm") {
                    SettingsTransport.call(ctx, "usage_add", null, Bundle().apply {
                        putLong("prompt", promptTokens)
                        putLong("completion", completionTokens)
                        putString("kind", name)
                    })
                } else {
                    UsageMeter.add(ctx, promptTokens, completionTokens, name)
                }
            }
        }
    }
}
