package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AetherProgressMessagesTest {
    @Test
    fun `three second messages have no repeats for more than a minute`() {
        assertEquals(3_000L, AETHER_MESSAGE_INTERVAL_MILLIS)
        assertTrue(progressMessages.size * AETHER_MESSAGE_INTERVAL_MILLIS > 60_000L)
        assertEquals(progressMessages.size, progressMessages.toSet().size)
    }

    @Test
    fun `message changes only at three second boundaries and wraps after the entire cycle`() {
        assertEquals(progressMessages[0], aetherProgressMessage(0))
        assertEquals(progressMessages[0], aetherProgressMessage(2_999))
        assertEquals(progressMessages[1], aetherProgressMessage(3_000))
        assertEquals(progressMessages[1], aetherProgressMessage(5_999))
        assertEquals(progressMessages[2], aetherProgressMessage(6_000))
        val cycle = progressMessages.size * AETHER_MESSAGE_INTERVAL_MILLIS
        assertEquals(progressMessages.last(), aetherProgressMessage(cycle - 1))
        assertEquals(progressMessages.first(), aetherProgressMessage(cycle))
        assertEquals(progressMessages.first(), aetherProgressMessage(-1))
    }
}
