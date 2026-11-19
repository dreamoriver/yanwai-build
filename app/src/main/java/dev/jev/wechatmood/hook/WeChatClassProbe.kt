package dev.jev.wechatmood.hook

import android.content.Context
import dev.jev.wechatmood.core.MoodLog
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/**
 * 只探测、不改行为：找出微信里负责「wxid → 显示名」的类和方法。
 *
 * 这版微信不把非好友昵称落库，名字只在内存里。要读它，先得知道是哪个类存的。
 * 微信把类名混淆成单字母，但包路径和字段名往往留着（rcontact 的 field_fromUsername 就是例子），
 * 所以这里直接从 apk 的 dex 里把类名全捞出来，再按方法名/字段名筛。
 *
 * 上一版靠 hook ClassLoader#loadClass 收集类名，只有 119 个，微信根本不走那条路。
 */
object WeChatClassProbe {
    private const val CHUNK = 4 * 1024 * 1024
    private const val OVERLAP = 256
    private const val CLASS_LIMIT = 40_000
    private val CLASS_IN_DEX = Regex("com/tencent/mm/[A-Za-z0-9_$/]{2,80}")
    private val METHOD_WORDS = Regex("(?i)(displayname|nickname|nick_name|getremark|conremark|getdisplay)")
    private val FIELD_WORDS = Regex("(?i)(displayname|nickname|remark)")
    /** 包路径没混淆，优先扫这些包，命中率高得多。 */
    private val PRIORITY_PACKAGES = listOf("storage", "model", "chatroom", "contact")

    /** 从 apk 的 dex 里把微信类名捞出来：不信 ClassLoader，直接读文件。 */
    fun apkClassNames(context: Context): Set<String> {
        val info = context.applicationInfo
        val paths = listOfNotNull(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        val out = HashSet<String>()
        for (path in paths.filterNotNull()) {
            runCatching {
                ZipFile(path).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                        .forEach { entry ->
                            zip.getInputStream(entry).use { input ->
                                val buffer = ByteArray(CHUNK)
                                var tail = ""
                                while (out.size < CLASS_LIMIT) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    val text = tail + String(buffer, 0, read, Charsets.ISO_8859_1)
                                    CLASS_IN_DEX.findAll(text).forEach { match ->
                                        if (out.size < CLASS_LIMIT) out.add(match.value.replace('/', '.'))
                                    }
                                    tail = if (text.length > OVERLAP) text.takeLast(OVERLAP) else text
                                }
                            }
                        }
                }
            }.onFailure { MoodLog.w("CLASS_PROBE_APK_FAILED " + it.javaClass.simpleName) }
        }
        return out
    }

    /**
     * 扫一遍：方法名/字段名带 nickname、displayName、remark 的类都记下来。
     * 包路径像 storage/model 的先扫，有总时长预算，超时就停。
     */
    fun scan(context: Context, budgetMillis: Long = 30_000): String {
        val out = StringBuilder()
        val all = runCatching { apkClassNames(context) }.getOrDefault(emptySet())
        out.append("probe2_classes=").append(all.size).append('\n')
        if (all.isEmpty()) {
            out.append("probe2_failed\n")
            return out.toString()
        }
        val loader = context.classLoader
        val ordered = all.sortedBy { name ->
            val lower = name.lowercase()
            PRIORITY_PACKAGES.indexOfFirst { lower.contains(".$it.") }.let { if (it < 0) 99 else it }
        }
        val deadline = System.currentTimeMillis() + budgetMillis
        val hits = LinkedHashMap<String, String>()
        val fields = LinkedHashMap<String, String>()
        var scanned = 0
        var timedOut = false
        for (name in ordered) {
            if (System.currentTimeMillis() > deadline) { timedOut = true; break }
            if (hits.size >= 80 && fields.size >= 40) break
            if (name.contains("$$")) continue
            val clazz = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            scanned++
            runCatching { clazz.declaredMethods }
                .getOrDefault(emptyArray())
                .filter { METHOD_WORDS.containsMatchIn(it.name) }
                .forEach { method ->
                    if (hits.size < 80) {
                        val signature = method.name + "(" +
                            method.parameterTypes.joinToString(",") { it.simpleName } + "):" +
                            method.returnType.simpleName
                        val static = if (Modifier.isStatic(method.modifiers)) " static" else ""
                        hits[name + "#" + signature + static] = "1"
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
        hits.keys.forEach { out.append("probe2_method=").append(it).append('\n') }
        fields.keys.forEach { out.append("probe2_field=").append(it).append('\n') }
        out.append("probe2_scanned=").append(scanned)
            .append(" timeout=").append(timedOut)
            .append(" methods=").append(hits.size)
            .append(" fields=").append(fields.size).append('\n')
        MoodLog.i("CLASS_PROBE2 classes=" + all.size + " scanned=" + scanned +
            " methods=" + hits.size + " fields=" + fields.size)
        return out.toString()
    }
}
