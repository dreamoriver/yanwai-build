package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.MoodLog
import java.io.File

/**
 * 诊断用：定位图片/表情文件在微信私有目录里的实际位置与命名规则。
 * 只记录路径、文件大小和文件头，不含聊天内容。
 */
object MediaProbe {
    private val subs = listOf("image2", "image", "emoji", "emojiinfo", "imageinfo2")

    fun probe(target: MessageMetadata?) {
        if (target == null) return
        val path = target.imagePath
        if (path.isBlank()) {
            MoodLog.w("MEDIA_PROBE type=${target.type} path=EMPTY")
            return
        }
        val name = path.substringAfterLast('/')
        val keys = listOf(name, name.removeSuffix(".dat"), name.take(24)).filter { it.isNotBlank() }
        MoodLog.w("MEDIA_PROBE type=${target.type} path=${path.take(120)}")

        var listed = 0
        for (rootPath in listOf("/data/user/0/com.tencent.mm/MicroMsg", "/data/data/com.tencent.mm/MicroMsg")) {
            val root = File(rootPath)
            if (!root.isDirectory) {
                MoodLog.w("MEDIA_PROBE_ROOT missing $rootPath")
                continue
            }
            val dirs = runCatching { root.listFiles()?.filter { it.isDirectory } }.getOrNull().orEmpty()
            MoodLog.w("MEDIA_PROBE_ROOT ok $rootPath dirs=${dirs.size}")
            for (dir in dirs) {
                for (sub in subs) {
                    val folder = File(dir, sub)
                    if (!folder.isDirectory) continue
                    val files = runCatching { folder.listFiles() }.getOrNull().orEmpty()
                    val hit = files.firstOrNull { file -> keys.any { key -> file.name.contains(key) } }
                    if (hit != null) {
                        MoodLog.w("MEDIA_PROBE_HIT ${hit.absolutePath} size=${hit.length()} head=${head(hit)}")
                        return
                    }
                    if (listed < 6) {
                        listed++
                        val newest = files.maxByOrNull { it.lastModified() }
                        MoodLog.w("MEDIA_PROBE_DIR ${folder.path} files=${files.size} newest=${newest?.name?.take(70).orEmpty()} size=${newest?.length() ?: 0}")
                    }
                }
            }
        }
        MoodLog.w("MEDIA_PROBE_MISS keys=${keys.joinToString("|").take(120)} listed=$listed")
    }

    private fun head(file: File): String = runCatching {
        val bytes = ByteArray(16)
        val read = file.inputStream().use { it.read(bytes) }
        (0 until maxOf(read, 0)).joinToString("") { "%02x".format(bytes[it]) }
    }.getOrDefault("unreadable")
}
