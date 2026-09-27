package dev.lockr.security

import dev.lockr.data.PinFailureState

object PinFailurePolicy {
    fun recordFailure(previous: PinFailureState, nowEpochMillis: Long): PinFailureState {
        val attempts = previous.attempts + 1
        val cooldown = when {
            attempts >= 15 -> 15 * 60_000L
            attempts >= 10 -> 5 * 60_000L
            attempts >= 5 -> 30_000L
            else -> 0L
        }
        val until = if (cooldown == 0L) 0L else safeAdd(nowEpochMillis, cooldown)
        return PinFailureState(attempts, until)
    }

    fun remainingMillis(state: PinFailureState, nowEpochMillis: Long): Long =
        (state.lockedUntilEpochMillis - nowEpochMillis).coerceAtLeast(0L)

    private fun safeAdd(left: Long, right: Long) =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
