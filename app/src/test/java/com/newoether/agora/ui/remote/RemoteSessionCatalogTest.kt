package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.remote.RemoteDevice
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.RemoteSession
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h700dp-mdpi")
class RemoteSessionCatalogTest {
    @get:Rule val compose = createComposeRule()
    private val state = MutableStateFlow(RemoteState(
        restoring = false,
        deviceId = "device",
        devices = listOf(RemoteDevice("device", "Workstation", "http://localhost")),
    ))
    private val vm = mockk<RemoteViewModel>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun showScreen() {
        every { vm.state } returns state
        compose.setContent { MaterialTheme { RemoteScreen(vm, settings, true, {}, {}, { _, _ -> }) } }
    }

    @Test fun emptyLoadingSettlesToMemoryStyleEmptyStateAndKeepsCategoryOnFailure() {
        state.value = state.value.copy(loading = true)
        showScreen()
        compose.onNodeWithText(text(R.string.remote_sessions)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.loading_label)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_empty_hint)).assertDoesNotExist()
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(1)
        val ring = compose.onNode(progress, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(20f, (ring.right - ring.left).value, 0.01f)
        assertEquals(20f, (ring.bottom - ring.top).value, 0.01f)

        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        compose.onNodeWithText(text(R.string.remote_sessions)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_empty)).assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText(text(R.string.remote_empty_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.loading_label)).assertDoesNotExist()
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(0)

        compose.runOnIdle { state.value = state.value.copy(failure = RemoteFailure.SERVICE) }
        compose.onNodeWithText(text(R.string.remote_sessions)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_empty)).assertIsDisplayed()
        verify(exactly = 0) { vm.newSession() }
        verify(exactly = 0) { vm.selectSession(any()) }
        verify(exactly = 0) { vm.renameSession(any(), any()) }
        verify(exactly = 0) { vm.archiveSession(any()) }
    }

    @Test fun claudeDesktopHidesUnsupportedCreationForEmptyAndPopulatedCatalogs() {
        val source = com.newoether.agora.remote.RemoteAdapter("claude-desktop", "claude", "desktop", "connected",
            nativeScopeId = "desktop-scope", capabilities = com.newoether.agora.remote.RemoteCapabilities(
                history = true, live = true, idleSend = true, activeSend = "native-steer", stop = true))
        state.value = state.value.copy(
            devices = state.value.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to source.id))
        showScreen()
        compose.onNodeWithContentDescription(text(R.string.new_chat)).assertDoesNotExist()
        val session = RemoteSession("native", "Existing Desktop Conversation", "/native", 1)
        compose.runOnIdle { state.value = state.value.copy(sessions = listOf(session)) }
        compose.onNodeWithContentDescription(text(R.string.new_chat)).assertDoesNotExist()
        compose.onNodeWithText(session.title).performClick()
        verify(exactly = 1) { vm.selectSession(session) }
        verify(exactly = 0) { vm.newSession() }
        compose.runOnIdle {
            val supported = source.copy(id = "claude-code", origin = "cli",
                capabilities = source.capabilities.copy(create = true))
            state.value = state.value.copy(
                devices = state.value.devices.map { it.copy(adapters = listOf(supported)) },
                adapterSelections = mapOf("device" to supported.id))
        }
        compose.onNodeWithContentDescription(text(R.string.new_chat)).assertIsDisplayed()
    }

    @Test fun populatedRefreshKeepsRowsWithOnlyBottomLinearProgress() {
        val session = RemoteSession("session", "Native Task", "/native", 1)
        state.value = state.value.copy(sessions = listOf(session), loading = true)
        showScreen()
        compose.onNodeWithText(text(R.string.remote_sessions)).assertIsDisplayed()
        compose.onNodeWithText(session.title).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.loading_label)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_empty)).assertDoesNotExist()
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(1)
        val bar = compose.onNode(progress, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("Populated refresh keeps a horizontal progress bar", bar.size.width > bar.size.height * 10)

        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(session.title).assertIsDisplayed().performClick()
        verify(exactly = 1) { vm.selectSession(session) }
    }
}
