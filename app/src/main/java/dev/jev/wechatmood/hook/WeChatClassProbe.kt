package dev.jev.wechatmood.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.MoodLog
import java.lang.reflect.Modifier

/**
 * 只探测、不改行为：找出微信里负责「wxid → 显示名」的类和方法。
 *
 * 这版微信不把非好友昵称落库，名字只在内存里。要读它，先得知道是哪个类存的。
 * 微信把类名混淆成单字母，但方法名常常留着（getDisplayName / getNickName 之类），
 * 所以这里按方法名和字段名去扫，扫到之后再谈 hook。
 */
object WeChatClassProbe {
    private const val PREFIX = "com.tencent.mm"
    private const val LIMIT = 40_000
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var loader: ClassLoader? = null
    @Volatile private var installed = false

    private val METHOD_WORDS = Regex("(?i)(displayname|nickname|nick_name|getremark|conremark|getdisplay)")
    private val FIELD_WORDS = Regex("(?i)(displayname|nickname|remark)")

    /** 模块初始化时调用：只记录类名，不碰方法，开销很小。 */
    @Synchronized fun install(classLoader: ClassLoader) {
        if (installed) return
        loader = classLoader
        installed = true
        runCatching {
            val method = ClassLoader::class.java.getDeclaredMethod(
                "loadClass", String::class.java, Boolean::class.javaPrimitiveType)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (!name.startsWith(PREFIX)) return
                    if (loaded.size < LIMIT) loaded.add(name)
                }
            })
        }.onFailure { MoodLog.w("CLASS_PROBE_HOOK_FAILED " + it.javaClass.simpleName) }
    }

    fun loadedCount(): Int = loaded.size

    /**
     * 扫一遍：方法名/字段名带 nickname、displayName、remark 的类都记下来。
     * 有总时长预算，超时就停，返回已经找到的部分。
     */
    fun scan(budgetMillis: Long = 20_000): String {
        val loader = loader ?: return "probe_unavailable\n"
        val out = StringBuilder()
        val names = loaded.toList()
        out.append("probe_loaded=").append(names.size).append('\n')
        val deadline = System.currentTimeMillis() + budgetMillis
        val hits = LinkedHashMap<String, String>()
        val fields = LinkedHashMap<String, String>()
        var scanned = 0
        var timedOut = false
        for (name in names) {
            if (System.currentTimeMillis() > deadline) { timedOut = true; break }
            if (name.contains("$$")) continue
            val clazz = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            scanned++
            runCatching { clazz.declaredMethods }
                .getOrDefault(emptyArray())
                .filter { METHOD_WORDS.containsMatchIn(it.name) }
                .forEach { method ->
                    if (hits.size < 60) {
                        val signature = method.name + "(" +
                            method.parameterTypes.joinToString(",") { it.simpleName } + "):" +
                            method.returnType.simpleName
                        val static = if (Modifier.isStatic(method.modifiers)) " static" else ""
                        hits[name + "#" + signature + static] =
                            (method.parameterTypes.firstOrNull()?.simpleName ?: "-")
                    }
                }
            runCatching { clazz.declaredFields }
                .getOrDefault(emptyArray())
                .filter { FIELD_WORDS.containsMatchIn(it.name) }
                .forEach { field ->
                    if (fields.size < 40) {
                        fields[name + "#" + field.name + ":" + field.type.simpleName] =
                            if (Modifier.isStatic(field.modifiers)) "static" else "instance"
                    }
                }
        }
        hits.keys.forEach { out.append("probe_method=").append(it).append('\n') }
        fields.keys.forEach { out.append("probe_field=").append(it).append('\n') }
        out.append("probe_scanned=").append(scanned)
            .append(" timeout=").append(timedOut)
            .append(" methods=").append(hits.size)
            .append(" fields=").append(fields.size).append('\n')
        MoodLog.i("CLASS_PROBE scanned=" + scanned + " methods=" + hits.size + " fields=" + fields.size)
        return out.toString()
    }
}
