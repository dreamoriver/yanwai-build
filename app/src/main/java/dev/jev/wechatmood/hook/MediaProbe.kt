package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.MoodLog
import java.io.File

/**
 * 诊断用：把图片/表情消息的本地路径与文件头打印到模块日志。
 * 不含任何聊天内容，只记录路径片段、大小和前 16 字节（判断是否加密）。
 */
object MediaProbe {
    fun probe(target: MessageMetadata?) {
        if (target == null) return
        val path = target.imagePath
        if (path.isBlank()) {
            MoodLog.w("MEDIA_PROBE type=${target.type} path=EMPTY")
            return
        }
        MoodLog.i("MEDIA_PROBE type=${target.type} path=${path.take(160)}")
        val name = path.substringAfterLast('/')
        val keys = listOf(name, name.removeSuffix(".dat"), name.take(24)).filter { it.isNotBlank() }
        val roots = listOf(
            File("/data/user/0/com.tencent.mm/MicroMsg"),
            File("/data/data/com.tencent.mm/MicroMsg")
        )
        var hits = 0
        var scanned = 0
        for (root in roots) {
            val dirs = runCatching { root.listFiles()?.filter { it.isDirectory } }.getOrNull().orEmpty()
            for (dir in dirs) {
                for (sub in listOf("image2", "image", "emoji", "emojiinfo")) {
                    val folder = File(dir, sub)
                    if (!folder.isDirectory) continue
                    val files = runCatching { folder.listFiles() }.getOrNull().orEmpty()
                    scanned += files.size
                    for (file in files) {
                        if (keys.none { key -> file.name.contains(key) }) continue
                        hits++
                        MoodLog.i("MEDIA_PROBE_HIT ${file.absolutePath} size=${file.length()} head=${head(file)}")
                        if (hits >= 4) return
                    }
                }
            }
        }
        MoodLog.w("MEDIA_PROBE_MISS keys=${keys.joinToString("|")} scanned=$scanned")
    }

    private fun head(file: File): String = runCatching {
        val bytes = ByteArray(16)
        val read = file.inputStream().use { it.read(bytes) }
        (0 until maxOf(read, 0)).joinToString("") { "%02x".format(bytes[it]) }
    }.getOrDefault("unreadable")
}
