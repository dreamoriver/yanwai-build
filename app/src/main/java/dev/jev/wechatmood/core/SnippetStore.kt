package dev.jev.wechatmood.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一条话术片段：用户自己写好的常用表态短句（拒绝、请假、约时间这类）。
 *
 * id 只是本机标识，不带内容摘要；日志里只出现 id，不出现片段正文。
 */
data class Snippet(
    val id: String,
    val title: String,
    val text: String,
    val updatedAt: Long,
)

/**
 * 话术片段的本机存储。
 *
 * 为什么是 SharedPreferences 而不是写进微信的库：这些句子是用户自己写的文本，
 * 和聊天记录、联系人没有任何关系，也不需要被别的功能反查。为了它去摸微信的表结构、
 * 再跟着微信升级改代码，换不来任何好处。
 *
 * 所以这里只依赖 Context：一份独立的小文件（[PREFS]）加一个 JSON 数组，
 * 微信升级、清聊天记录、换账号都动不到它。
 *
 * 文件落在哪个进程取决于调用方给的 Context：从聊天界面调用时跟着微信进程走
 * （和 hook 层的 GroupNameStore 一致），但文件仍是我们自己的名字，不混进微信的表。
 *
 * 容量取舍：100 条 × 500 字，最坏约 60KB，一次性读写不影响滑动；
 * 超出上限丢 updatedAt 最旧的那条，不做静默截断式的「悄悄吞掉新内容」。
 */
object SnippetStore {
    const val PREFS = "yanwai_snippets"
    private const val KEY_ITEMS = "items"
    /** 原文解析不了时先留一份，别让一次解析失败把唯一一份数据吃掉。 */
    private const val KEY_BAD = "items_unreadable"
    const val MAX_ITEMS = 100
    const val MAX_TEXT = 500
    /** 标题只占列表一行，和角色名同一个上限；留空就用正文开头代替。 */
    const val MAX_TITLE = 40

    /** 读-改-写放在同一个临界区，两个界面同时保存也不会互相覆盖。 */
    private val lock = Any()

    /** 按最近修改时间从新到旧；读不出来时返回空列表，不清用户数据。 */
    fun list(context: Context): List<Snippet> = synchronized(lock) {
        read(context).sortedByDescending { it.updatedAt }
    }

    /**
     * 同 id 覆盖，空 id 视为新增。
     * 返回 false 表示这次没写进去（内容为空或磁盘异常），由界面决定怎么提示。
     */
    fun save(context: Context, snippet: Snippet): Boolean = synchronized(lock) {
        runCatching {
            val text = snippet.text.trim().take(MAX_TEXT)
            if (text.isEmpty()) return@synchronized false
            val value = Snippet(
                id = snippet.id.trim().ifEmpty { UUID.randomUUID().toString() },
                title = snippet.title.trim().take(MAX_TITLE),
                text = text,
                // 保存时间由存储侧兜底，界面不必自己算。
                updatedAt = if (snippet.updatedAt > 0) snippet.updatedAt else System.currentTimeMillis(),
            )
            // 先摘掉同一条，再加上新值，最后按更新时间重排。
            val kept = read(context).filterNot { it.id == value.id } + value
            write(context, kept.sortedByDescending { it.updatedAt }.take(MAX_ITEMS))
            true
        }.getOrElse {
            MoodLog.w("SNIPPET_STORE_SAVE_FAIL " + it.javaClass.simpleName)
            false
        }
    }

    fun delete(context: Context, id: String) {
        synchronized(lock) {
            runCatching { write(context, read(context).filterNot { it.id == id }) }
                .onFailure { MoodLog.w("SNIPPET_STORE_DELETE_FAIL " + it.javaClass.simpleName) }
        }
    }

    /**
     * 解析失败只报日志、返回空列表：宁可这一屏看起来是空的，
     * 也不拿空数组回写，把用户写过的片段真删掉。
     */
    private fun read(context: Context): List<Snippet> {
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
        }.onFailure { MoodLog.w("SNIPPET_STORE_LOAD_FAIL " + it.javaClass.simpleName) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    // 逐条容错：一条写坏了只丢这一条，不拖累整个列表。
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString("id").trim()
                    val text = json.optString("text").trim().take(MAX_TEXT)
                    if (id.isEmpty() || text.isEmpty()) continue
                    add(Snippet(id, json.optString("title").trim().take(MAX_TITLE), text,
                        json.optLong("updatedAt", 0L)))
                }
            }
        }.getOrElse {
            MoodLog.w("SNIPPET_STORE_LOAD_FAIL " + it.javaClass.simpleName)
            emptyList()
        }
    }

    private fun write(context: Context, items: List<Snippet>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("text", item.text).put("updatedAt", item.updatedAt))
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_ITEMS, null)
        val editor = prefs.edit()
        // 旧内容解析不了时备份一份：真正的用户文本只有这一处，不能赌解析一定成功。
        if (previous != null && !parses(previous)) editor.putString(KEY_BAD, previous.take(64 * 1024))
        editor.putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun parses(raw: String): Boolean = runCatching { JSONArray(raw); true }.getOrDefault(false)
}
