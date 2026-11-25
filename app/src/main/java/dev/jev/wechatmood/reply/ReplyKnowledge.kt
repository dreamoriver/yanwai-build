package dev.jev.wechatmood.reply

import android.content.Context

/** Original MIT sources are bundled, pinned and read locally, never fetched at chat time. */
object ReplyKnowledge {
    const val SOURCE_URL = "https://github.com/shengjidaguai-china/goutoujunshi"
    const val REVISION = "6db7354a4002dc7c448a9c87ffdad8132570c9d3"
    private val cached = mutableMapOf<ReplyRelationship, String>()
    @Synchronized fun load(context: Context, relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED): String {
        cached[relationship]?.let { return it }
        val module = if (context.packageName == "dev.jev.wechatmood") context else
            context.createPackageContext("dev.jev.wechatmood", Context.CONTEXT_IGNORE_SECURITY)
        val assets = module.assets
        val paths = ReplyKnowledgeCatalog.paths(relationship)
        return paths.joinToString("\n\n") { path ->
            "## 来源：$path\n" + assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.also { cached[relationship] = it }
    }

    /** 按当前对话裁剪相关片段，避免每次都把整库塞进 system。 */
    fun loadFor(context: Context, relationship: ReplyRelationship, sample: String): String {
        val target = effective(relationship, sample)
        return ReplyKnowledgeSelector.select(load(context, target), sample, anchors(target))
    }

    /** 聊天里出现伴侣线索时，即使身份选的是同事／朋友／暧昧，也切到同一套参考。 */
    private val PARTNER_CUES = listOf("老公", "丈夫", "媳妇", "老婆", "婆家", "孩子他爸", "家里那位", "我先生", "已婚", "婚假", "领证")

    private fun effective(relationship: ReplyRelationship, sample: String): ReplyRelationship =
        if (relationship == ReplyRelationship.TAKEN || PARTNER_CUES.none { sample.contains(it) }) relationship
        else ReplyRelationship.TAKEN

    /** 这些段在长库里按检索分数进不来，必须定额保留。 */
    private fun anchors(relationship: ReplyRelationship): List<String> = when (relationship) {
        ReplyRelationship.TAKEN -> listOf("## 十一、反舔检查", "## 三、五级阶梯", "## 五、越界话术的三个构造条件")
        else -> emptyList()
    }
}
