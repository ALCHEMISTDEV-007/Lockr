package dev.lockr.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SessionManagerTest {
    @Test fun timeoutExpiresSessionAtDeadline() {
        val sessions = InMemorySessionManager()
        sessions.createSession("media.app", 1_000L, 15_000L)
        assertNotNull(sessions.activeSession("media.app", 15_999L))
        assertNull(sessions.activeSession("media.app", 16_000L))
    }

    @Test fun immediateLockClearsSessionAfterLeavingPackage() {
        val sessions = InMemorySessionManager()
        sessions.createSession("media.app", 1_000L, 0L)
        assertNotNull(sessions.activeSession("media.app", 9_000L))
        sessions.onForegroundPackageChanged("chat.app", 9_001L, 0L)
        assertNull(sessions.activeSession("media.app", 9_001L))
    }

    @Test fun screenOffInvalidatesOnlyWhenConfigured() {
        val sessions = InMemorySessionManager()
        sessions.createSession("media.app", 1_000L, 30_000L)
        sessions.onScreenOff(lockOnScreenOff = false)
        assertNotNull(sessions.activeSession("media.app", 2_000L))
        sessions.onScreenOff(lockOnScreenOff = true)
        assertNull(sessions.activeSession("media.app", 2_000L))
    }

    @Test fun rapidPackageTransitionsRemainPackageSpecific() {
        val sessions = InMemorySessionManager()
        sessions.createSession("media.app", 1_000L, 60_000L)
        sessions.onForegroundPackageChanged("chat.app", 2_000L, 60_000L)
        sessions.createSession("chat.app", 2_001L, 60_000L)
        assertNotNull(sessions.activeSession("media.app", 2_002L))
        assertNotNull(sessions.activeSession("chat.app", 2_002L))
        sessions.onForegroundPackageChanged("media.app", 2_003L, 0L)
        assertNotNull(sessions.activeSession("media.app", 2_003L))
        assertNull(sessions.activeSession("chat.app", 2_003L))
    }
}
