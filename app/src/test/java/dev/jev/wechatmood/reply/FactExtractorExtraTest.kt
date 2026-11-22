package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转发摘要、图片描述这类「另一个模型的转述」进抽取通道。
 *
 * 关键约束：即使当前聊天没有消息也能整理；转述必须被标明不是聊天原话；
 * 条数和长度都要收住，不能拿一条超长摘要把提示词预算吃光。
 */
class FactExtractorExtraTest {
    private fun envelope(content: String) =
        """{"choices":[{"message":{"content":${JSONObject.quote(content)}},"finish_reason":"stop"}]}"""

    private fun settings(server: MockWebServer) =
        ReplySettings.fromInput(server.url("/v1/chat/completions").toString(), "sk-test", "test-model")

    private val now = 1_700_000_000_000L

    @Test fun `transcript without chat messages still accepts digest lines`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope("[]")))
        server.start()
        try {
            runBlocking {
                FactExtractor().extract(settings(server), emptyList(), now = now,
                    extra = listOf("【转发摘要】周六下午三点西门集合，记得带伞"))
            }
            val body = server.takeRequest().body.readUtf8()
            assertTrue("转述原文要进素材", body.contains("周六下午三点西门集合"))
            assertTrue("必须告诉模型这些行不是聊天原话", body.contains("另一个模型"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `only three digest lines are sent`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope("[]")))
        server.start()
        try {
            runBlocking {
                FactExtractor().extract(settings(server), emptyList(), now = now,
                    extra = (1..6).map { "【图片描述】第" + it + "张" })
            }
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("第3张"))
            assertFalse("超出条数的转述不该进提示词", body.contains("第4张"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a single digest line is capped`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope("[]")))
        server.start()
        try {
            val long = "【转发摘要】" + "长".repeat(3000)
            runBlocking {
                FactExtractor().extract(settings(server), emptyList(), now = now, extra = listOf(long))
            }
            val body = server.takeRequest().body.readUtf8()
            val message = JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")
            assertEquals("单条转述按 1200 字符截断（含前缀）", 1200, message.length)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `evidence beyond the chat window does not bind to a message`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope(
            """[{"kind":"约定","text":"周六下午三点集合","evidence":2,"occurred_at":"2026-11-22T15:00"}]""")))
        server.start()
        try {
            val facts = runBlocking {
                FactExtractor().extract(settings(server), emptyList(), now = now,
                    extra = listOf("【转发摘要】周六下午三点西门集合"))
            }
            assertEquals(1, facts.size)
            assertEquals("转述行没有聊天序号，不能绑到任何真实消息", 0L, facts[0].sourceChatMessageId)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `chat numbering survives when digest lines follow`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope(
            """[{"kind":"约定","text":"周六帮他搬家","evidence":1,"occurred_at":"2026-11-22T09:00"}]""")))
        server.start()
        try {
            val chat = ReplyMessage(99L, "对方", now, "周六帮我搬个家")
            val facts = runBlocking {
                FactExtractor().extract(settings(server), listOf(chat), now = now,
                    extra = listOf("【转发摘要】周日下午三点西门集合"))
            }
            assertEquals(99L, facts[0].sourceChatMessageId)
            val content = JSONObject(server.takeRequest().body.readUtf8())
                .getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue("聊天行要在转述之前，序号才对得上",
                content.indexOf("周六帮我搬个家") < content.indexOf("周日下午三点西门集合"))
        } finally {
            server.shutdown()
        }
    }
}
