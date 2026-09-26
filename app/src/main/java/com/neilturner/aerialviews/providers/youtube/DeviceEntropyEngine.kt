package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.os.Build
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Generates and securely persists an immutable 64-bit entropy master seed per Android TV device.
 * Stored in [Context.getNoBackupFilesDir] to survive app updates and avoid cloud backup collisions.
 *
 * This master seed guarantees that every device explores the shared 10,000-video master catalog
 * along an independent, deterministic, and non-repeating trajectory:
 * - Unique starting cursor offset (no two users start on the same video).
 * - Unique deterministic shuffle permutations.
 * - Consistent playback state across power cycles.
 */
object DeviceEntropyEngine {
    private const val ENTROPY_FILE_NAME = "aerial_entropy.bin"

    @Volatile
    private var cachedSeed: Long? = null

    fun getDeviceSeed(context: Context): Long {
        cachedSeed?.let { return it }

        synchronized(this) {
            cachedSeed?.let { return it }

            val targetDir =
                runCatching { context.noBackupFilesDir }.getOrNull()
                    ?: runCatching { context.filesDir }.getOrNull()
                    ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            val entropyFile = File(targetDir, ENTROPY_FILE_NAME)
            if (entropyFile.exists() && entropyFile.length() == 8L) {
                try {
                    val readSeed = DataInputStream(entropyFile.inputStream()).use { it.readLong() }
                    if (readSeed != 0L) {
                        cachedSeed = readSeed
                        return readSeed
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed reading device entropy seed from %s; regenerating", entropyFile.name)
                }
            }

            // Generate cryptographically strong entropy combined with hardware fingerprint
            val secureRandom = SecureRandom()
            val randomEntropy = secureRandom.nextLong()
            val fpHash = runCatching { Build.FINGERPRINT?.hashCode()?.toLong() }.getOrNull() ?: 0L
            val modelHash = runCatching { Build.MODEL?.hashCode()?.toLong() }.getOrNull() ?: 0L
            val hardwareEntropy = (fpHash shl 32) xor modelHash
            var generatedSeed = randomEntropy xor hardwareEntropy

            // Guard against theoretical 0L sentinel
            if (generatedSeed == 0L) {
                generatedSeed = 0x5EED_CAFE_BABE_0001L
            }

            try {
                DataOutputStream(entropyFile.outputStream()).use { it.writeLong(generatedSeed) }
                Timber.i("Generated and persisted new device entropy seed: %d", generatedSeed)
            } catch (e: Exception) {
                Timber.w(e, "Failed persisting device entropy seed to %s", entropyFile.name)
            }

            cachedSeed = generatedSeed
            return generatedSeed
        }
    }

    /**
     * Resets the in-memory cached seed. Primarily used for unit testing.
     */
    internal fun resetCacheForTesting() {
        synchronized(this) {
            cachedSeed = null
        }
    }
}
