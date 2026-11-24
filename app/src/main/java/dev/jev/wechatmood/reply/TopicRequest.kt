package dev.jev.wechatmood.reply

import android.content.Context

/**
 * 「找话题」的唯一请求入口。
 *
 * 抽屉里原来是直接拼参数调 [ReplyHttpClient.findTopics]；现在多了一个入口（关系温度里
 * 点别人的会话），两处各写一份拼参数迟早会分叉：一处补了参考资料选取、另一处忘了，
 * 同一段聊天在两边给出的话题就会不一样。所以把「准备参考资料 + 当前日期上下文 + 发请求」
 * 收成一个函数，抽屉与关系温度都从这里进。
 *
 * 这里不改写任何东西：提示词仍在 [TopicProtocol]，重试与用量统计仍在 [ReplyHttpClient]，
 * 关系温度那边复用的就是抽屉同一条链路，不另起一份 HTTP 实现。
 */
object TopicRequest {

    /**
     * @param knowledge 抽屉已经按关系选过参考资料时传进来，避免再读一遍 assets；
     *   传 null 就按 [chat] 的消息、草稿和补充现选一次。
     * @param time 抽屉会先算好（同一批话题要对同一个日期去重），传 null 则取当前时间。
     */
    suspend fun find(context: Context, settings: ReplySettings, chat: ReplyContext, draft: String,
        notes: String, relationship: ReplyRelationship, customRelationship: String = "",
        knowledge: String? = null, time: TopicTimeContext? = null,
        previous: List<TopicSuggestion> = emptyList(), client: ReplyHttpClient = ReplyHttpClient()
    ): List<TopicSuggestion> {
        val guide = knowledge ?: ReplyKnowledge.loadFor(context, relationship,
            chat.messages.joinToString("\n") { it.text } + "\n" + draft + "\n" + notes)
        return client.findTopics(settings, chat, draft, notes, guide, relationship,
            time ?: TopicCalendar.current(), previous, customRelationship)
    }
}
