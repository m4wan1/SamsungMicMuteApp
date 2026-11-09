package com.example.suppressor

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import com.example.suppressor.audio.AudioStreamController
import com.example.suppressor.persistence.PersistenceManager
import com.example.suppressor.persistence.SuppressionState
import com.example.suppressor.vibration.VibrationSettingsController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class RecordingSuppressorTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var audioController: AudioStreamController
    private lateinit var vibrationController: VibrationSettingsController
    private lateinit var persistenceManager: PersistenceManager
    private lateinit var suppressor: RecordingSuppressor

    private val packageName = "com.example.suppressor"
    private val originalAudioVolume = 5
    private val originalVibrationIntensity = 80

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        audioManager = mock(AudioManager::class.java)
        audioController = mock(AudioStreamController::class.java)
        vibrationController = mock(VibrationSettingsController::class.java)
        persistenceManager = mock(PersistenceManager::class.java)

        `when`(context.packageName).thenReturn(packageName)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)

        `when`(audioController.getCurrentVolume(context)).thenReturn(originalAudioVolume)
        `when`(audioController.setVolume(context, 0, 0)).thenReturn(true)
        `when`(audioController.restoreVolume(context, originalAudioVolume)).thenReturn(true)

        `when`(vibrationController.getCurrentIntensity(context)).thenReturn(originalVibrationIntensity)
        `when`(vibrationController.setIntensity(context, 0)).thenReturn(true)
        `when`(vibrationController.restoreIntensity(context, originalVibrationIntensity)).thenReturn(true)

        RecordingSuppressor.packageNameResolver = { config ->
            configMockPackages[config] ?: "unknown"
        }

        suppressor = RecordingSuppressor(
            context = context,
            audioController = audioController,
            vibrationController = vibrationController,
            persistenceManager = persistenceManager
        ).apply {
            debounceMs = 0L
        }
    }

    @After
    fun tearDown() {
        RecordingSuppressor.packageNameResolver = null
        configMockPackages.clear()
    }

    private val configMockPackages = mutableMapOf<AudioRecordingConfiguration, String>()

    private fun createMockConfig(pkg: String, active: Boolean = true, source: Int = 1): AudioRecordingConfiguration {
        val config = mock(AudioRecordingConfiguration::class.java)
        configMockPackages[config] = pkg
        `when`(config.clientAudioSource).thenReturn(source)
        `when`(config.isClientSilenced).thenReturn(!active)
        `when`(config.clientAudioSessionId).thenReturn(pkg.hashCode())
        return config
    }

    @Test
    fun `first recording starts suppression and caches original values`() {
        val config = createMockConfig("com.whatsapp")

        suppressor.onRecordingConfigChanged(listOf(config))

        assertTrue(suppressor.isCurrentlySuppressing())
        assertEquals(1, suppressor.getActiveRecordingCount())
        assertTrue(suppressor.getActivePackages().contains("com.whatsapp"))

        // Original values cached & saved
        verify(persistenceManager).saveOriginalValues(originalAudioVolume, originalVibrationIntensity)

        // Audio & vibration muted
        verify(audioController).setVolume(context, 0, 0)
        verify(vibrationController).setIntensity(context, 0)
    }

    @Test
    fun `last recording stops restores original values and clears suppression`() {
        val config = createMockConfig("com.whatsapp")

        // Start recording
        suppressor.onRecordingConfigChanged(listOf(config))
        assertTrue(suppressor.isCurrentlySuppressing())

        // Stop recording
        suppressor.onRecordingConfigChanged(emptyList())

        assertFalse(suppressor.isCurrentlySuppressing())
        assertEquals(0, suppressor.getActiveRecordingCount())

        // Original values restored
        verify(audioController).restoreVolume(context, originalAudioVolume)
        verify(vibrationController).restoreIntensity(context, originalVibrationIntensity)

        // State cleared
        verify(persistenceManager).clearSuppressionState()
    }

    @Test
    fun `concurrent recordings hold suppression until all stop`() {
        val configWhatsApp = createMockConfig("com.whatsapp")
        val configSnapchat = createMockConfig("com.snapchat.android")

        // WhatsApp starts
        suppressor.onRecordingConfigChanged(listOf(configWhatsApp))
        assertTrue(suppressor.isCurrentlySuppressing())
        assertEquals(1, suppressor.getActiveRecordingCount())

        // Snapchat starts as well
        suppressor.onRecordingConfigChanged(listOf(configWhatsApp, configSnapchat))
        assertTrue(suppressor.isCurrentlySuppressing())
        assertEquals(2, suppressor.getActiveRecordingCount())

        // WhatsApp stops, but Snapchat is still recording
        suppressor.onRecordingConfigChanged(listOf(configSnapchat))
        assertTrue(suppressor.isCurrentlySuppressing())
        assertEquals(1, suppressor.getActiveRecordingCount())

        // Restoration should NOT have happened yet
        verify(audioController, never()).restoreVolume(context, originalAudioVolume)
        verify(vibrationController, never()).restoreIntensity(context, originalVibrationIntensity)

        // Snapchat stops (all stopped)
        suppressor.onRecordingConfigChanged(emptyList())
        assertFalse(suppressor.isCurrentlySuppressing())
        assertEquals(0, suppressor.getActiveRecordingCount())

        // Restoration happens now
        verify(audioController).restoreVolume(context, originalAudioVolume)
        verify(vibrationController).restoreIntensity(context, originalVibrationIntensity)
    }

    @Test
    fun `any recording config triggers suppression regardless of package name`() {
        val ownConfig = createMockConfig(packageName)

        suppressor.onRecordingConfigChanged(listOf(ownConfig))

        assertTrue(suppressor.isCurrentlySuppressing())
        assertEquals(1, suppressor.getActiveRecordingCount())
        verify(audioController).setVolume(context, 0, 0)
        verify(vibrationController).setIntensity(context, 0)
    }

    @Test
    fun `forceRestore restores original values when suppressing`() {
        val config = createMockConfig("com.sec.android.app.voicenote")

        suppressor.onRecordingConfigChanged(listOf(config))
        assertTrue(suppressor.isCurrentlySuppressing())

        suppressor.forceRestore()

        assertFalse(suppressor.isCurrentlySuppressing())
        verify(audioController).restoreVolume(context, originalAudioVolume)
        verify(vibrationController).restoreIntensity(context, originalVibrationIntensity)
    }

    @Test
    fun `initializeFromPersistedState clears stale state when no active recordings`() {
        val persistedState = SuppressionState(
            isSuppressing = true,
            activeRecordingCount = 1,
            activePackages = setOf("com.whatsapp"),
            originalAudioVolume = originalAudioVolume,
            originalVibrationIntensity = originalVibrationIntensity,
            timestamp = System.currentTimeMillis()
        )
        `when`(persistenceManager.loadState()).thenReturn(persistedState)
        `when`(audioManager.activeRecordingConfigurations).thenReturn(emptyList())

        suppressor.initializeFromPersistedState()

        verify(persistenceManager).clearSuppressionState()
    }
}
