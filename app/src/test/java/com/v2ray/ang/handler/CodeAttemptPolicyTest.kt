package com.v2ray.ang.handler

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CodeAttemptPolicyTest {
    @Test fun `five requests then one hour cooldown including restored state`() {
        val now = 10_000_000L
        var state = CodeAttempts(0, 0, 0)
        repeat(5) { index ->
            state = CodeAttemptPolicy.consume(state.attempts, state.window, state.blockedUntil, now + index)!!
            assertEquals(index + 1, state.attempts)
        }
        assertNull(CodeAttemptPolicy.consume(state.attempts, state.window, state.blockedUntil, now + 100))
        val restored = CodeAttemptPolicy.consume(state.attempts, state.window, state.blockedUntil, state.blockedUntil)!!
        assertEquals(1, restored.attempts)
        assertEquals(0L, restored.blockedUntil)
    }
    @Test fun `expired attempt window resets before fifth and rolling back clock does not increase attempts`() {
        assertEquals(1, CodeAttemptPolicy.consume(4, 10000, 0, 3_610_000)!!.attempts)
        assertNull(CodeAttemptPolicy.consume(5, 10000, 3_610_000, 9000))
    }
}
