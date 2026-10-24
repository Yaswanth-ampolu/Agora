package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.remote.RemoteActivity
import com.newoether.agora.remote.RemoteMessage
import com.newoether.agora.remote.projectRemoteMessages
import com.newoether.agora.webui.WebToolBody
import com.newoether.agora.webui.webToolDetail
import com.newoether.agora.webui.webToolDocument
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class RemoteToolDetailRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
    private fun segment(name: String, args: String, result: String? = null, state: String = "succeeded") =
        MessageSegment(type = "tool", toolName = name, toolArgs = args, toolResult = result,
            toolState = state, remoteToolSource = "filo")

    @Test fun projectionPreservesNativeIdentityAndMarksRemoteOnly() {
        val activity = RemoteActivity("tool", "Read", """{"file_path":"a.kt"}""", "1→literal", "succeeded")
        val projected = projectRemoteMessages(listOf(RemoteMessage("tool", turnId = "turn", clientId = null, role = "assistant", text = "", timestamp = null, activity = activity)))
            .single().segments!!.single()
        assertEquals("Read", projected.toolName)
        assertEquals(activity.arguments, projected.toolArgs)
        assertEquals(activity.result, projected.toolResult)
        assertEquals("filo", projected.remoteToolSource)
        assertTrue(resources.toolDetailPresentation(projected).body is ToolDetailBody.FileContent)
    }

    @Test fun sharedComposeBodyShowsCodeDiffAndTerminalWithoutEscapedPayloads() {
        val value = mutableStateOf(segment("Write", """{"file_path":"a.kt","content":"first\nsecond"}""", "written"))
        compose.setContent { MaterialTheme { ToolDetailContent(value.value) { _, _ -> } } }
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        compose.runOnIdle { value.value = segment("Edit", """{"file_path":"a.kt","old_string":"old","new_string":"new"}""", "done") }
        compose.onNodeWithText("old").assertExists()
        compose.onNodeWithText("new").assertExists()
        compose.onAllNodesWithText("old_string").assertCountEquals(0)
        compose.runOnIdle { value.value = segment("Bash", """{"command":"echo hello"}""", """{"output":"hello","exit_code":7}""", "failed") }
        compose.onNodeWithText("echo hello").assertExists()
        compose.onNodeWithText("hello").assertExists()
        assertEquals("7", (mapRemoteToolDetail("filo", "Bash", value.value.toolArgs, value.value.toolResult, "failed")!!.body as ToolDetailBody.Shell).status)
    }

    @Test fun sharedWebProjectionKeepsReceiptsAndEmptySearchMetadata() {
        val write = segment("Write", """{"file_path":"a.kt","content":"literal"}""", "written")
        val web = resources.webToolDetail(write)
        assertEquals("FILE_WRITE", web.kind)
        assertEquals("literal", (web.body as WebToolBody.FileContent).content)
        assertEquals(resources.webToolDocument("written"), web.supplemental.single())
        val search = segment("web_search", """{"query":"x"}""", """{"results":[],"truncated":true}""")
        assertEquals(resources.webToolDocument("""{"truncated":true}"""),
            resources.webToolDetail(search).supplemental.single())
        val stopped = resources.toolDetailPresentation(segment("Read", """{"file_path":"a"}""", state = "stopped"))
        assertTrue(stopped.body is ToolDetailBody.Stopped)
    }

    @Test fun runningReadCanBecomeCodeAndMalformedResultKeepsOriginalJson() {
        val value = mutableStateOf(segment("Read", """{"file_path":"a.kt"}""", state = "running"))
        compose.setContent { MaterialTheme { ToolDetailContent(value.value) { _, _ -> } } }
        compose.runOnIdle { value.value = value.value.copy(toolResult = " 9→val native = true", toolState = "succeeded") }
        compose.onNodeWithText("val native = true").assertExists()
        compose.onAllNodesWithText("9→val native = true").assertCountEquals(0)
        val invalid = segment("file_read", "{}", """{"secretField":"kept"}""")
        val fallback = resources.toolDetailPresentation(invalid)
        assertEquals(invalid.toolArgs, fallback.arguments)
        assertEquals(listOf(invalid.toolResult), (fallback.body as ToolDetailBody.JsonContent).values)
        assertNull(mapRemoteToolDetail("filo", "file_read", "{", "result", "succeeded"))
    }
}
