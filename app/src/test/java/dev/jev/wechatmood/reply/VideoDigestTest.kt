package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDigestTest {
    private val settings = ReplySettings.fromInput("https://example.com", "key", "test-model")

    @Test fun `description is cleaned into one line and bounded`() {
        assertEquals("地下车库车内视角 中控屏在导航", VideoDigest.normalize("「地下车库车内视角\n中控屏在导航」"))
        assertNull(VideoDigest.normalize("   "))
        assertEquals(160, requireNotNull(VideoDigest.normalize("字".repeat(500))).length)
    }

    @Test fun `rejection text is not treated as a video description`() {
        assertFalse(VideoDigest.usable("抱歉，我无法查看视频"))
        assertFalse(VideoDigest.usable("没有收到视频，无法描述"))
        assertFalse(VideoDigest.usable("看不到画面"))
        assertTrue(VideoDigest.usable("一个人在车库倒车，中控屏在导航"))
    }

    @Test fun `video digest payload sends every frame as an image`() {
        val frames = listOf("data:image/jpeg;base64,AAA", "data:image/jpeg;base64,BBB",
            "data:image/jpeg;base64,CCC", "data:image/jpeg;base64,DDD")
        val payload = ReplyProtocol.videoDigestPayload(settings, frames, 14)
        val messages = payload.getJSONArray("messages")
        val content = messages.getJSONObject(1).getJSONArray("content")
        val images = (0 until content.length())
            .count { content.getJSONObject(it).optString("type") == "image_url" }
        assertEquals(4, images)
        assertTrue(messages.getJSONObject(0).getString("content").contains("14 秒"))
        assertEquals(false, payload.getBoolean("stream"))
    }

    @Test fun `reply hint tells the model it only saw key frames`() {
        val context = ReplyContext(talker = "wxid_a", messages = emptyList(),
            targetMedia = "视频", targetVideoDigest = "车库里的车正在倒车", focusMessageId = 7)
        val payload = ReplyProtocol.payload(settings, context, "", "", "", stream = false)
        val system = payload.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("关键帧"))
        assertTrue(system.contains("车库里的车正在倒车"))
    }

    @Test fun `reply hint admits when only the cover was captured`() {
        val context = ReplyContext(talker = "wxid_a", messages = emptyList(),
            targetMedia = "视频", targetImageDataUri = "data:image/jpeg;base64,AAA", focusMessageId = 7)
        val payload = ReplyProtocol.payload(settings, context, "", "", "", stream = false)
        val system = payload.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("封面"))
        assertFalse(system.contains("没取到画面内容"))
    }

    @Test fun `reply hint stays honest when the video content is missing`() {
        val context = ReplyContext(talker = "wxid_a", messages = emptyList(),
            targetMedia = "小视频", focusMessageId = 7)
        val payload = ReplyProtocol.payload(settings, context, "", "", "", stream = false)
        val system = payload.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("没取到画面内容"))
        assertFalse(system.contains("关键帧"))
    }

    /** 视频分支插在图片分支前面，最容易把图片的旧措辞挤掉，这里逐字锁住。 */
    @Test fun `image and emoji hints stay word for word`() {
        val image = ReplyContext(talker = "wxid_a", messages = emptyList(),
            targetMedia = "图片", targetImageDataUri = "data:image/jpeg;base64,AAA", focusMessageId = 7)
        val imageHint = ReplyProtocol.payload(settings, image, "", "", "", stream = false)
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(imageHint.contains("一张图片"))
        assertTrue(imageHint.contains("内容已随消息附上"))
        assertFalse(imageHint.contains("关键帧"))

        val emoji = ReplyContext(talker = "wxid_a", messages = emptyList(),
            targetMedia = "表情", targetImageDataUri = "data:image/jpeg;base64,AAA", focusMessageId = 7)
        val emojiHint = ReplyProtocol.payload(settings, emoji, "", "", "", stream = false)
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(emojiHint.contains("一个表情"))
    }

    @Test fun `reply target names a video as a video`() {
        val context = ReplyContext(talker = "wxid_a", messages = emptyList(),
            targetMedia = "视频", targetVideoDigest = "车库倒车", focusMessageId = 7)
        val payload = ReplyProtocol.payload(settings, context, "", "", "", stream = false)
        val evidence = payload.getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(evidence.contains("一段视频"))
        assertFalse(evidence.contains("一张视频"))
    }
}
