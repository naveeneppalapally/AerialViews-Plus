package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@DisplayName("Device Entropy Engine Tests")
class DeviceEntropyEngineTest {
    private lateinit var tempDir: File
    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        DeviceEntropyEngine.resetCacheForTesting()
        tempDir = Files.createTempDirectory("aerial_entropy_test").toFile()
        context = mockk<Context>()
        every { context.noBackupFilesDir } returns tempDir
    }

    @AfterEach
    fun tearDown() {
        DeviceEntropyEngine.resetCacheForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    @DisplayName("Should generate and persist immutable seed across cache clears")
    fun testGenerateAndPersistSeed() {
        val seed1 = DeviceEntropyEngine.getDeviceSeed(context)
        assertNotEquals(0L, seed1)

        val entropyFile = File(tempDir, "aerial_entropy.bin")
        assertTrue(entropyFile.exists())
        assertEquals(8L, entropyFile.length())

        // In-memory cache hit
        val seed2 = DeviceEntropyEngine.getDeviceSeed(context)
        assertEquals(seed1, seed2)

        // Clear in-memory cache to force reading from disk
        DeviceEntropyEngine.resetCacheForTesting()
        val seed3 = DeviceEntropyEngine.getDeviceSeed(context)
        assertEquals(seed1, seed3)
    }

    @Test
    @DisplayName("Different device contexts should generate distinct seeds")
    fun testDistinctDeviceSeeds() {
        val tempDir2 = Files.createTempDirectory("aerial_entropy_test_2").toFile()
        val context2 = mockk<Context>()
        every { context2.noBackupFilesDir } returns tempDir2

        val seed1 = DeviceEntropyEngine.getDeviceSeed(context)

        DeviceEntropyEngine.resetCacheForTesting()
        val seed2 = DeviceEntropyEngine.getDeviceSeed(context2)

        assertNotEquals(seed1, seed2)
        tempDir2.deleteRecursively()
    }
}
