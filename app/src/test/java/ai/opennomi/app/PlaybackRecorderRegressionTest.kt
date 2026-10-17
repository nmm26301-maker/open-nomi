package ai.opennomi.app

import ai.opennomi.app.screen.PlaybackRecorderOwner
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackRecorderRegressionTest {
    @Test fun blockedNativeStopReturnsToCallerAndCannotRaceWithRelease() {
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        val released=CountDownLatch(1);val stops=AtomicInteger();val releases=AtomicInteger()
        val owner=PlaybackRecorderOwner(Any(),{
            stops.incrementAndGet();entered.countDown()
            check(unblock.await(5,TimeUnit.SECONDS))
        },{releases.incrementAndGet();released.countDown()})
        val pool=Executors.newFixedThreadPool(2)
        try {
            assertTrue(owner.start{})
            pool.submit{owner.requestStop()}.get(2,TimeUnit.SECONDS)
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val finish=pool.submit{owner.finish()}
            assertFalse(released.await(100,TimeUnit.MILLISECONDS))
            owner.requestStop()
            unblock.countDown();finish.get(2,TimeUnit.SECONDS)
            owner.finish()
            assertEquals(1,stops.get());assertEquals(1,releases.get())
            assertFalse(owner.start{fail("Released recorder must never restart")})
        } finally {unblock.countDown();pool.shutdownNow()}
    }

    @Test fun cancellingBeforeNativeStartPreventsRecordingAndStillReleases() {
        val releases=AtomicInteger()
        val owner=PlaybackRecorderOwner(Any(),{},{releases.incrementAndGet()})
        owner.requestStop()
        assertFalse(owner.start{fail("Cancelled recorder must never start")})
        owner.finish();owner.finish();assertEquals(1,releases.get())
    }

    @Test fun nativeStopFailureStillReleasesExactlyOnce() {
        val releases=AtomicInteger();val stops=AtomicInteger()
        val owner=PlaybackRecorderOwner(Any(),{stops.incrementAndGet();error("already stopped")},{releases.incrementAndGet()})
        owner.finish();owner.finish()
        assertEquals(1,stops.get());assertEquals(1,releases.get())
    }
}
