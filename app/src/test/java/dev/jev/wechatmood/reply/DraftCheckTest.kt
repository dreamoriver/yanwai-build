package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftCheckTest {
    private fun body(content: String): String = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)))).toString()

    @Test fun `issues and rewrite are parsed`() {
        val check = DraftCheckProtocol.parse(body("""{"issues":["可能显得在赌气"],"rewrite":"那我先不折腾了，你定？"}"""))
        assertEquals(listOf("可能显得在赌气"), check.issues)
        assertEquals("那我先不折腾了，你定？", check.rewrite)
        assertTrue(!check.isEmpty)
    }

    @Test fun `at most two issues are kept`() {
        val check = DraftCheckProtocol.parse(body("""{"issues":["一","二","三"],"rewrite":""}"""))
        assertEquals(2, check.issues.size)
        assertNull(check.rewrite)
    }

    @Test fun `null rewrite token becomes null`() {
        assertNull(DraftCheckProtocol.parse(body("""{"issues":[],"rewrite":"null"}""")).rewrite)
    }

    @Test fun `empty result is treated as no problem`() {
        val check = DraftCheckProtocol.parse(body("""{"issues":[],"rewrite":""}"""))
        assertTrue(check.isEmpty)
    }

    @Test fun `prose around the object is tolerated`() {
        val check = DraftCheckProtocol.parse(body("""检查完了：{"issues":["太生硬"],"rewrite":"好呀"} 以上。"""))
        assertEquals(listOf("太生硬"), check.issues)
    }

    @Test fun `malformed body yields an empty result`() {
        assertTrue(DraftCheckProtocol.parse("not json").isEmpty)
        assertTrue(DraftCheckProtocol.parse(body("没有可说的")).isEmpty)
    }

    @Test fun `cache hits only for the same draft within the window`() {
        val cache = DraftCheckCache(windowMs = 1000)
        val check = DraftCheck(listOf("太冷"), null)
        cache.put("t1", "行吧", check, now = 1000)
        assertEquals(check, cache.get("t1", "行吧", now = 1500))
        assertNull(cache.get("t1", "行吧", now = 2500))
        assertNull(cache.get("t1", "好的", now = 1500))
    }

    @Test fun `payload keeps recent messages and the draft`() {
        val settings = ReplySettings.fromInput("https://example.com/v1/chat/completions", "key", "model")
        val context = ReplyContext("talker@chatroom", listOf(
            ReplyMessage(1, "对方", 1000, "这周末有空吗"),
            ReplyMessage(2, "我", 2000, "看情况"),
        ))
        val payload = DraftCheckProtocol.payload(settings, context, "那你自己看着办吧", ReplyRelationship.PARTNER)
        val evidence = JSONObject(payload.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("那你自己看着办吧", evidence.getString("draft"))
        assertEquals(2, evidence.getJSONArray("messages").length())
        assertEquals("model", payload.getString("model"))
        assertTrue(!payload.getBoolean("stream"))
    }
}
