package com.example.suppressor

import android.content.Context
import com.example.suppressor.audio.AudioStreamController
import com.example.suppressor.persistence.PersistenceManager
import com.example.suppressor.vibration.VibrationSettingsController
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class FallbackChainTest {

    private lateinit var context: Context
    private lateinit var primaryAudio: AudioStreamController
    private lateinit var fallbackAudio: AudioStreamController
    private lateinit var primaryVibration: VibrationSettingsController
    private lateinit var fallbackVibration: VibrationSettingsController
    private lateinit var persistenceManager: PersistenceManager
    private lateinit var audioManager: android.media.AudioManager

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        audioManager = mock(android.media.AudioManager::class.java)
        primaryAudio = mock(AudioStreamController::class.java)
        fallbackAudio = mock(AudioStreamController::class.java)
        primaryVibration = mock(VibrationSettingsController::class.java)
        fallbackVibration = mock(VibrationSettingsController::class.java)
        persistenceManager = mock(PersistenceManager::class.java)

        `when`(context.packageName).thenReturn("com.example.suppressor")
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(primaryAudio.displayName).thenReturn("PrimaryAudio")
        `when`(fallbackAudio.displayName).thenReturn("FallbackAudio")
        `when`(primaryVibration.displayName).thenReturn("PrimaryVibration")
        `when`(fallbackVibration.displayName).thenReturn("FallbackVibration")

        RecordingSuppressor.packageNameResolver = { "com.whatsapp" }
    }

    @After
    fun tearDown() {
        RecordingSuppressor.packageNameResolver = null
    }

    @Test
    fun `audio fallback activates when primary fails`() {
        // Primary returns false on setVolume, fallback returns true
        `when`(primaryAudio.getCurrentVolume(context)).thenReturn(7)
        `when`(primaryAudio.setVolume(context, 0, 0)).thenReturn(false)
        `when`(primaryAudio.getFallback()).thenReturn(fallbackAudio)
        `when`(fallbackAudio.setVolume(context, 0, 0)).thenReturn(true)

        `when`(primaryVibration.getCurrentIntensity(context)).thenReturn(50)
        `when`(primaryVibration.setIntensity(context, 0)).thenReturn(true)

        val chainedAudio = primaryAudio // chained via mock
        val suppressor = RecordingSuppressor(context, chainedAudio, primaryVibration, persistenceManager)

        val config = mock(android.media.AudioRecordingConfiguration::class.java)

        suppressor.onRecordingConfigChanged(listOf(config))

        assertTrue(suppressor.isCurrentlySuppressing())
        verify(primaryAudio).setVolume(context, 0, 0)
        verify(fallbackAudio).setVolume(context, 0, 0)
    }

    @Test
    fun `vibration fallback activates when primary fails`() {
        `when`(primaryAudio.getCurrentVolume(context)).thenReturn(7)
        `when`(primaryAudio.setVolume(context, 0, 0)).thenReturn(true)

        // Primary vibration fails, fallback succeeds
        `when`(primaryVibration.getCurrentIntensity(context)).thenReturn(50)
        `when`(primaryVibration.setIntensity(context, 0)).thenReturn(false)
        `when`(primaryVibration.getFallback()).thenReturn(fallbackVibration)
        `when`(fallbackVibration.setIntensity(context, 0)).thenReturn(true)

        val suppressor = RecordingSuppressor(context, primaryAudio, primaryVibration, persistenceManager)

        val config = mock(android.media.AudioRecordingConfiguration::class.java)

        suppressor.onRecordingConfigChanged(listOf(config))

        assertTrue(suppressor.isCurrentlySuppressing())
        verify(primaryVibration).setIntensity(context, 0)
        verify(fallbackVibration).setIntensity(context, 0)
    }

    @Test
    fun `suppression fails and restores when all audio fallbacks fail`() {
        `when`(primaryAudio.getCurrentVolume(context)).thenReturn(7)
        `when`(primaryAudio.setVolume(context, 0, 0)).thenReturn(false)
        `when`(primaryAudio.getFallback()).thenReturn(fallbackAudio)
        `when`(fallbackAudio.setVolume(context, 0, 0)).thenReturn(false)

        `when`(primaryVibration.getCurrentIntensity(context)).thenReturn(50)
        `when`(primaryVibration.setIntensity(context, 0)).thenReturn(true)

        val suppressor = RecordingSuppressor(context, primaryAudio, primaryVibration, persistenceManager)

        val config = mock(android.media.AudioRecordingConfiguration::class.java)

        suppressor.onRecordingConfigChanged(listOf(config))

        // If either audio or vibration suppression completely fails, suppression is aborted and restored
        assertFalse(suppressor.isCurrentlySuppressing())
    }
}
