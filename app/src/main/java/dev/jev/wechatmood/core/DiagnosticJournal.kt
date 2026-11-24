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

    /** 磁盘上大概有几个字符；用来判断什么时候该把文件截回上限。 */
    private var persisted = memory.length
    /** 文件最多长到上限的 1.5 倍，再整体重写一次截回 limit。 */
    private val rewriteAt = limit + limit / 2

    @Synchronized fun append(text: String) {
        memory = (memory + text).takeLast(limit)
        runCatching {
            if (unreadOnDisk) {
                // 读失败那次留下的历史还在磁盘上，先只追加，不拿空 memory 去 writeText。
                file.appendText(text)
                unreadOnDisk = false
                persisted = limit
            } else if (diskFailure == null) {
                // 每行只追加自己这一条：既及时落盘（进程被杀不丢最近日志），
                // 又不会像以前那样写满之后每行都重写整个 128KB。
                file.appendText(text)
                persisted += text.length
                if (persisted > rewriteAt) {
                    file.writeText(memory)
                    persisted = memory.length
                }
            } else {
                // 上次写失败（磁盘满、权限变化）：用内存里的全文重试一次。
                file.writeText(memory)
                persisted = memory.length
            }
            diskFailure = null
        }.onFailure { diskFailure = it.javaClass.simpleName }
    }

    @Synchronized fun read(): String = memory
}
