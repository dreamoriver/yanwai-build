package dev.jev.wechatmood.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Resource contracts: choices remain accessible, keys stay out of saved view state. */
class SettingsLayoutTest {
    @Test fun replyPageOffersRoleManagementAndKeepsOnlyUsefulInstructions() {
        val nodes = elements("activity_main")
        assertFalse(nodes.any { it.getAttribute("android:text").contains("不需要额外开启许可") })
        assertTrue(nodes.any { it.getAttribute("android:text").contains("在微信聊天中展开「＋」") })
        assertTrue(nodes.any { it.getAttribute("android:id") == "@+id/roleManager" })
    }
    @Test fun manualReplyDoesNotRequireAnExtraPermissionSwitch() {
        assertFalse(elements("reply_settings").any { it.getAttribute("android:id") == "@+id/replyConsent" })
    }
    private fun elements(name: String): List<Element> {
        val file = File("src/main/res/layout/$name.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun secretsNeverEnterAndroidSavedViewState() {
        listOf("activity_main", "intent_settings", "reply_settings").forEach { layout ->
            elements(layout).filter { it.getAttribute("android:inputType") == "textPassword" }.forEach {
                assertEquals("$layout must not persist keys in instance state", "false", it.getAttribute("android:saveEnabled"))
                assertEquals("no", it.getAttribute("android:importantForAutofill"))
            }
        }
    }

}
