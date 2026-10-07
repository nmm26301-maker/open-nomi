package ai.opennomi.app

import ai.opennomi.app.audio.*
import org.junit.Assert.*
import org.junit.Test

class ConversationRouteRegressionTest {
    private val speaker = ConversationDevice(1, RouteKind.SPEAKER, "phone")
    private val wired = ConversationDevice(2, RouteKind.WIRED, "usb")
    private val bluetooth = ConversationDevice(3, RouteKind.BLUETOOTH, "headset")
    private inner class Fake : ConversationRoutePlatform {
        var available = listOf(speaker)
        var actual: ConversationDevice? = speaker
        val requests = mutableListOf<ConversationDevice>()
        var enters = 0; var exits = 0; var accepted = true
        override fun devices() = available
        override fun current() = actual
        override fun enter() { enters++ }
        override fun select(device: ConversationDevice): Boolean { requests.add(device); return accepted }
        override fun exit() { exits++ }
    }
    private fun controller(fake: Fake) = ConversationRouteController(fake, {}, {})
    @Test fun phoneRouteIsImmediatelyReadyWithoutAddingStartupDelay() {
        val f=Fake();val c=controller(f);c.acquire(Any())
        assertFalse(c.state.pending);assertEquals(speaker,c.state.device)
    }
    @Test fun acceptedBluetoothRequestIsNotMistakenForConfirmedAudio() {
        val f=Fake();f.available=listOf(speaker,bluetooth);val c=controller(f);c.acquire(Any())
        assertEquals(bluetooth,f.requests.single());assertTrue(c.state.pending);assertNull(c.state.device)
        c.confirmed(bluetooth);assertFalse(c.state.pending);assertEquals(bluetooth,c.state.device)
    }
    @Test fun repeatedDeviceCallbacksDoNotReconnectPendingBluetooth() {
        val f=Fake();f.available=listOf(speaker,bluetooth);val c=controller(f);c.acquire(Any())
        repeat(10){c.devicesChanged()};assertEquals(1,f.requests.size)
    }
    @Test fun connectAndDisconnectAutomaticallyChooseExternalThenPhone() {
        val f=Fake();val c=controller(f);c.acquire(Any())
        f.available=listOf(speaker,bluetooth);c.devicesChanged();f.actual=bluetooth;c.confirmed(bluetooth)
        assertEquals(bluetooth,c.state.device)
        f.available=listOf(speaker);f.actual=speaker;c.devicesChanged();assertEquals(speaker,c.state.device)
    }
    @Test fun unavailableHeadsetFallsBackInsteadOfHoldingMicrophoneForever() {
        val f=Fake();f.available=listOf(speaker,bluetooth);val c=controller(f);c.acquire(Any())
        c.expired(c.state.request);assertEquals(speaker,c.state.device);assertTrue(c.state.message.contains("回退"))
        c.devicesChanged();assertEquals(2,f.requests.size)
    }
    @Test fun disconnectedThenReconnectedHeadsetCanRetryAfterTimeout() {
        val f=Fake();f.available=listOf(speaker,bluetooth);val c=controller(f);c.acquire(Any());c.expired(c.state.request)
        f.available=listOf(speaker);c.devicesChanged();f.available=listOf(speaker,bluetooth);c.devicesChanged()
        assertEquals(bluetooth,f.requests.last());assertTrue(c.state.pending)
    }
    @Test fun oldTimeoutAndConfirmationCannotOverrideNewRoute() {
        val f=Fake();f.available=listOf(speaker,bluetooth);val c=controller(f);c.acquire(Any());val old=c.state.request
        f.available=listOf(speaker);c.devicesChanged();c.expired(old);c.confirmed(bluetooth)
        assertEquals(speaker,c.state.device)
    }
    @Test fun wiredHeadsetWinsOverSpeakerAndBluetoothWinsOverWired() {
        val f=Fake();f.available=listOf(speaker,wired);f.actual=wired;val c=controller(f);c.acquire(Any())
        assertEquals(wired,c.state.device);f.available+=bluetooth;c.devicesChanged();assertEquals(bluetooth,f.requests.last())
    }
    @Test fun releasingAnOldOwnerCannotEndAnotherConversation() {
        val f=Fake();val c=controller(f);val old=Any();val new=Any()
        c.acquire(old);c.acquire(new);c.release(old);c.release(old)
        assertTrue(c.active);assertEquals(0,f.exits);c.release(new)
        assertFalse(c.active);assertEquals(1,f.exits);assertFalse(c.state.active)
    }
    @Test fun cancellationRejectsLateBluetoothCallbackAndTimeout() {
        val f=Fake();f.available=listOf(speaker,bluetooth);val c=controller(f);val owner=Any();c.acquire(owner)
        val old=c.state.request;c.release(owner);c.confirmed(bluetooth);c.expired(old)
        assertFalse(c.active);assertNull(c.state.device);assertEquals(1,f.exits)
    }
    @Test fun exhaustedRoutesPublishActionableErrorAndCanRecover() {
        val f=Fake();f.available=emptyList();val c=controller(f);c.acquire(Any())
        assertFalse(c.state.pending);assertNull(c.state.device);assertTrue(c.state.message.contains("不可用"))
        f.available=listOf(speaker);c.devicesChanged();assertEquals(speaker,c.state.device)
    }
}
