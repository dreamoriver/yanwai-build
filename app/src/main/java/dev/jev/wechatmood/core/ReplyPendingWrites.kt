package dev.jev.wechatmood.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 角色应用在连不上设置服务时的本地待写队列。
 *
 * 用户在抽屉里选了一个角色，言外却被系统停掉了：主库写不进去，但选择本身应该立刻生效。
 * 这里先把「哪个联系人用了哪个角色」记在微信进程目录，等下次连上服务再补写主库。
 * 只存哈希后的会话键与角色 UUID，不含姓名、背景或聊天内容。
 */
object ReplyPendingWrites {
    private const val PREFS = "yanwai_reply_pending"
    private const val KEY = "queue"
    private const val MAX = 50

    data class Entry(val key: String, val roleId: String, val roleRevision: String)

    fun enqueue(context: Context, key: String, roleId: String, roleRevision: String) {
        runCatching {
            // 同一个联系人只保留最后一次选择，避免补写时把旧的决定覆盖回去。
            val merged = load(context).filterNot { it.key == key } + Entry(key, roleId, roleRevision)
            save(context, merged.takeLast(MAX))
            MoodLog.i("REPLY_PENDING_ENQUEUE size=${merged.size}")
        }
    }

    fun take(context: Context): List<Entry> = load(context)

    fun save(context: Context, entries: List<Entry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(JSONObject().put("key", entry.key).put("role", entry.roleId).put("rev", entry.roleRevision))
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, array.toString()).apply()
        }
    }

    private fun load(context: Context): List<Entry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val key = item.optString("key")
            val role = item.optString("role")
            val revision = item.optString("rev")
            if (key.isBlank() || role.isBlank() || revision.isBlank()) null else Entry(key, role, revision)
        }
    }.getOrElse { emptyList() }

    /** 只有「连不上服务」才值得本地兜底；账号变化、角色被删这类错误照样要报出来。 */
    fun isOffline(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any { message -> message.message?.let { it.contains("无法连接言外设置服务") || it.contains("设置尚未连接") } == true }
}
