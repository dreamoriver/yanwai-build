package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SimulationTest {

    private fun branch(stance: String, theirs: String, mine: String,
        basis: String = "他上一条说了在忙", move: String = "先别追问") =
        SimulationBranch(stance, theirs, basis, mine, move)

    @Test fun `clean keeps three distinct stances in order`() {
        val result = Simulation.clean(listOf(
            branch("更可能", "那行吧，我自己去", "那你去吧，我不等你了"),
            branch("也说不定", "等下，我忙完就来", "好，那我等你"),
            branch("最坏", "随你，以后别找我", "我明天再找你聊"),
        ), "那就不去了")
        assertEquals(3, result.branches.size)
        assertEquals(0, result.dropped)
        assertEquals(listOf("更可能", "也说不定", "最坏"), result.branches.map { it.stance })
    }

    @Test fun `clean drops a repeated stance instead of showing duplicates`() {
        val result = Simulation.clean(listOf(
            branch("更可能", "那行吧，我自己去", "那你去吧"),
            branch("更可能", "那就这样吧", "行"),
        ), "")
        assertEquals(1, result.branches.size)
        assertEquals(1, result.dropped)
    }

    @Test fun `clean drops a branch that merely repeats my candidate`() {
        val candidate = "那就不去了，改天再说吧"
        val result = Simulation.clean(listOf(
            branch("更可能", "那就不去了，改天再说吧", "那你忙"),
            branch("最坏", "行，那以后都别约了", "那先这样，改天我找你"),
        ), candidate)
        assertEquals(1, result.branches.size)
        assertEquals("行，那以后都别约了", result.branches[0].theirs)
        assertEquals(1, result.dropped)
    }

    @Test fun `clean drops blank fields rather than inventing content`() {
        val result = Simulation.clean(listOf(
            branch("更可能", "  ", "那我等你"),
            branch("", "那行吧", "好"),
            branch("最坏", "随你", "  "),
        ), "")
        assertTrue(result.isEmpty)
        assertEquals(3, result.dropped)
    }

    /** 「回「看到了」再问他刷了多久」这类动作说明不能被当成一句话发出去。 */
    @Test fun `clean drops a mine field that is really an instruction`() {
        val result = Simulation.clean(listOf(
            branch("更可能", "刷好了", "回「看到了」再问他刷了多久"),
            branch("也说不定", "还没刷完", "先别催，等他说完"),
            branch("最坏", "不想弄了", "行，那你别折腾了"),
        ), "刷好没")
        assertEquals(1, result.branches.size)
        assertEquals("行，那你别折腾了", result.branches.single().mine)
        assertEquals(2, result.dropped)
    }

    @Test fun `clean drops the 回一句 wording that is still an instruction`() {
        val result = Simulation.clean(listOf(
            branch("更可能", "发你了", "回一句让他发原图"),
            branch("最坏", "不想发", "那算了，不看了"),
        ), "发我一下")
        assertEquals(1, result.branches.size)
        assertEquals("那算了，不看了", result.branches.single().mine)
    }

    @Test fun `parse treats a literal null field as empty instead of text`() {
        val content = JSONObject().put("branches", JSONArray()
            .put(JSONObject().put("stance", "更可能").put("reply", "那行吧")
                .put("basis", "依据不足").put("my_next", "那你去忙吧").put("move", "null"))
        ).toString()
        val branch = SimulationProtocol.parse(body(content), "那就不去了").branches.single()
        assertEquals("", branch.move)
        assertEquals("那你去忙吧", branch.mine)
    }

    @Test fun `clean truncates long reply by code point and never splits an emoji`() {
        val longEmoji = "😀".repeat(40)
        val result = Simulation.clean(listOf(branch("更可能", longEmoji, "那我等你")), "")
        val text = result.branches.single().theirs
        assertTrue(text.length <= Simulation.MAX_REPLY)
        assertFalse(text.last().isHighSurrogate())
    }

    @Test fun `clean caps branch count at three`() {
        val result = Simulation.clean((1..5).map { branch("档位$it", "回应$it", "我那我也$it") }, "")
        assertEquals(Simulation.MAX_BRANCHES, result.branches.size)
    }

    @Test fun `overlap detects a verbatim repeat and ignores punctuation`() {
        assertTrue(Simulation.overlap3("那就不去了，改天再说", "那就不去了 改天再说") > 0.9)
        assertTrue(Simulation.overlap3("随你便吧", "周末一起吃饭") < 0.2)
    }

    @Test fun `parse reads theirs, mine and move separately`() {
        val content = JSONObject().put("branches", JSONArray()
            .put(JSONObject().put("stance", "更可能").put("reply", "那行吧，我自己去")
                .put("basis", "他上一条说了在忙").put("my_next", "那你去忙，我晚上再问你")
                .put("move", "别追第二句"))
            .put(JSONObject().put("stance", "最坏").put("reply", "随你，以后别找我")
                .put("basis", "依据不足").put("my_next", "那先这样，改天我找你")
                .put("move", ""))
        ).toString()
        val result = SimulationProtocol.parse(body(content), "那就不去了")
        assertEquals(2, result.branches.size)
        assertEquals("那你去忙，我晚上再问你", result.branches[0].mine)
        assertEquals("别追第二句", result.branches[0].move)
        assertEquals("依据不足", result.branches[1].basis)
        assertNotEquals(result.branches[0].mine, result.branches[0].theirs)
    }

    @Test fun `parse drops a body without json object`() {
        val result = SimulationProtocol.parse(body("抱歉，我无法回答"), "那就不去了")
        assertTrue(result.isEmpty)
    }

    @Test fun `parse keeps truncation error so the client can retry with a bigger budget`() {
        val body = JSONObject()
            .put("choices", JSONArray().put(JSONObject()
                .put("finish_reason", "length")
                .put("message", JSONObject().put("role", "assistant").put("content", "{\"branches\":[]}"))))
            .toString()
        val error = runCatching { SimulationProtocol.parse(body, "那就不去了") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("截断"))
    }

    @Test fun `payload asks for a sendable sentence and a separate move hint`() {
        val settings = ReplySettings.fromInput("https://example.com", "key", "test-model")
        val context = ReplyContext("a", listOf(ReplyMessage(1, "对方", 1700000000000, "周末想去哪？")))
        val payload = SimulationProtocol.payload(settings, context, "那就不去了", "资料", correction = "分支雷同")
        assertEquals("test-model", payload.getString("model"))
        assertFalse(payload.getBoolean("stream"))
        val messages = payload.getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("my_next"))
        assertTrue(system.contains("move"))
        assertTrue(system.contains("更可能"))
        assertTrue(system.contains("依据不足"))
        assertTrue(system.contains("分支雷同"))
        assertTrue(system.contains("资料"))
        val evidence = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("那就不去了", evidence.getString("candidate"))
        assertEquals("那就不去了", evidence.getString("draft"))
    }

    private fun body(content: String) = JSONObject()
        .put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop")
            .put("message", JSONObject().put("role", "assistant").put("content", content))))
        .toString()
}
