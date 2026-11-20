package com.v2ray.ang.dto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TestServiceMessageCompatibilityTest {
    @Test
    fun release772RequestDefaultsToNumericTestingWhenDecoded() {
        val input = javaClass.getResourceAsStream("/protocol/test-service-772.bin")!!
        val restored = ObjectInputStream(input).use { it.readObject() as TestServiceMessage }
        assertEquals(123, restored.key)
        assertEquals("test-group", restored.subscriptionId)
        assertEquals(listOf("guid-a"), restored.serverGuids)
        assertFalse(restored.healthOnly)
        assertFalse(restored.onlyTcp)
    }

    @Test
    fun healthOnlyFlagSurvivesTheServiceBoundary() {
        val original = TestServiceMessage(123, "group", listOf("a"), healthOnly = true)
        val output = ByteArrayOutputStream()
        ObjectOutputStream(output).use { it.writeObject(original) }
        val restored = ObjectInputStream(ByteArrayInputStream(output.toByteArray())).use { it.readObject() }
        assertEquals(original, restored)
    }
}
