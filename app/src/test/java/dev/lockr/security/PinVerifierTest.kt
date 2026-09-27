package dev.lockr.security

import dev.lockr.data.PinFailureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinVerifierTest {
    @Test fun verifierAcceptsCorrectPinAndRejectsIncorrectPin() {
        val record = PinVerifier.create("2580")
        assertTrue(PinVerifier.verify("2580", record))
        assertFalse(PinVerifier.verify("2581", record))
        assertFalse(PinVerifier.verify("258", record))
    }

    @Test fun onlyFourToSixNumericDigitsAreValid() {
        assertTrue(PinVerifier.isValidPin("1234"))
        assertTrue(PinVerifier.isValidPin("123456"))
        assertFalse(PinVerifier.isValidPin("123"))
        assertFalse(PinVerifier.isValidPin("1234567"))
        assertFalse(PinVerifier.isValidPin("12a4"))
    }

    @Test fun fifthFailureStartsCooldownAndLaterFailuresEscalate() {
        var state = PinFailureState()
        repeat(4) { state = PinFailurePolicy.recordFailure(state, 1_000L) }
        assertEquals(0L, PinFailurePolicy.remainingMillis(state, 1_000L))
        state = PinFailurePolicy.recordFailure(state, 1_000L)
        assertEquals(30_000L, PinFailurePolicy.remainingMillis(state, 1_000L))
        repeat(5) { state = PinFailurePolicy.recordFailure(state, 40_000L) }
        assertEquals(300_000L, PinFailurePolicy.remainingMillis(state, 40_000L))
    }
}
