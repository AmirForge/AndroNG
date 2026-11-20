package com.v2ray.ang.dto

import java.io.Serializable

data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val onlyTcp: Boolean = false,
    val healthOnly: Boolean = false,
) : Serializable {
    companion object {
        // Preserve decoding of queued requests from release 772 when adding an optional field.
        private const val serialVersionUID = -4429458407118583968L
    }
}

