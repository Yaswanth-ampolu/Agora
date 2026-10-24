package com.newoether.agora.remote

import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteObservedInputTest {
    private fun initial(): RemoteState {
        val caps = RemoteCapabilities.legacy
        val source = RemoteAdapter("claude-code", "claude", "cli", "connected",
            nativeScopeId = "scope", capabilities = caps)
        val state = RemoteState(devices = listOf(RemoteDevice("device", "Computer", "address",
            RemoteDeviceStatus.CONNECTED, adapters = listOf(source), multiAdapter = true)),
            deviceId = "device", adapterSelections = mapOf("device" to source.id), restoring = false,
            session = RemoteSession("session", "Task", "", 1,
                access = RemoteSessionAccess("scope", "canonical", "binding", caps)),
            runtime = RemoteRuntime("idle", model = "model"),
            models = listOf(RemoteModel("model", "Model", true, listOf("low", "high"), "high")))
        return state.copy(drafts = mapOf(requireNotNull(state.owner) to "original input"))
    }

    @Test fun submittedKeepsDraftAndObservedClearsOnlyUnchangedText() = runTest {
        for (editDraft in listOf(false, true)) {
            val state = MutableStateFlow(initial())
            val client = mockk<FiloClient>()
            coEvery { client.sendReceipt(any(), any(), any(), any()) } coAnswers {
                RemoteSendReceipt(clientId = thirdArg(), kind = "submitted_observed", state = "submitted")
            }
            val submission = RemoteSubmission(backgroundScope, state, { client }, { _, _ -> true }, { 1 },
                { true }, {}, null, ScrollRequestCoordinator(), { _, _, _ -> null }, {}, { false })
            val owner = state.value.owner!!
            submission.send()
            runCurrent()
            val accepted = state.value.attempts[owner]!!
            assertEquals(RemoteDelivery.ACCEPTED, accepted.delivery)
            assertEquals("original input", state.value.drafts[owner])
            submission.send()
            runCurrent()
            coVerify(exactly = 1) { client.sendReceipt(any(), any(), any(), any()) }
            if (editDraft) state.value = state.value.copy(drafts = mapOf(owner to "later edit"))
            submission.confirm(owner, accepted, listOf(RemoteInputReceipt(accepted.clientId,
                messageId = "native-user", kind = "submitted_observed", state = "observed")))
            assertEquals(RemoteDelivery.DELIVERED, state.value.attempts[owner]?.delivery)
            assertEquals(if (editDraft) "later edit" else null, state.value.drafts[owner])
            val settled = state.value
            submission.confirm(owner, accepted, listOf(RemoteInputReceipt(accepted.clientId,
                kind = "submitted_observed", state = "unconfirmed")))
            assertEquals(settled, state.value)
        }
    }

    @Test fun unknownBlocksFreshClientAndLateSubmittedCannotDowngradeUncertainty() = runTest {
        val state = MutableStateFlow(initial())
        val client = mockk<FiloClient>()
        coEvery { client.sendReceipt(any(), any(), any(), any()) } throws IOException("response lost")
        val submission = RemoteSubmission(backgroundScope, state, { client }, { _, _ -> true }, { 1 },
            { true }, {}, null, ScrollRequestCoordinator(), { _, _, _ -> RemoteFailure.NETWORK }, {}, { false })
        val owner = state.value.owner!!
        submission.send()
        runCurrent()
        val unknown = state.value.attempts[owner]!!
        assertEquals(RemoteDelivery.UNKNOWN, unknown.delivery)
        assertEquals("submitted_observed", unknown.receiptKind)
        submission.confirm(owner, unknown, listOf(RemoteInputReceipt(unknown.clientId,
            kind = "submitted_observed", state = "submitted")))
        assertEquals(RemoteDelivery.UNKNOWN, state.value.attempts[owner]?.delivery)
        submission.send()
        runCurrent()
        coVerify(exactly = 1) { client.sendReceipt(any(), any(), any(), any()) }
        submission.confirm(owner, unknown, listOf(RemoteInputReceipt(unknown.clientId,
            turnId = "native-turn", kind = "submitted_observed", state = "observed")))
        assertEquals(RemoteDelivery.DELIVERED, state.value.attempts[owner]?.delivery)
        assertNull(state.value.drafts[owner])
    }

    @Test fun encryptedTransportErrorsKeepSubmissionUnknownAndBlockBlindResend() = runTest {
        for (error in listOf(com.newoether.agora.util.ConchAuthenticationException("token"),
            com.newoether.agora.util.ConchServiceException("gateway"))) {
            val state = MutableStateFlow(initial())
            val client = mockk<FiloClient>()
            val notices = mutableListOf<RemoteNotice>()
            coEvery { client.sendReceipt(any(), any(), any(), any()) } throws error
            val submission = RemoteSubmission(backgroundScope, state, { client }, { _, _ -> true }, { 1 },
                { true }, {}, null, ScrollRequestCoordinator(), { _, failure, _ -> classifyRemoteFailure(failure) },
                { notices += it }, { false })
            val owner = state.value.owner!!
            submission.send()
            runCurrent()
            assertEquals(RemoteDelivery.UNKNOWN, state.value.attempts[owner]?.delivery)
            assertEquals("original input", state.value.drafts[owner])
            assertEquals(classifyRemoteFailure(error), notices.single().failure)
            assertEquals("delivery_unconfirmed", notices.single().code)
            submission.send()
            runCurrent()
            coVerify(exactly = 1) { client.sendReceipt(any(), any(), any(), any()) }
        }
    }

    @Test fun pendingSettingsReleasesSendBarrierWithoutClaimingAppliedValues() = runTest {
        val state = MutableStateFlow(initial())
        val client = mockk<FiloClient>()
        val pending = RemoteSettingsChange("change", "pending", RemoteSettings(effort = "low"))
        coEvery { client.updateSettings(any(), any()) } returns Unit
        coEvery { client.conversation(any(), any()) } returns RemoteConversationPage(emptyList(), null,
            emptyList(), runtime = state.value.runtime!!.copy(settingsChange = pending))
        val mutation = RemoteSettingsMutation(backgroundScope, state, { client }, { client }, { _, _ -> true },
            { 1 }, { 1 }, { _, _, _, page -> state.value = state.value.copy(runtime = page.runtime) }, {},
            { fail("Pending native request must not report failure"); null })
        mutation.setThinkingLevel("low")
        runCurrent()
        assertEquals(pending, state.value.runtime?.settingsChange)
        assertEquals("high", state.value.runtime?.effort ?: state.value.settingsModel?.defaultReasoningEffort)
        assertFalse(state.value.settingsPending)
        assertTrue(state.value.canSend)
        coVerify(exactly = 1) { client.updateSettings("session", RemoteSettings(effort = "low")) }
    }
}
