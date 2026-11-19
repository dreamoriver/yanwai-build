package dev.jev.wechatmood.hook

import android.content.Context
import org.json.JSONObject

/**
 * 手动补的群成员昵称。
 *
 * 这版微信不把非好友的昵称落库（rcontact 里只有 type 和头像位），
 * 除了引用消息里能捡到一些，剩下的只能自己记一份，补过一次就永久生效。
 */
object GroupNameStore {
    private const val PREFS = "yanwai_group_names"

    fun load(context: Context, talker: String): MutableMap<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(talker, null) ?: return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                val value = json.optString(key).trim()
                if (value.isNotEmpty()) out[key] = value
            }
            out
        }.getOrDefault(mutableMapOf())
    }

    fun save(context: Context, talker: String, user: String, name: String) {
        if (user.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = load(context, talker)
        if (name.isBlank()) map.remove(user) else map[user] = name.trim()
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString(talker, json.toString()).apply()
    }
}
