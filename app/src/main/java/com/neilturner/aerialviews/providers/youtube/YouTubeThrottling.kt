package com.neilturner.aerialviews.providers.youtube

import android.content.SharedPreferences
import androidx.core.content.edit
import timber.log.Timber

/**
 * Circuit breaker for YouTube's anonymous-access bot gate
 * (SignInConfirmNotBotException / "Sign in to confirm that you're not a bot").
 *
 * The gate is IP-based and temporary, but hammering the player endpoint while
 * blocked only extends it — and every blocked extraction burns seconds of
 * black-screen loading. While the breaker is open the repository serves
 * cached URLs (even expiring ones) and skips refresh extractions entirely.
 *
 * State is persisted: Android TV kills background screensaver processes
 * whenever the user opens another app, and an in-memory-only breaker would
 * reset on every such death and hammer the blocked edge again. Cooldown
 * escalates exponentially per consecutive episode (45m -> 90m -> 180m ...,
 * capped at 24h) and resets on the first proven-successful extraction.
 */
object YouTubeThrottling {
    private const val TAG = "YouTubeThrottle"
    private const val BASE_COOLDOWN_MS = 45L * 60L * 1000L
    private const val MAX_COOLDOWN_MS = 24L * 60L * 60L * 1000L
    private const val MAX_CONSECUTIVE = 6 // 45m * 2^5 = 24h cap
    private const val KEY_BLOCKED_UNTIL = "yt_bot_blocked_until"
    private const val KEY_BLOCK_COUNT = "yt_bot_block_count"

    @Volatile
    private var blockedUntilMs: Long = 0L

    @Volatile
    private var consecutiveBlocks: Int = 0

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Must be called once per process (repository init covers all paths). */
    @Synchronized
    fun init(sharedPreferences: SharedPreferences) {
        prefs = sharedPreferences
        blockedUntilMs = sharedPreferences.getLong(KEY_BLOCKED_UNTIL, 0L)
        consecutiveBlocks = sharedPreferences.getInt(KEY_BLOCK_COUNT, 0)
    }

    /**
     * Records a bot-gate hit. Only the first hit per episode escalates:
     * further hits while already blocked are the same episode, not new ones,
     * so they must neither extend the clock nor inflate the count.
     */
    @Synchronized
    fun noteBotBlock() {
        val now = System.currentTimeMillis()
        if (now < blockedUntilMs) {
            return
        }
        consecutiveBlocks = (consecutiveBlocks + 1).coerceAtMost(MAX_CONSECUTIVE)
        blockedUntilMs = now + cooldownFor(consecutiveBlocks)
        persist()
        Timber.tag(TAG).w(
            "YouTube bot gate hit (episode %d), pausing extractions for %d minutes",
            consecutiveBlocks,
            remainingCooldownMinutes(),
        )
    }

    /** A real extraction succeeded: the gate is demonstrably lifted. */
    @Synchronized
    fun noteExtractionSuccess() {
        if (consecutiveBlocks == 0 && blockedUntilMs == 0L) {
            return
        }
        consecutiveBlocks = 0
        blockedUntilMs = 0L
        persist()
    }

    fun isBlocked(): Boolean = System.currentTimeMillis() < blockedUntilMs

    fun remainingCooldownMs(): Long =
        (blockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    fun remainingCooldownMinutes(): Long =
        (remainingCooldownMs() + 59_999L) / 60_000L

    internal fun consecutiveBlocksForTest(): Int = consecutiveBlocks

    /** Test-only reset so the global cooldown never leaks between tests. */
    @Synchronized
    internal fun clearForTest() {
        blockedUntilMs = 0L
        consecutiveBlocks = 0
        prefs?.edit {
            remove(KEY_BLOCKED_UNTIL)
            remove(KEY_BLOCK_COUNT)
        }
    }

    private fun cooldownFor(consecutive: Int): Long {
        val step = consecutive.coerceIn(1, MAX_CONSECUTIVE)
        return (BASE_COOLDOWN_MS * (1L shl (step - 1))).coerceAtMost(MAX_COOLDOWN_MS)
    }

    private fun persist() {
        prefs?.edit {
            putLong(KEY_BLOCKED_UNTIL, blockedUntilMs)
            putInt(KEY_BLOCK_COUNT, consecutiveBlocks)
        }
    }
}
