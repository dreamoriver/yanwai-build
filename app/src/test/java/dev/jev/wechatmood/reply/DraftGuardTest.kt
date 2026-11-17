package dev.jev.wechatmood.reply

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DraftGuardTest {
    @Test fun `hostile draft is flagged without calling the model`() {
        assertNotNull(DraftGuard.hint("操你妈的"))
        assertNotNull(DraftGuard.hint("你是不是傻逼"))
        assertNotNull(DraftGuard.hint("你麻痹"))
    }

    @Test fun `ordinary drafts pass the local guard`() {
        assertNull(DraftGuard.hint("行吧"))
        assertNull(DraftGuard.hint("那我先不折腾了，你定？"))
        assertNull(DraftGuard.hint("滚去睡觉"))  // 单字「滚」不算，避免误伤
    }

    @Test fun `hint names the matched word for traceability`() {
        val hint = DraftGuard.hint("操你妈的")
        assertNotNull(hint)
        assert(hint!!.contains("攻击性"))
    }
}
