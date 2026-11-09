package com.example.suppressor.vibration

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class OneUI8VibrationSettingsControllerTest {

    private lateinit var context: Context
    private lateinit var controller: OneUI8VibrationSettingsController

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        controller = OneUI8VibrationSettingsController()
    }

    @Test
    fun `settingKey is sem_vibration_notification_intensity`() {
        assertEquals("sem_vibration_notification_intensity", controller.settingKey)
    }

    @Test
    fun `intensityRange is 0 to 100`() {
        val range = controller.getIntensityRange()
        assertEquals(0, range.first)
        assertEquals(100, range.last)
    }

    @Test
    fun `displayName is set`() {
        assertNotNull(controller.displayName)
    }
}
