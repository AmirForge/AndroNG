package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeConfigurationShortcutTest {
    @Test
    fun defaultAndAllPagesOfferFreeConfigsWhileSubscriptionsOfferUpdates() {
        assertTrue(isFreeConfigurationPage(AppConfig.DEFAULT_SUBSCRIPTION_ID))
        assertTrue(isFreeConfigurationPage(""))
        assertFalse(isFreeConfigurationPage(AppConfig.ANDROMEDA_SUBSCRIPTION_ID))
        assertFalse(isFreeConfigurationPage("personal-subscription"))
    }
}
