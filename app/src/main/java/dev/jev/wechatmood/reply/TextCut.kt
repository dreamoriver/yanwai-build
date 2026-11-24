package dev.jev.wechatmood.reply

/**
 * 按 Char 截断的安全版：直接 `take(n)` 会在切点落进代理对时留下一个孤立的高位代理，
 * emoji、生僻字（补充平面）就变成半截乱码，塞进提示词或日志都是坏数据。
 * 切点正好劈开代理对时往前退一位。整个包只留这一份，上下文、事实提取、知识库裁剪共用。
 */
internal fun String.takeSafely(max: Int): String {
    if (max <= 0) return ""
    if (length <= max) return this
    val cut = if (this[max - 1].isHighSurrogate() && this[max].isLowSurrogate()) max - 1 else max
    return substring(0, cut)
}
