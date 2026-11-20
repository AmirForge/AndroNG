package com.v2ray.ang.ui.main

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AetherRefreshFeedbackTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `gentle feedback continues throughout a refresh and stops when cancelled`() = runTest {
        var pulses = 0
        val feedback = launch { pulseAetherRefreshHaptics { pulses++ } }
        runCurrent()
        advanceTimeBy(899)
        runCurrent()
        assertEquals(0, pulses)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pulses)
        advanceTimeBy(2_700)
        runCurrent()
        assertEquals(4, pulses)
        feedback.cancelAndJoin()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(4, pulses)
    }
}
