package com.dacia.hybridcluster.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigJsonTest {
    @Test fun missingFieldsGetDefaults() {
        // JSON zapisany przez starszą wersję – brak nowych pól (np. clusterUseHostSafeArea)
        val cfg = ConfigManager.fromJson("""{"uiScale":1.3,"demoMode":true,"selectedBtDeviceAddress":"AA:BB"}""")!!
        assertEquals(1.3f, cfg.uiScale, 1e-6f)
        assertEquals(true, cfg.demoMode)
        assertEquals("AA:BB", cfg.selectedBtDeviceAddress)
        assertEquals(UiConfig().fullLeftOffsetPercent, cfg.fullLeftOffsetPercent, 1e-6f)
        assertEquals(false, cfg.clusterUseHostSafeArea)
        assertEquals("SYSTEM", cfg.appLanguage)
        assertNull(cfg.selectedBtDeviceName)
    }

    @Test fun roundTrip() {
        val c = UiConfig(uiScale = 1.42f, appLanguage = "EN", selectedBtDeviceName = "vLinker MS")
        val json = com.google.gson.Gson().toJson(c)
        assertEquals(c, ConfigManager.fromJson(json))
    }

    @Test fun corruptJsonReturnsNull() = assertNull(ConfigManager.fromJson("{oops"))
}
