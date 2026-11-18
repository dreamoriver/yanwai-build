package dev.jev.wechatmood.hook

import android.app.Activity
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.FactExtractor
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.ReplySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 关系档案的一次整理：读最近历史、抽取、按联系人写回。
 * 抽屉里的「更新关系档案」和档案页共用这一段，避免两套抽取逻辑漂移。
 */
internal object RelationFactRefresher {
    /** 返回合并后的有效条目；没有抽到内容时返回空列表，不写库。 */
    suspend fun refresh(activity: Activity, key: ReplyContactKey, settings: ReplySettings): List<RelationFact> {
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull()
            ?: error("读取聊天失败")
        // 页面上往往只有十几条，抽不出稳定事实；优先用本机历史补足，读不到再退回页面消息。
        val history = runCatching { ReplyDatabaseHistory.load(live, FactExtractor.MAX_MESSAGES) }.getOrNull()
        val messages = history?.messages?.takeIf { it.isNotEmpty() } ?: live.messages
        MoodLog.i("RELATION_FACT_INPUT page=${live.messages.size} used=${messages.size}")
        val extracted = withContext(Dispatchers.IO) { FactExtractor().extract(settings, messages) }
        if (extracted.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            RelationFactBridge.save(activity, key, extracted, System.currentTimeMillis(), messages.lastOrNull()?.id ?: 0)
        }.also { MoodLog.i("RELATION_FACT_SAVED count=${it.size}") }
    }
}
