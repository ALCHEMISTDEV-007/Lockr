package dev.lockr.security

import dev.lockr.data.ProtectedAppLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LockDecisionEngineTest {
    private class FakeProtectedApps(private val selected: Set<String>) : ProtectedAppLookup {
        override suspend fun isProtectedPackage(packageName: String) = packageName in selected
    }

    @Test fun unprotectedPackageIsIgnored() = runBlocking {
        val engine = LockDecisionEngine(FakeProtectedApps(emptySet()), InMemorySessionManager())
        assertEquals(LockDecision.IGNORE_UNPROTECTED_APP, engine.shouldAuthenticate("chat.app", 10L))
    }

    @Test fun protectedPackageWithoutSessionRequiresAuthentication() = runBlocking {
        val engine = LockDecisionEngine(FakeProtectedApps(setOf("photos.app")), InMemorySessionManager())
        assertEquals(LockDecision.REQUIRE_AUTHENTICATION, engine.shouldAuthenticate("photos.app", 10L))
    }

    @Test fun activePackageSessionAllowsAccess() = runBlocking {
        val sessions = InMemorySessionManager().apply { createSession("photos.app", 10L, 30_000L) }
        val engine = LockDecisionEngine(FakeProtectedApps(setOf("photos.app")), sessions)
        assertEquals(LockDecision.ALLOW_ACTIVE_SESSION, engine.shouldAuthenticate("photos.app", 20L))
    }

    @Test fun expiredPackageSessionRequiresAuthentication() = runBlocking {
        val sessions = InMemorySessionManager().apply { createSession("photos.app", 10L, 30_000L) }
        val engine = LockDecisionEngine(FakeProtectedApps(setOf("photos.app")), sessions)
        assertEquals(LockDecision.REQUIRE_AUTHENTICATION, engine.shouldAuthenticate("photos.app", 40_010L))
    }
}
