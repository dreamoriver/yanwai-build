package dev.jev.wechatmood.core

/** 模型输出里常见的 Markdown 标记，展示和复制前统一去掉。 */
object TextClean {
    private val MARKERS = Regex("[*_`#>]+")
    private val BULLET = Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE)
    private val BLANK_LINES = Regex("\\n{3,}")
    /** 昵称里真的会用到 # _ 这类字符，只去掉看不见的控制符与零宽空白。 */
    private val INVISIBLE = Regex("[\\u0000-\\u001F\\u007F\\u00A0\\u200B-\\u200F\\u2028\\u2029\\uFEFF]")

    fun plain(text: String): String = text
        .replace(BULLET, "")
        .replace(MARKERS, "")
        .replace(BLANK_LINES, "\n\n")
        .trim()

    /** 昵称专用：保留 # _ 和 emoji，只清掉渲染出来是空白的东西。 */
    fun name(text: String): String = text.replace(INVISIBLE, "").trim()
}
