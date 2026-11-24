package dev.jev.wechatmood.core

import java.io.File

class DiagnosticJournal(private val file: File, private val limit: Int = 128 * 1024) {
    var diskFailure: String? = null
        private set
    private var memory = ""
    /**
     * 启动时磁盘上已有内容、但没读进来（读失败）。
     *
     * 这时 [memory] 是空串。若第一次 append 直接 writeText(memory)，磁盘上那份原有日志会被
     * 整体覆盖掉——读失败却写成功虽少见，但历史就没了。所以第一次落盘只追加，不覆盖。
     */
    private var unreadOnDisk = false

    init {
        runCatching { if (file.exists()) memory = file.readText().takeLast(limit) }
            .onFailure {
                diskFailure = it.javaClass.simpleName
                // 读失败不等于文件不存在：文件还在就当磁盘上有没读到的历史。
                unreadOnDisk = runCatching { file.exists() }.getOrDefault(false)
            }
    }

    /** 磁盘上大概有几个字符；用来判断还能不能继续追加。 */
    private var persisted = memory.length
    /** 写满之后攒下来、还没落盘的字符数。 */
    private var pending = 0
    /**
     * 写满之后再攒这么多才整体重写一次。
     *
     * 以前写满之后每次 append 都重写整个 128KB，而 MoodLog 是在调用线程同步写；
     * 攒够一批只重写一次，代价是进程被杀时最新的一小段只在内存里。
     */
    private val rewriteStep = limit / 4

    @Synchronized fun append(text: String) {
        memory = (memory + text).takeLast(limit)
        runCatching {
            if (unreadOnDisk) {
                // 读失败那次留下的历史还在磁盘上，先只追加，不拿空 memory 去 writeText。
                // 追加之后磁盘从这一段起与 memory 一致，按「已写满」记；之后的第一批批量重写
                // 才会整体替换文件——代价是读不到的那段旧历史仍可能那时被覆盖，但不会再在
                // 第一次落盘就被抹掉，且中间这批新日志一定先落盘了。
                file.appendText(text)
                unreadOnDisk = false
                persisted = limit
                pending = 0
            } else if (diskFailure == null && persisted + text.length <= limit) {
                // 还没写满：追加最省，磁盘和内存始终一致。
                file.appendText(text)
                persisted += text.length
            } else {
                pending += text.length
                if (diskFailure != null || pending >= rewriteStep) {
                    file.writeText(memory)
                    persisted = memory.length
                    pending = 0
                }
            }
            diskFailure = null
        }.onFailure { diskFailure = it.javaClass.simpleName }
    }

    @Synchronized fun read(): String = memory
}
