package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合并转发里媒体占位的回填：这些都是纯函数，不碰 Android、不发请求。 */
class RecordMediaTest {
    private fun shot(index: Int, text: String, kind: String = RecordMedia.KIND_IMAGE) =
        RecordMedia.Shot(index, kind, text)

    @Test
    fun fillsPlaceholdersInRowOrder() {
        val text = "1. sw: [图片]\n2. sw: [图片]\n3. sw: [视频]"
        val shots = listOf(
            shot(0, "舞台截图"),
            shot(1, "评论区"),
            shot(2, "迎亲牌子", RecordMedia.KIND_VIDEO)
        )
        val out = RecordMedia.applyToText(text, shots, intArrayOf(0))
        assertTrue(out.contains("[图片]（内容：舞台截图）"))
        assertTrue(out.contains("[图片]（内容：评论区）"))
        assertTrue(out.contains("[视频]（封面：迎亲牌子）"))
    }

    @Test
    fun keepsVoiceAndChatRecordUntouched() {
        val out = RecordMedia.applyToText("[语音] [聊天记录] [图片]", listOf(shot(0, "外卖单")), intArrayOf(0))
        assertTrue(out.startsWith("[语音] [聊天记录] "))
        assertTrue(out.endsWith("[图片]（内容：外卖单）"))
    }

    @Test
    fun cursorIsSharedSoTwoImagesDiffer() {
        val shots = listOf(shot(0, "第一张"), shot(1, "第二张"))
        val cursor = intArrayOf(0)
        val first = RecordMedia.applyToText("[图片]", shots, cursor)
        val second = RecordMedia.applyToText("[图片]", shots, cursor)
        assertTrue(first.contains("第一张"))
        assertTrue(second.contains("第二张"))
    }

    /** 描述比图片少时，多出来的占位保持原样，不能拿同一张图重复填。 */
    @Test
    fun extraPlaceholdersStayBare() {
        val out = RecordMedia.applyToText("[图片][图片]", listOf(shot(0, "只有一张")), intArrayOf(0))
        assertTrue(out.contains("[图片]（内容：只有一张）"))
        assertTrue(out.endsWith("[图片]"))
    }

    @Test
    fun emptyShotsReturnOriginalText() {
        val text = "sw: [图片]"
        assertEquals(text, RecordMedia.applyToText(text, emptyList(), intArrayOf(0)))
    }

    @Test
    fun videoLabelIsCoverNotContent() {
        val out = RecordMedia.applyToText("[视频]", listOf(shot(0, "画面", RecordMedia.KIND_VIDEO)), intArrayOf(0))
        assertTrue(out.contains("（封面：画面）"))
        assertFalse(out.contains("（内容：画面）"))
    }

    /** 描述里的正文不能超过 120 字，否则一条转发能把上下文预算撑爆。 */
    @Test
    fun longDescriptionIsCapped() {
        val long = "字".repeat(400)
        val out = RecordMedia.applyToText("[图片]", listOf(shot(0, long)), intArrayOf(0))
        assertTrue(out.length < 160)
    }

    /** 摘要面板靠这个数字提示「点开记录一次即可补上」；单测环境没有缓存，占位数就是缺失数。 */
    @Test
    fun missingMediaCountsPlaceholdersWithoutCache() {
        val message = ForwardDigest.Message("群聊的聊天记录", true, listOf(
            ForwardDigest.Item(1, "sw", "[图片]", ""),
            ForwardDigest.Item(1, "sw", "说了句[视频]", "")
        ), 0, 0)
        assertEquals(2, RecordMedia.missing(message, "talker", 11303987L))
    }

    @Test
    fun missingMediaIsZeroWithoutPlaceholders() {
        val message = ForwardDigest.Message("t", false,
            listOf(ForwardDigest.Item(1, "sw", "纯文字", "")), 0, 0)
        assertEquals(0, RecordMedia.missing(message, "talker", 1L))
    }
}
