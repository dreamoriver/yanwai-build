package dev.jev.wechatmood.reply

/**
 * 对话沙盘的一条分支。
 *
 * 字段分工很关键，之前把两者混成一个字段，结果「动作提示」被填进了聊天框：
 * - [theirs] 他可能说的一句话，直接显示；
 * - [mine] 我可以直接发的一句原话，能原样粘到聊天框，不带任何说明；
 * - [move] 给我的动作提示（如「先别追问」），只在界面小字显示，不进输入框。
 *
 * 档位只用文字（更可能 / 也说不定 / 最坏），不给百分比：
 * 概率是 JEV 的事，LLM 编出来的数字是假精确。
 */
data class SimulationBranch(
    val stance: String,
    val theirs: String,
    val basis: String,
    val mine: String,
    val move: String = "",
)

data class SimulationResult(val branches: List<SimulationBranch> = emptyList(), val dropped: Int = 0) {
    val isEmpty: Boolean get() = branches.isEmpty()
}

/**
 * 沙盘输出的后置校验。
 *
 * 模型爱出的坏输出：三个分支互为同义改写、把我说的话复述一遍当回应、
 * 把「回一句…再问他…」这种动作说明塞进可发送字段。
 * 档位去重、3-gram 重合度、引号/指令式过滤把这几类丢掉；不足三条就如实显示几条。
 */
object Simulation {
    const val MAX_BRANCHES = 3
    const val MAX_REPLY = 60
    const val MAX_BASIS = 60
    const val MAX_MINE = 40
    const val MAX_MOVE = 20
    /** 和候选原句的重合度超过这个值，就是在复述我的话。 */
    private const val REPEAT_OVERLAP = 0.6
    /**
     * 动作说明的特征：以「回「…」」开头，或以这些「怎么做」的词开头。
     * 只认句首，避免把「回头我找你」「说实话我不想去」这类真话误杀。
     */
    private val MOVE_HINT = Regex(
        "^回[「『\"']|^回(一句|个|他|她)|^(先别|别再|再问|问他|问她|问一句|看看|看一下|发一句|说一句|约他|约她|提一句|告诉他|告诉她|然后)")

    fun clean(raw: List<SimulationBranch>, candidate: String): SimulationResult {
        val seen = mutableSetOf<String>()
        val kept = mutableListOf<SimulationBranch>()
        var dropped = 0
        for (branch in raw) {
            // 按码点截断：take() 劈开代理对会把 emoji 变成半个字符。
            val stance = branch.stance.trim().takeSafely(12)
            val theirs = branch.theirs.trim().takeSafely(MAX_REPLY)
            val basis = branch.basis.trim().takeSafely(MAX_BASIS)
            val mine = branch.mine.trim().takeSafely(MAX_MINE)
            val move = branch.move.trim().takeSafely(MAX_MOVE)
            if (stance.isBlank() || theirs.isBlank() || mine.isBlank()) {
                dropped++
                continue
            }
            if (!seen.add(stance)) {
                dropped++
                continue
            }
            if (candidate.isNotBlank() && overlap3(theirs, candidate) > REPEAT_OVERLAP) {
                dropped++
                continue
            }
            // 可发送的那句里出现引号或动作说明的特征，就不能当成一句话发出去。
            if (mine.contains('「') || mine.contains('」') || MOVE_HINT.containsMatchIn(mine)) {
                dropped++
                continue
            }
            kept += SimulationBranch(stance, theirs, basis, mine, move)
        }
        return SimulationResult(kept.take(MAX_BRANCHES), dropped)
    }

    /** 中文按字切 3-gram 就够，不引入分词。 */
    internal fun overlap3(a: String, b: String): Double {
        val left = grams(a)
        val right = grams(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.count { it in right }.toDouble() / left.size
    }

    private fun grams(text: String): Set<String> {
        val chars = text.filter { it.isLetterOrDigit() }
        if (chars.length < 3) return emptySet()
        return (0..chars.length - 3).mapTo(HashSet()) { chars.substring(it, it + 3) }
    }
}
