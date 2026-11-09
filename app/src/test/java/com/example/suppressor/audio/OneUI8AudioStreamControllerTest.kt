package com.example.suppressor.audio

import android.content.Context
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class OneUI8AudioStreamControllerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var controller: OneUI8AudioStreamController

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        audioManager = mock(AudioManager::class.java)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        controller = OneUI8AudioStreamController()
    }

    @Test
    fun `streamType is STREAM_NOTIFICATION`() {
        assertEquals(AudioManager.STREAM_NOTIFICATION, controller.streamType)
    }

    @Test
    fun `getCurrentVolume delegates to audioManager`() {
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)).thenReturn(5)
        assertEquals(5, controller.getCurrentVolume(context))
    }

    @Test
    fun `setVolume clamps volume within maxVolume`() {
        `when`(audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)).thenReturn(7)

        // Setting value higher than max should clamp to 7
        val result = controller.setVolume(context, 10, 0)
        assertTrue(result)
        verify(audioManager).setStreamVolume(AudioManager.STREAM_NOTIFICATION, 7, 0)

        // Setting negative should clamp to 0
        controller.setVolume(context, -3, 0)
        verify(audioManager).setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
    }

    @Test
    fun `restoreVolume restores volume with flag 0`() {
        `when`(audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)).thenReturn(7)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)).thenReturn(4)
        val result = controller.restoreVolume(context, 4)
        assertTrue(result)
        verify(audioManager).setStreamVolume(AudioManager.STREAM_NOTIFICATION, 4, 0)
    }

    @Test
    fun `isSupported returns true when maxVol and currentVol are valid`() {
        `when`(audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)).thenReturn(7)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)).thenReturn(3)
        assertTrue(controller.isSupported(context))
    }

    @Test
    fun `isSupported returns false when stream has 0 max volume`() {
        `when`(audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)).thenReturn(0)
        assertFalse(controller.isSupported(context))
    }
}
