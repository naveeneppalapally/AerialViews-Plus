package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import java.util.ArrayDeque
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber
import kotlin.random.Random

class YouTubeSourceRepository(
    private val context: Context,
    private val cacheDao: YouTubeCacheDao,
    private val watchHistoryDao: YouTubeWatchHistoryDao,
    private val sharedPreferences: SharedPreferences,
    private val searcher: VideoSearcher = NewPipeVideoSearcher(),
    extractor: StreamExtractor? = null,
) {
    // Lazy so constructing the repository never touches the network stack or
    // Context.applicationContext until an extraction is actually requested.
    // (Eager default args broke mocked-Context tests and would do real work.)
    private val streamExtractor: StreamExtractor by lazy {
        extractor ?: NewPipeStreamExtractor(context.applicationContext)
    }
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingManualFullRebuild = AtomicBoolean(false)

    init {
        // Restores persisted breaker state; without this a process death
        // (TV kills background dreams constantly) would hammer a live gate.
        YouTubeThrottling.init(sharedPreferences)
    }
    
    suspend fun triggerFullLibraryRebuild() {
        if (!refreshMutex.tryLock()) {
            pendingManualFullRebuild.set(true)
            _refreshEvents.emit(RefreshEvent.AlreadyInProgress)
            Log.i(TAG, "Manual rebuild queued, another refresh holds the lock")
            return
        }
        Log.i(TAG, "Manual library rebuild started")
        try {
            // Snapshot before refresh so a network failure/timeout can never wipe the library.
            val snapshot = cacheDao.getAllGood()
            try {
                withTimeout(5 * 60 * 1000L) { // 5-minute safety timeout
                    performLoadFreshSearchResults(replaceExistingCache = true)
                }
            } catch (exception: Exception) {
                if (cacheDao.countGoodEntries() == 0 && snapshot.isNotEmpty()) {
                    runCatching { cacheDao.insertAll(snapshot) }
                    Timber.tag(TAG).w("Restored %s cached YouTube entries after failed library rebuild", snapshot.size)
                }
                throw exception
            }
        } catch (exception: YouTubeBotBlockedException) {
            // Distinct from failure: the gate is up, the cache is intact, and
            // the user deserves a reason — not a spin, not a scary error.
            _refreshEvents.emit(RefreshEvent.BotBlocked(exception.cooldownMinutes))
            _libraryState.value =
                YouTubeLibraryState.BotBlocked(
                    persistedCount = cacheDao.countGoodEntries(),
                    cooldownMinutes = exception.cooldownMinutes,
                )
            Timber.tag(TAG).i(exception, "Library rebuild skipped, YouTube bot gate is up")
        } catch (exception: Exception) {
            _libraryState.value =
                YouTubeLibraryState.Failed(
                    persistedCount = cacheDao.countGoodEntries(),
                    error = exception,
                )
            Timber.tag(TAG).e(exception, "Forced library rebuild failed or timed out")
        } finally {
            val finalCount = cacheDao.countGoodEntries()
            sharedPreferences.edit { putString(KEY_COUNT, finalCount.toString()) }
            // Legacy settlement stays untouched; the state flow settles only
            // on success paths inside performLoadFreshSearchResults so a
            // failure state is never immediately overwritten by Idle.
            if (_libraryState.value !is YouTubeLibraryState.BotBlocked &&
                _libraryState.value !is YouTubeLibraryState.Failed
            ) {
                setLibraryStateIdle()
            }
            Log.i(TAG, "Manual library rebuild finished, count=$finalCount")
            refreshMutex.unlock()
            if (pendingManualFullRebuild.getAndSet(false)) {
                repositoryScope.launch {
                    triggerFullLibraryRebuild()
                }
            }
        }
    }

    private val backgroundWarmInFlight = AtomicBoolean(false)
    private val lastBackgroundWarmAt = AtomicLong(0L)
    private val preResolvedLock = Any()
    private val _cacheFullEvent = MutableStateFlow(false)
    val cacheFullEvent: StateFlow<Boolean> = _cacheFullEvent.asStateFlow()
    private val _refreshEvents = MutableSharedFlow<RefreshEvent>(extraBufferCapacity = 16)
    val refreshEvents: SharedFlow<RefreshEvent> = _refreshEvents.asSharedFlow()

    /**
     * Single source of truth for the library counter (Phase 1, sole owner
     * since Phase 4). Every [YouTubeLibraryState.persistedCount] is a
     * Room-committed count, never an in-memory candidate tally.
     */
    private val _libraryState =
        MutableStateFlow<YouTubeLibraryState>(
            YouTubeLibraryState.Idle(
                persistedCount = sharedPreferences.getString(KEY_COUNT, "0")?.toIntOrNull() ?: 0,
                lastRefreshedAt = sharedPreferences.getLong(KEY_LAST_SEARCH_AT, 0L),
            ),
        )
    val libraryState: StateFlow<YouTubeLibraryState> = _libraryState.asStateFlow()

    /** Marks a category toggle as pending (debounce window). See state docs. */
    fun noteCategoryPending() {        val current =
            when (val state = _libraryState.value) {
                is YouTubeLibraryState.Idle -> state.persistedCount
                is YouTubeLibraryState.CategoryPending -> state.persistedCount
                is YouTubeLibraryState.Removing -> state.persistedCount
                is YouTubeLibraryState.Searching -> state.persistedCount
                is YouTubeLibraryState.Populating -> state.persistedCount
                is YouTubeLibraryState.BotBlocked -> state.persistedCount
                is YouTubeLibraryState.Failed -> state.persistedCount
                is YouTubeLibraryState.Disabled -> state.persistedCount
            }
        _libraryState.value = YouTubeLibraryState.CategoryPending(persistedCount = current)
    }

    /**
     * Instant removal preview for a category toggle. Scans the DB (read-only,
     * milliseconds) and emits [Removing] with the post-delete remaining count
     * so the counter drops in the same frame as the tap — no debounce wait.
     * Changes nothing: no delete, no snapshot write. The debounced
     * [applyCategoryDeltaRefresh] confirms seconds later with the real
     * DB-committed count and self-corrects any preview skew.
     */
    suspend fun previewCategoryRemoval() {
        // Runs on dbDispatcher (see companion): blocking NewPipe network
        // calls can saturate Dispatchers.IO for tens of seconds, and this
        // millisecond read must never queue behind them.
        withContext(dbDispatcher) {
            val preview = categoryManager.previewCategoryRemovalSnapshot()
            Log.d(TAG, "Removal preview: removed=${preview.removedCount} remaining=${preview.remainingCount}")
            if (preview.removedCount > 0) {
                _libraryState.value =
                    YouTubeLibraryState.Removing(
                        persistedCount = preview.remainingCount,
                        removedCount = preview.removedCount,
                    )
            }
        }
    }

    private suspend fun setLibraryStateIdle() {
        val count = cacheDao.countGoodEntries()
        if (!sharedPreferences.getBoolean(KEY_ENABLED, true)) {
            _libraryState.value = YouTubeLibraryState.Disabled(persistedCount = count)
        } else {
            _libraryState.value =
                YouTubeLibraryState.Idle(
                    persistedCount = count,
                    lastRefreshedAt = sharedPreferences.getLong(KEY_LAST_SEARCH_AT, 0L),
                )
        }
    }
    
    sealed interface RefreshEvent {
        data object AlreadyInProgress : RefreshEvent

        data class BotBlocked(
            val cooldownMinutes: Long,
        ) : RefreshEvent
    }

    private val refreshMutex = Mutex()

    private var badCountThisSession = 0

    @Volatile
    private var preResolvedEntry: YouTubeCacheEntity? = null

    @Volatile
    private var preResolvingJob: Job? = null

    @Volatile
    private var preResolvingTarget: String? = null

    private val historyTracker =
        YouTubeHistoryTracker(
            cacheDao = cacheDao,
            watchHistoryDao = watchHistoryDao,
            sharedPreferences = sharedPreferences,
        )

    private val categoryManager =
        YouTubeCategoryManager(
            cacheDao = cacheDao,
            sharedPreferences = sharedPreferences,
        )

    init {
        initializeCategorySnapshotIfNeeded()
        repositoryScope.launch {
            seedCacheIfEmpty()
            val dbCount = cacheDao.countGoodEntries()
            sharedPreferences.edit {
                putString(KEY_COUNT, dbCount.toString())
            }
            setLibraryStateIdle()
        }
    }

    suspend fun seedCacheIfEmpty() {
        withContext(Dispatchers.IO) {
            try {
                if (cacheDao.countGoodEntries() > 0) {
                    return@withContext
                }
                val assetManager = runCatching { context.assets }.getOrNull() ?: return@withContext
                val jsonString = runCatching {
                    assetManager.open("curated_youtube_seed.json").bufferedReader().use { it.readText() }
                }.getOrNull() ?: return@withContext

                val jsonArray = org.json.JSONArray(jsonString)
                val seedEntries = mutableListOf<YouTubeCacheEntity>()
                val now = System.currentTimeMillis()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val videoId = obj.getString("videoId")
                    val title = obj.getString("title")
                    val uploaderName = obj.optString("uploaderName", "")
                    val durationSeconds = obj.optInt("durationSeconds", 0)
                    val categoryKey = obj.optString("categoryKey", "")
                    val videoPageUrl = obj.optString("videoPageUrl", "https://www.youtube.com/watch?v=$videoId")
                    seedEntries.add(
                        YouTubeCacheEntity(
                            videoId = videoId,
                            videoPageUrl = videoPageUrl,
                            streamUrl = "",
                            audioStreamUrl = "",
                            title = title,
                            uploaderName = uploaderName,
                            durationSeconds = durationSeconds,
                            categoryKey = categoryKey,
                            streamUrlExpiresAt = 0L,
                            searchCachedAt = now,
                            searchQuery = "seed:$categoryKey",
                        )
                    )
                }
                if (seedEntries.isNotEmpty()) {
                    cacheDao.insertIgnore(seedEntries)
                    Timber.tag(TAG).i("Seeded YouTube cache with %s pristine curated entries", seedEntries.size)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to seed YouTube cache from assets")
            }
        }
    }

    fun consumeCacheFullEvent() {
        _cacheFullEvent.value = false
    }

    suspend fun getNextVideoUrl(): String =
        withContext(Dispatchers.IO) {
            ensureStreamQualitySignatureFresh()
            val lastPlayedVideoId = playHistory().lastOrNull()

            consumeAnyPreResolvedEntry()?.let { cachedEntry ->
                if (cachedEntry.videoId == lastPlayedVideoId) {
                    Timber.tag(TAG).d("Discarding stale pre-resolved repeat: %s", cachedEntry.videoId)
                } else if (!cachedEntry.isBad && isUsableCachedStream(cachedEntry)) {
                    Log.i(TAG, "Using pre-resolved URL instantly")
                    recordPlayback(cachedEntry)
                    maybeWarmSearchCacheNearPlaylistEnd()
                    preResolveNext(repositoryScope)
                    return@withContext cachedEntry.streamUrl
                }
            }

            val entries = ensureSearchCache()
            prunePlayHistory(entries)
            val attemptedIds = mutableSetOf<String>()

            repeat(MAX_PLAYBACK_RESOLVE_ATTEMPTS) {
                val selectedEntry =
                    selectEntryForPlayback(
                        entries.filterNot { entry ->
                            entry.isBad || entry.videoId in attemptedIds
                        },
                    ) ?: return@repeat
                attemptedIds += selectedEntry.videoId

                resolveEntryStreamUrlOrNull(selectedEntry)?.let { resolvedUrl ->
                    preResolveNext(repositoryScope)
                    return@withContext resolvedUrl
                }
            }

            throw YouTubeSourceException("No videos available")
        }

    suspend fun getCachedVideos(): List<YouTubeCacheEntity> =
        withContext(Dispatchers.IO) {
            ensureStreamQualitySignatureFresh()
            val searchCache = ensureSearchCache()
            prunePlayHistory(searchCache)
            val cachedEntries = buildPlaylistEntries(searchCache)
            updateCachedCount(cachedEntries.size)
            cachedEntries
        }

    suspend fun getLocalCachedVideos(): List<YouTubeCacheEntity> =
        withContext(Dispatchers.IO) {
            ensureStreamQualitySignatureFresh()
            if (isCacheVersionStale() || isCacheSignatureStale()) {
                return@withContext runCatching { ensureSearchCache() }
                    .getOrElse { exception ->
                        Timber.tag(TAG).w(exception, "Using locally cached YouTube entries after stale-cache refresh failure")
                        cacheDao.getAllGood()
                    }.let { refreshedEntries ->
                        prunePlayHistory(refreshedEntries)
                        buildPlaylistEntries(refreshedEntries).also { entries ->
                            updateCachedCount(entries.size)
                        }
                    }
            }

            val cachedEntries = cacheDao.getAllGood()
            if (cachedEntries.isEmpty()) {
                updateCachedCount(0)
                return@withContext emptyList()
            }

            prunePlayHistory(cachedEntries)
            buildPlaylistEntries(cachedEntries).also { entries ->
                updateCachedCount(entries.size)
            }
        }

    suspend fun getCachedVideosSnapshot(): List<YouTubeCacheEntity> =
        withContext(Dispatchers.IO) {
            ensureStreamQualitySignatureFresh()
            val cachedEntries = cacheDao.getAllGood()
            if (cachedEntries.isEmpty()) {
                updateCachedCount(0)
                return@withContext emptyList()
            }

            prunePlayHistory(cachedEntries)
            buildPlaylistEntries(cachedEntries).also { entries ->
                updateCachedCount(entries.size)
            }
        }

    suspend fun getCacheSize(): Int =
        withContext(Dispatchers.IO) {
            cacheDao.countGoodEntries()
        }

    suspend fun applyCurrentFilters(): Int =
        withContext(Dispatchers.IO) {
            val initialCount = cacheDao.countGoodEntries()
            val removedByCategory = applyCurrentCategoryFilterInternal()
            val filteredCount = currentFilteredCount()
            if (filteredCount != initialCount || removedByCategory > 0) {
                clearPreResolvedEntry()
            }
            updateCachedCount(filteredCount)
            Log.i(
                TAG,
                "Applied YouTube cache filters instantly (categories=${enabledCategoryKeys()}, " +
                    "removedByCategory=$removedByCategory, remaining=$filteredCount)",
            )
            filteredCount
        }

    suspend fun applyCurrentCategoryFilter(): Int =
        withContext(Dispatchers.IO) {
            val removed = applyCurrentCategoryFilterInternal()
            val filteredCount = currentFilteredCount()
            if (removed > 0) {
                clearPreResolvedEntry()
            }
            val dbCount = cacheDao.countGoodEntries()
            updateCachedCount(dbCount)
            markCategoryStateFresh(dbCount)
            Log.i(
                TAG,
                "Applied YouTube category filter instantly (enabled=${enabledCategoryKeys()}, removed=$removed, remaining=$dbCount)",
            )
            dbCount
        }

    data class DeltaRefreshResult(
        val removedCount: Int,
        val insertedCount: Int,
        val countAfterRemoval: Int,
        val finalCount: Int,
        val allCategoriesDisabled: Boolean,
        val libraryFull: Boolean,
        val removedCategoriesCount: Int,
    )

    suspend fun previewCategoryRemovalSnapshot(): YouTubeCategoryManager.CategoryRemovalPreview =
        withContext(Dispatchers.IO) {
            categoryManager.previewCategoryRemovalSnapshot()
        }

    suspend fun applyCategoryDeltaRefresh(): DeltaRefreshResult =
        withContext(Dispatchers.IO) {
            val currentEnabled = enabledCategoryKeys().toSet()
            val previousEnabled = categoryManager.readCategorySnapshot()
            val removedCategories = previousEnabled - currentEnabled
            val addedCategories = currentEnabled - previousEnabled
            val removedCategoriesCount = removedCategories.size
            Log.d(TAG, "Delta start: current=$currentEnabled previous=$previousEnabled removed=$removedCategories added=$addedCategories")

            if (removedCategories.isNotEmpty()) {
                // Delete on dbDispatcher (see companion): same IO-starvation
                // reasoning as the preview above. No network happens here.
                val result =
                    withContext(dbDispatcher) {
                        val removedCount = categoryManager.applyCurrentCategoryFilterInternal()
                        val dbCount = cacheDao.countGoodEntries()
                        Log.d(TAG, "Delta removal committed: removedRows=$removedCount dbCount=$dbCount")
                        sharedPreferences.edit { putString(KEY_COUNT, dbCount.toString()) }
                        if (removedCount > 0) {
                            clearPreResolvedEntry()
                            _libraryState.value =
                                YouTubeLibraryState.Removing(
                                    persistedCount = dbCount,
                                    removedCount = removedCount,
                                )
                        }
                        markCategoryStateFresh(dbCount)
                        setLibraryStateIdle()
                        DeltaRefreshResult(
                            removedCount = removedCount,
                            insertedCount = 0,
                            countAfterRemoval = dbCount,
                            finalCount = dbCount,
                            allCategoriesDisabled = currentEnabled.isEmpty(),
                            libraryFull = dbCount >= TARGET_CACHE_SIZE,
                            removedCategoriesCount = removedCategoriesCount,
                        )
                    }
                if (result.finalCount < TARGET_CACHE_SIZE && currentEnabled.isNotEmpty()) {
                    scheduleBackgroundWarmCache(
                        forceSearchRefresh = true,
                        replaceExistingCacheOverride = false,
                    )
                }
                return@withContext result
            }

            val dbCount = cacheDao.countGoodEntries()
            if (addedCategories.isEmpty()) {
                markCategoryStateFresh(dbCount)
                setLibraryStateIdle()
                return@withContext DeltaRefreshResult(
                    removedCount = 0,
                    insertedCount = 0,
                    countAfterRemoval = dbCount,
                    finalCount = dbCount,
                    allCategoriesDisabled = currentEnabled.isEmpty(),
                    libraryFull = dbCount >= TARGET_CACHE_SIZE,
                    removedCategoriesCount = 0,
                )
            }

            if (!refreshMutex.tryLock()) {
                markCategoryStateFresh(dbCount)
                setLibraryStateIdle()
                scheduleBackgroundWarmCache(
                    forceSearchRefresh = true,
                    replaceExistingCacheOverride = false,
                )
                return@withContext DeltaRefreshResult(
                    removedCount = 0,
                    insertedCount = 0,
                    countAfterRemoval = dbCount,
                    finalCount = dbCount,
                    allCategoriesDisabled = false,
                    libraryFull = dbCount >= TARGET_CACHE_SIZE,
                    removedCategoriesCount = 0,
                )
            }

            try {
                var removedCount = 0
                var insertedCount = 0
                var countAfterRemoval = 0

                try {
                    // Novelty snapshot BEFORE removal/insertion mutates history.
                    val preDeltaTiers = historyTracker.noveltyTiers()
                    val deltaNovelty = NoveltyState(tier1 = preDeltaTiers.tier1, tier2 = preDeltaTiers.tier2)

                    removedCount = categoryManager.applyCurrentCategoryFilterInternal()

                    var entriesSnapshot = cacheDao.getAllGood()
                    countAfterRemoval = cacheDao.countGoodEntries()
                    sharedPreferences.edit { putString(KEY_COUNT, countAfterRemoval.toString()) }
                    if (removedCount > 0) {
                        _libraryState.value =
                            YouTubeLibraryState.Removing(
                                persistedCount = countAfterRemoval,
                                removedCount = removedCount,
                            )
                    }

                    val currentEnabledList = currentEnabled.toList()
                    val categoryPlan = currentEnabledList.takeIf { it.isNotEmpty() }
                        ?.let { enabled ->
                            categoryManager.buildCategoryBalancePlan(enabled, entriesSnapshot, TARGET_CACHE_SIZE)
                        }
                    var preferredDeficitOrder = categoryPlan?.deficitCategories ?: emptyList()

                    val needsRebalance =
                        addedCategories.isNotEmpty() &&
                            countAfterRemoval >= TARGET_CACHE_SIZE &&
                            categoryPlan != null

                    if (needsRebalance) {
                        val plan = categoryPlan
                        val rebalanceOutcome =
                            categoryManager.rebalanceOverQuotaCategories(entriesSnapshot, plan.targets, recentPlaybackCutoff())
                        if (rebalanceOutcome.evictedVideoIds.isNotEmpty()) {
                            val evicted = cacheDao.deleteByVideoIds(rebalanceOutcome.evictedVideoIds)
                            removedCount += evicted
                        }
                        entriesSnapshot = cacheDao.getAllGood()
                        countAfterRemoval = cacheDao.countGoodEntries()
                        preferredDeficitOrder = rebalanceOutcome.deficitCategories
                    }

                    if (countAfterRemoval < TARGET_CACHE_SIZE && currentEnabledList.isNotEmpty()) {
                        val targets = categoryPlan?.targets ?: categoryManager.allocateCategoryTargets(currentEnabledList, TARGET_CACHE_SIZE)
                        var entriesForBackfill = entriesSnapshot
                        var remainingBackfill = (TARGET_CACHE_SIZE - countAfterRemoval).coerceAtLeast(0)
                        var rounds = 0
                        while (remainingBackfill > 0 && rounds < CATEGORY_DELTA_BACKFILL_ROUNDS) {
                            val counts = categoryManager.computeCategoryCounts(entriesForBackfill, targets.keys)
                            val categoriesToFill =
                                categoryManager.computeDeficitPriorityList(
                                    targets = targets,
                                    counts = counts,
                                    preferredOrder = preferredDeficitOrder,
                                ).ifEmpty { targets.keys.toList() }

                            var insertedThisRound = 0
                            categoriesToFill.forEach { category ->
                                if (remainingBackfill <= 0) {
                                    return@forEach
                                }
                                val categoryDeficit =
                                    ((targets[category] ?: 0) - (counts[category] ?: 0)).coerceAtLeast(0)
                                val extractionLimitForCategory =
                                    if (categoryDeficit > 0) {
                                        minOf(categoryDeficit, remainingBackfill)
                                    } else {
                                        minOf(remainingBackfill, CATEGORY_DELTA_FALLBACK_BATCH_PER_CATEGORY)
                                    }
                                if (extractionLimitForCategory <= 0) {
                                    return@forEach
                                }

                                val insertedForCategory =
                                    addEntriesForCategories(
                                        categoryKeys = listOf(category),
                                        existingEntries = entriesForBackfill,
                                        initialCount = countAfterRemoval + insertedCount,
                                        extractionLimit = extractionLimitForCategory,
                                        novelty = deltaNovelty,
                                    )
                                if (insertedForCategory > 0) {
                                    insertedCount += insertedForCategory
                                    insertedThisRound += insertedForCategory
                                    remainingBackfill -= insertedForCategory
                                    entriesForBackfill = cacheDao.getAllGood()
                                }
                            }

                            if (insertedThisRound <= 0) {
                                break
                            }
                            preferredDeficitOrder = categoriesToFill
                            rounds += 1
                        }
                    } else if (addedCategories.isNotEmpty() && countAfterRemoval >= TARGET_CACHE_SIZE) {
                        _cacheFullEvent.value = true
                    }
                } finally {
                    val finalCount = cacheDao.countGoodEntries()
                    sharedPreferences.edit { putString(KEY_COUNT, finalCount.toString()) }
                }

                if (removedCount > 0 || insertedCount > 0) {
                    clearPreResolvedEntry()
                }
                if (insertedCount == 0 && addedCategories.isNotEmpty() && YouTubeThrottling.isBlocked()) {
                    _refreshEvents.emit(RefreshEvent.BotBlocked(YouTubeThrottling.remainingCooldownMinutes()))
                }
                val dbCount = cacheDao.countGoodEntries()
                _libraryState.value =
                    YouTubeLibraryState.Idle(
                        persistedCount = dbCount,
                        lastRefreshedAt = sharedPreferences.getLong(KEY_LAST_SEARCH_AT, 0L),
                    )
                markCategoryStateFresh(dbCount)
                Log.i(
                    TAG,
                    "Applied category delta refresh (added=${addedCategories.size}, removed=${removedCategories.size}, " +
                        "removedCategoriesCount=$removedCategoriesCount, removedRows=$removedCount, " +
                        "postRemoval=$countAfterRemoval, backfilled=$insertedCount, finalCount=$dbCount)",
                )
                DeltaRefreshResult(
                    removedCount = removedCount,
                    insertedCount = insertedCount,
                    countAfterRemoval = countAfterRemoval,
                    finalCount = dbCount,
                    allCategoriesDisabled = currentEnabled.isEmpty(),
                    libraryFull = cacheDao.countGoodEntries() >= TARGET_CACHE_SIZE,
                    removedCategoriesCount = removedCategoriesCount,
                )
            } finally {
                refreshMutex.unlock()
            }
        }

    suspend fun markAsPlayed(videoId: String) =
        withContext(Dispatchers.IO) {
            cacheDao.getAllGood().firstOrNull { it.videoId == videoId }?.let { recordPlayback(it) }
        }

    fun playbackUrl(entry: YouTubeCacheEntity): String =
        if (hasFreshStreamUrl(entry)) {
            entry.streamUrl
        } else {
            entry.videoPageUrl
        }

    fun playbackAudioUrl(entry: YouTubeCacheEntity): String =
        if (hasFreshStreamUrl(entry)) {
            entry.audioStreamUrl
        } else {
            ""
        }

    private fun entryPlaybackUrls(entry: YouTubeCacheEntity): YouTubePlaybackUrls =
        YouTubePlaybackUrls(
            videoUrl = entry.streamUrl,
            audioUrl = entry.audioStreamUrl,
        )

    fun preWarmInBackground() {
        scheduleBackgroundWarmCache(forceSearchRefresh = true)
    }

    fun preResolveNext(scope: CoroutineScope) {
        // Single-slot pre-resolve: don't cancel an identical in-flight request.
        // Callers fire this on every playlist build and playback start, which
        // previously thrashed extractions so none ever completed.
        if (preResolvingJob?.isActive == true && preResolvingTarget == null) {
            return
        }
        preResolvingJob?.cancel()
        preResolvingTarget = null
        preResolvingJob =
            scope.launch(Dispatchers.IO) {
                ensureStreamQualitySignatureFresh()

                if (cacheDao.countGoodEntries() < COLD_CACHE_SKIP_THRESHOLD) {
                    clearPreResolvedEntry()
                    return@launch
                }

                try {
                    val nextEntry = selectNextCandidate() ?: run {
                        clearPreResolvedEntry()
                        return@launch
                    }
                    val resolvedAt = System.currentTimeMillis()
                    val resolvedPlayback = resolveEntryPlayback(nextEntry, recordPlayback = false)
                    cachePreResolvedEntry(
                        buildResolvedEntry(
                            entry = nextEntry,
                            resolvedPlayback = resolvedPlayback,
                            resolvedAt = resolvedAt,
                        ),
                    )
                    Timber.tag(TAG).d("Pre-resolved YouTube video: %s", nextEntry.title)
                } catch (exception: Exception) {
                    clearPreResolvedEntry()
                    Timber.tag(TAG).w(exception, "Failed to pre-resolve next YouTube video")
                }
            }
    }

    fun preResolveVideo(
        videoPageUrl: String,
        scope: CoroutineScope,
    ) {
        if (preResolvingJob?.isActive == true && preResolvingTarget == videoPageUrl) {
            return
        }
        preResolvingJob?.cancel()
        preResolvingTarget = videoPageUrl
        preResolvingJob =
            scope.launch(Dispatchers.IO) {
                try {
                    ensureStreamQualitySignatureFresh()

                    try {
                        val entry =
                            cacheDao.getByVideoPageUrl(videoPageUrl)
                                ?.takeIf { !it.isBad }
                                ?: if (YouTubeThrottling.isBlocked()) {
                                    // No cached row and the gate is up: extracting
                                    // here would burn time and hammer the edge.
                                    return@launch
                                } else {
                                    buildDirectCacheEntry(
                                        videoPageUrl = videoPageUrl,
                                        cachedAt = System.currentTimeMillis(),
                                        preferredQuality = preferredQuality(),
                                    )
                                }
                                ?: return@launch
                        val resolvedAt = System.currentTimeMillis()
                        val resolvedPlayback =
                            if (hasFreshStreamUrl(entry)) {
                                entryPlaybackUrls(entry)
                            } else {
                                resolveEntryPlayback(entry, recordPlayback = false)
                            }
                        cachePreResolvedEntry(
                            buildResolvedEntry(
                                entry = entry,
                                resolvedPlayback = resolvedPlayback,
                                resolvedAt = resolvedAt,
                            ),
                        )
                        Timber.tag(TAG).d("Pre-resolved requested YouTube video: %s", entry.title)
                    } catch (exception: Exception) {
                        clearPreResolvedEntry()
                        Timber.tag(TAG).w(exception, "Failed to pre-resolve requested YouTube video")
                    }
                } finally {
                    if (preResolvingTarget == videoPageUrl) {
                        preResolvingTarget = null
                    }
                }
            }
    }

    suspend fun preloadVideoUrl(videoPageUrl: String): String? =
        preloadVideoPlayback(videoPageUrl)?.videoUrl

    suspend fun preloadVideoPlayback(videoPageUrl: String): YouTubePlaybackUrls? =
        withContext(Dispatchers.IO) {
            ensureStreamQualitySignatureFresh()
            peekPreResolvedEntry(videoPageUrl)?.let { cachedEntry ->
                return@withContext entryPlaybackUrls(cachedEntry)
            }

            cacheDao.getByVideoPageUrl(videoPageUrl)?.takeIf { !it.isBad }?.let { cachedEntry ->
                if (hasFreshStreamUrl(cachedEntry)) {
                    return@withContext entryPlaybackUrls(cachedEntry)
                }
                return@withContext runCatching {
                    resolveEntryPlayback(cachedEntry, recordPlayback = false)
                }.getOrNull()
            }

            fetchDirectEntry(videoPageUrl)?.let(::entryPlaybackUrls)
        }

    /**
     * Shared direct-entry tail for the preload and resolve paths: build via
     * the extractor, persist, recount. Recording/warming/next-resolve stay
     * with the callers (resolve records playback, preload must not).
     */
    private suspend fun fetchDirectEntry(videoPageUrl: String): YouTubeCacheEntity? {
        // Fail fast while blocked: a network attempt here only burns the
        // resolve timeout and extends the gate.
        if (YouTubeThrottling.isBlocked()) {
            return null
        }
        return runCatching {
            buildDirectCacheEntry(
                videoPageUrl = videoPageUrl,
                cachedAt = System.currentTimeMillis(),
                preferredQuality = preferredQuality(),
            )
        }.getOrNull()?.also { directEntry ->
            // IGNORE preserves the stable categoryKey when this video is
            // already cached; refresh only its stream URL below.
            val inserted = cacheDao.insertIgnore(listOf(directEntry))
            if (inserted.firstOrNull() == -1L && directEntry.streamUrl.isNotBlank()) {
                cacheDao.updateStreamUrl(
                    directEntry.videoId,
                    directEntry.streamUrl,
                    directEntry.audioStreamUrl,
                    directEntry.streamUrlExpiresAt,
                )
            }
            updateCachedCount(cacheDao.countGoodEntries())
        }
    }

    suspend fun preloadProjectivyVideoUrl(videoPageUrl: String): String? =
        withContext(Dispatchers.IO) {
            ensureStreamQualitySignatureFresh()
            val projectivyQuality = projectivyPlaybackResolutionQuality()
            peekPreResolvedEntry(videoPageUrl)?.streamUrl
                ?.takeIf { streamUrl ->
                    isProjectivyPreferredReusableStream(streamUrl, projectivyQuality)
                }
                ?.let { return@withContext it }

            cacheDao.getByVideoPageUrl(videoPageUrl)?.takeIf { !it.isBad }?.let { cachedEntry ->
                if (hasFreshProjectivyStreamUrl(cachedEntry, projectivyQuality) &&
                    isProjectivyPreferredReusableStream(cachedEntry.streamUrl, projectivyQuality)
                ) {
                    return@withContext cachedEntry.streamUrl
                }

                resolveProjectivyStreamUrl(
                    videoPageUrl = videoPageUrl,
                    preferredQuality = projectivyQuality,
                    preferVideoOnly = projectivyPreferVideoOnly(),
                )?.also { resolvedUrl ->
                    cacheDao.updateStreamUrl(
                        cachedEntry.videoId,
                        resolvedUrl,
                        "",
                        System.currentTimeMillis() + STREAM_URL_TTL_MS,
                    )
                    return@withContext resolvedUrl
                }

                if (hasFreshProjectivyStreamUrl(cachedEntry, projectivyQuality)) {
                    return@withContext cachedEntry.streamUrl
                }
            }

            runCatching {
                buildDirectCacheEntry(
                    videoPageUrl = videoPageUrl,
                    cachedAt = System.currentTimeMillis(),
                    preferredQuality = projectivyQuality,
                    preferVideoOnly = projectivyPreferVideoOnly(),
                    allowAdaptiveManifests = true,
                    preferAdaptiveManifests = projectivyPreferAdaptiveManifests(projectivyQuality),
                    preferManifests = false,
                )
            }.getOrNull()
                ?.also { directEntry ->
                    if (!isProjectivyStableStreamUrl(directEntry.streamUrl, projectivyQuality)) {
                        return@also
                    }
                    // IGNORE preserves the stable categoryKey when this video
                    // is already cached; refresh only its stream URL below.
                    val inserted = cacheDao.insertIgnore(listOf(directEntry))
                    if (inserted.firstOrNull() == -1L && directEntry.streamUrl.isNotBlank()) {
                        cacheDao.updateStreamUrl(
                            directEntry.videoId,
                            directEntry.streamUrl,
                            directEntry.audioStreamUrl,
                            directEntry.streamUrlExpiresAt,
                        )
                    }
                    updateCachedCount(cacheDao.countGoodEntries())
                }?.streamUrl
                ?.takeIf(::isProjectivyUsableStreamUrl)
        }

    suspend fun resolveVideoUrl(videoPageUrl: String): String =
        resolveVideoPlayback(videoPageUrl).videoUrl

    suspend fun resolveVideoPlayback(videoPageUrl: String): YouTubePlaybackUrls =
        withContext(Dispatchers.IO) {
            ensureStreamQualitySignatureFresh()
            consumePreResolvedEntry(videoPageUrl)?.let { cachedEntry ->
                if (!cachedEntry.isBad && isUsableCachedStream(cachedEntry)) {
                    recordPlayback(cachedEntry)
                    maybeWarmSearchCacheNearPlaylistEnd()
                    preResolveNext(repositoryScope)
                    return@withContext entryPlaybackUrls(cachedEntry)
                }
            }

            cacheDao.getByVideoPageUrl(videoPageUrl)?.takeIf { !it.isBad }?.let { cachedEntry ->
                resolveEntryPlaybackOrNull(cachedEntry)?.let { resolvedPlayback ->
                    return@withContext resolvedPlayback.also {
                        preResolveNext(repositoryScope)
                    }
                }
            }

            val directEntry =
                fetchDirectEntry(videoPageUrl) ?: throw if (YouTubeThrottling.isBlocked()) {
                    // Distinct from "no videos": the gate is up and nothing
                    // cached is usable. Callers skip fast; future UI can show
                    // the cooldown notice instead of a generic error.
                    YouTubeBotBlockedException(YouTubeThrottling.remainingCooldownMinutes())
                } else {
                    YouTubeSourceException("No videos available")
                }

            recordPlayback(directEntry)
            maybeWarmSearchCacheNearPlaylistEnd()
            preResolveNext(repositoryScope)
            entryPlaybackUrls(directEntry)
        }

    suspend fun warmCache(
        forceSearchRefresh: Boolean = false,
        replaceExistingCacheOverride: Boolean? = null,
    ): Int =
        withContext(Dispatchers.IO) {
            ensureStreamQualitySignatureFresh()
            val cachedEntries = cacheDao.getAllGood()

            val refreshedEntries =
                when {
                    cachedEntries.isEmpty() -> {
                        Log.d(TAG, "warmCache: empty cache, full fill")
                        loadFreshSearchResults(replaceExistingCache = true, skipIfPopulated = true)
                    }
                    forceSearchRefresh ||
                        isSearchCacheExpired() ||
                        isCacheVersionStale() ||
                        isCacheSignatureStale() ||
                        isCacheUndersized(cachedEntries) -> {
                        runCatching {
                            loadFreshSearchResults(
                                replaceExistingCache =
                                    replaceExistingCacheOverride ?: (forceSearchRefresh || isCacheSignatureStale()),
                            )
                        }.getOrElse { exception ->
                            Timber.tag(TAG).w(exception, "Using cached YouTube entries after warm refresh failure")
                            updateCachedCount(cachedEntries.size)
                            cachedEntries
                        }
                    }

                    else -> refreshExpiringStreamUrls(cachedEntries)
                }

            val liveCount = cacheDao.countGoodEntries()
            updateCachedCount(liveCount)
            liveCount
        }

    private suspend fun ensureSearchCache(): List<YouTubeCacheEntity> {
        val cachedEntries = cacheDao.getAllGood()
        if (cachedEntries.isEmpty()) {
            return try {
                loadFreshSearchResults(replaceExistingCache = true, skipIfPopulated = true)
            } catch (exception: Exception) {
                throw when (exception) {
                    is YouTubeSourceException -> exception
                    else -> YouTubeSourceException("No videos available", exception)
                }
            }
        }

        if (isCacheVersionStale() || isCacheSignatureStale()) {
            return runCatching {
                loadFreshSearchResults(replaceExistingCache = true)
            }
                .getOrElse { exception ->
                    Timber.tag(TAG).w(exception, "Using cached YouTube entries after synchronous cache refresh failure")
                    updateCachedCount(cachedEntries.size)
                    cachedEntries
                }
        }

        if (shouldRunBackgroundSearchWarm(cachedEntries)) {
            scheduleBackgroundWarmCache(forceSearchRefresh = true)
        } else if (hasExpiringStreams(cachedEntries)) {
            scheduleBackgroundWarmCache(forceSearchRefresh = false)
        }

        updateCachedCount(cachedEntries.size)
        return cachedEntries
    }

    suspend fun refreshSearchResults(
        replaceExistingCache: Boolean = true,
        skipIfPopulated: Boolean = false,
    ): List<YouTubeCacheEntity> =
        withContext(Dispatchers.IO) {
            loadFreshSearchResults(replaceExistingCache, skipIfPopulated)
        }

    private suspend fun loadFreshSearchResults(
        replaceExistingCache: Boolean = false,
        skipIfPopulated: Boolean = false,
    ): List<YouTubeCacheEntity> {
        return refreshMutex.withLock {
            // Deduplicate automatic cold fills. Several triggers race on an
            // empty cache — the 10s prewarm, the 90s startup worker, a
            // settings-open prewarm, concurrent playback demands — and the
            // mutex only serializes them: the loser used to run a second
            // full 25-query search after the winner persisted videos,
            // burning quota and painting a phantom "Searching… x/25" the
            // user never asked for. Re-check under the lock; if the winner
            // already populated the cache, stand down and serve it.
            // Forced paths (manual rebuild) bypass this via perform*.
            if (skipIfPopulated && cacheDao.countGoodEntries() >= COLD_CACHE_SKIP_THRESHOLD) {
                return@withLock categoryManager.filteredExistingEntries(cacheDao.getAllGood())
            }
            performLoadFreshSearchResults(replaceExistingCache)
        }
    }

    private suspend fun performLoadFreshSearchResults(
        replaceExistingCache: Boolean = false,
    ): List<YouTubeCacheEntity> {
        if (enabledCategoryKeys().isEmpty()) {
            Timber.tag(TAG).w(
                "All YouTube categories are disabled; using fallback discovery categories to avoid an empty playlist",
            )
        }

        return try {
            // Fail fast: searching while gated burns minutes to learn what the
            // breaker already knows — no extraction can succeed right now.
            if (YouTubeThrottling.isBlocked()) {
                throw YouTubeBotBlockedException(YouTubeThrottling.remainingCooldownMinutes())
            }
            withTimeout(5 * 60 * 1000L) { // 5-minute safety timeout
                val refreshPlan = buildRefreshPlan()
                // Snapshot novelty BEFORE persist updates the list below.
                val preTiers = historyTracker.noveltyTiers()
                val novelty = NoveltyState(tier1 = preTiers.tier1, tier2 = preTiers.tier2)
                val searchResults = searchRefreshCandidates(refreshPlan)
                val extractedEntries =
                    extractRefreshEntries(
                        refreshPlan = refreshPlan,
                        searchResults = searchResults,
                        replaceExistingCache = replaceExistingCache,
                        initialCount = refreshPlan.existingEntries.size,
                        novelty = novelty,
                    )
                // (Mid-refresh gate drops are reported distinctly from inside
                // extractRefreshEntries, which sees the eager/metadata split.)
                val entries = mergeRefreshedEntries(refreshPlan, extractedEntries, replaceExistingCache)
                persistFreshEntries(refreshPlan, entries)
                val finalEntries = topUpCacheToTargetAfterRefresh(entries, novelty)
                logNoveltyReport(refreshPlan.cachedAt, preTiers, novelty, finalEntries)
                _libraryState.value =
                    YouTubeLibraryState.Idle(
                        persistedCount = cacheDao.countGoodEntries(),
                        lastRefreshedAt = sharedPreferences.getLong(KEY_LAST_SEARCH_AT, 0L),
                    )
                finalEntries
            }
        } catch (exception: YouTubeBotBlockedException) {
            _libraryState.value =
                YouTubeLibraryState.BotBlocked(
                    persistedCount = cacheDao.countGoodEntries(),
                    cooldownMinutes = exception.cooldownMinutes,
                )
            throw exception
        } catch (exception: Exception) {
            val fallbackEntries = categoryManager.filteredExistingEntries(cacheDao.getAllGood())
            if (fallbackEntries.isNotEmpty()) {
                Timber.tag(TAG).w(exception, "Using filtered cached YouTube entries after refresh failure or timeout")
                updateCachedCount(fallbackEntries.size)
                setLibraryStateIdle()
                fallbackEntries
            } else {
                _libraryState.value =
                    YouTubeLibraryState.Failed(
                        persistedCount = cacheDao.countGoodEntries(),
                        error = exception,
                    )
                throw when (exception) {
                    is YouTubeSourceException -> exception
                    else -> YouTubeSourceException("Failed to refresh YouTube videos or timed out", exception)
                }
            }
        } finally {
            val finalCount = cacheDao.countGoodEntries()
            sharedPreferences.edit { putString(KEY_COUNT, finalCount.toString()) }
        }
    }

    private suspend fun buildRefreshPlan(): RefreshPlan {
        val cachedAt = System.currentTimeMillis()
        val categoryPreferences = categoryPreferences()
        val existingEntries = categoryManager.filteredExistingEntries(cacheDao.getAllGood())
        val isColdStart = existingEntries.size < COLD_CACHE_SKIP_THRESHOLD
        return RefreshPlan(
            query = searchQuery(),
            queryPool =
                QueryFormulaEngine.generateRingQueryPool(
                    count = if (isColdStart) COLD_START_QUERY_POOL_SIZE else QUERY_POOL_SIZE,
                    prefs = categoryPreferences,
                    sharedPreferences = sharedPreferences,
                ),
            preferredQuality = preferredQuality(),
            cachedAt = cachedAt,
            entropySeed = System.nanoTime() xor cachedAt,
            existingEntries = existingEntries,
            recentRefreshIds = recentRefreshIds().toSet(),
            isColdStart = isColdStart,
        )
    }

    private suspend fun searchRefreshCandidates(refreshPlan: RefreshPlan): List<SearchCandidate> {
        val persistedCount = cacheDao.countGoodEntries()
        // Searching progress goes straight into the state flow: the fragment
        // renders queriesCompleted/queriesTotal as "Searching… X/Y" instead
        // of a frozen spinner. Silent phases were reported as stuck
        // refreshes, so the total grows honestly as fallback pools append.
        _libraryState.value =
            YouTubeLibraryState.Searching(
                persistedCount = persistedCount,
                candidatesFound = 0,
            )
        Log.i(TAG, "Refresh searching ${refreshPlan.queryPool.size} queries")
        delay(300)
        // Cumulative search counter: fallback pools (long-tail, healthy,
        // supplemental) run only when yield is low, and each used to run
        // silent — the UI froze at "25 of 25" for minutes. The total grows
        // honestly as fallbacks are added, so the count never sits still
        // while queries run. Targets and pools are untouched.
        var searchDone = 0
        var searchTotal = refreshPlan.queryPool.size
        val mainSearchResults =
            searchCandidateVideos(
                queries = refreshPlan.queryPool,
                onProgress = { completed, _ ->
                    _libraryState.value =
                        YouTubeLibraryState.Searching(
                            persistedCount = persistedCount,
                            candidatesFound = 0,
                            queriesCompleted = (searchDone + completed).coerceAtMost(searchTotal),
                            queriesTotal = searchTotal,
                        )
                },
            )
        searchDone += refreshPlan.queryPool.size
        Log.i(TAG, "Refresh searched ${refreshPlan.queryPool.size} queries, ${uniqueCandidateCount(mainSearchResults)} unique candidates")
        if (refreshPlan.isColdStart) {
            Timber.tag(TAG).i(
                "Using fast cold-start YouTube candidate pool (%s queries, %s/%s unique candidates)",
                refreshPlan.queryPool.size,
                mainSearchResults.size,
                uniqueCandidateCount(mainSearchResults),
            )
            return mainSearchResults
        }
        val expandedResults =
            maybeExpandWithLongTail(mainSearchResults) { poolSize, completed ->
                if (completed == 0) {
                    searchTotal += poolSize
                }
                _libraryState.value =
                    YouTubeLibraryState.Searching(
                        persistedCount = persistedCount,
                        candidatesFound = 0,
                        queriesCompleted = (searchDone + completed).coerceAtMost(searchTotal),
                        queriesTotal = searchTotal,
                    )
            }
        searchDone = searchTotal
        val healthyResults =
            ensureHealthyCandidatePool(refreshPlan.query, expandedResults) { poolSize, completed ->
                if (completed == 0) {
                    searchTotal += poolSize
                }
                _libraryState.value =
                    YouTubeLibraryState.Searching(
                        persistedCount = persistedCount,
                        candidatesFound = 0,
                        queriesCompleted = (searchDone + completed).coerceAtMost(searchTotal),
                        queriesTotal = searchTotal,
                    )
            }
        searchDone = searchTotal
        val healthyUniqueCount = uniqueCandidateCount(healthyResults)
        val finalResults =
            if (healthyUniqueCount >= MIN_HEALTHY_CACHE_SIZE) {
                healthyResults
            } else {
                val supplementalQueries =
                    QueryFormulaEngine.generateFallbackQueryPool(
                        baseQuery = refreshPlan.query,
                        count = SUPPLEMENTAL_QUERY_POOL_SIZE,
                        entropySeed = refreshPlan.entropySeed xor healthyUniqueCount.toLong(),
                        prefs = categoryPreferences(),
                    )
                searchTotal += supplementalQueries.size
                val supplementalResults =
                    searchCandidateVideos(
                        queries = supplementalQueries,
                        onProgress = { completed, _ ->
                            _libraryState.value =
                                YouTubeLibraryState.Searching(
                                    persistedCount = persistedCount,
                                    candidatesFound = 0,
                                    queriesCompleted = (searchDone + completed).coerceAtMost(searchTotal),
                                    queriesTotal = searchTotal,
                                )
                        },
                    )
                searchDone = searchTotal
                val supplementedResults = mergeCandidatePools(healthyResults, supplementalResults)
                Timber.tag(TAG).i(
                    "Supplemented YouTube candidate pool with %s queries (%s -> %s unique candidates)",
                    supplementalQueries.size,
                    healthyUniqueCount,
                    uniqueCandidateCount(supplementedResults),
                )
                supplementedResults
            }

        Timber.tag(TAG).i(
            "Prepared YouTube candidate pool (queries=%s, main=%s/%s unique, final=%s/%s unique)",
            refreshPlan.queryPool.size,
            mainSearchResults.size,
            uniqueCandidateCount(mainSearchResults),
            finalResults.size,
            uniqueCandidateCount(finalResults),
        )
        return finalResults
    }

    /**
     * Single structured line per successful refresh so batch novelty is
     * measurable on-device: overlap with the previous batch (Tier 1) and the
     * ~3 before it, how many candidates each tier filtered, which relaxation
     * pass admitted the final IDs, and per-category quota fulfillment.
     */
    private fun logNoveltyReport(
        batchId: Long,
        preTiers: NoveltyTiers,
        novelty: NoveltyState,
        finalEntries: List<YouTubeCacheEntity>,
    ) {
        val finalIds = finalEntries.map { it.videoId }.toSet()
        val overlapN1 = finalIds.intersect(preTiers.tier1).size
        val overlapN3 = finalIds.intersect(preTiers.tier1 + preTiers.tier2).size
        val pass3 = overlapN1
        val pass2 = (overlapN3 - overlapN1).coerceAtLeast(0)
        val pass1 = (finalIds.size - overlapN3).coerceAtLeast(0)
        val targets = categoryManager.allocateCategoryTargets(enabledCategoryKeys(), TARGET_CACHE_SIZE)
        val counts = categoryManager.computeCategoryCounts(finalEntries, targets.keys)
        val quotas = targets.map { (key, target) -> "$key=${counts[key] ?: 0}/$target" }.joinToString(",")
        Log.i(
            TAG,
            "[RefreshNoveltyReport] batchId=$batchId total=${finalEntries.size} " +
                "overlapN1=$overlapN1 overlapN3=$overlapN3 " +
                "tier1Excluded=${novelty.tier1Excluded} tier2Demoted=${novelty.tier2Demoted} " +
                "relaxationPasses={pass1=$pass1,pass2=$pass2,pass3=$pass3} quotas={$quotas}",
        )
    }

    private suspend fun extractRefreshEntries(
        refreshPlan: RefreshPlan,
        searchResults: List<SearchCandidate>,
        replaceExistingCache: Boolean,
        initialCount: Int,
        novelty: NoveltyState,
    ): List<YouTubeCacheEntity> {
        val filteredCandidates =
            filterCategoryMismatchedCandidates(
                filterRecentlyPlayedCandidates(searchResults),
            )
        // Tier-1 hard exclusion: last batch's IDs never compete again
        // immediately. Emergency valve: if exclusion empties the pool
        // (starvation), admit everything rather than fail the refresh.
        val novelCandidates =
            if (novelty.tier1.isEmpty()) {
                filteredCandidates
            } else {
                val novel = filteredCandidates.filterNot { candidateVideoId(it) in novelty.tier1 }
                novelty.tier1Excluded += filteredCandidates.size - novel.size
                if (novel.isEmpty() && filteredCandidates.isNotEmpty()) {
                    Log.i(TAG, "Novelty emergency valve: Tier-1 exclusion emptied the pool, admitting all")
                    filteredCandidates
                } else {
                    novel
                }
            }
        val rankedCandidates =
            rankCandidatesWithStyleBalance(novelCandidates, novelty)
                .let(::deduplicateCandidatesByTitle)
                .let(::deduplicateCandidatesByVideoId)
                .let { applyCandidateDiversityCaps(it, EXTRACTION_TARGET_SIZE) }

        // Hybrid JIT: only the head gets eager extraction (instant-start
        // buffer + offline resilience). The tail is stored as metadata-only
        // (streamUrl="") and resolved at playback time. Eagerly extracting
        // all 200 burns ~800 player calls — the bot magnet — for URLs that
        // mostly expire (5.5h TTL) before ever playing.
        val eagerCandidates = rankedCandidates.take(EAGER_EXTRACTION_BUDGET)
        val metadataCandidates =
            rankedCandidates.drop(EAGER_EXTRACTION_BUDGET).take(EXTRACTION_TARGET_SIZE - EAGER_EXTRACTION_BUDGET)

        val eagerEntries =
            extractEntries(
                items = eagerCandidates,
                cachedAt = refreshPlan.cachedAt,
                preferredQuality = refreshPlan.preferredQuality,
                limit = EAGER_EXTRACTION_BUDGET,
            )
        if (eagerEntries.isEmpty() && eagerCandidates.isNotEmpty() && YouTubeThrottling.isBlocked()) {
            // The gate dropped mid-refresh after searches already ran:
            // report it distinctly instead of a silent stale fallback.
            throw YouTubeBotBlockedException(YouTubeThrottling.remainingCooldownMinutes())
        }
        val metadataEntries =
            buildMetadataEntries(
                candidates = metadataCandidates,
                cachedAt = refreshPlan.cachedAt,
            )
        val extractedEntries = (eagerEntries + metadataEntries).take(EXTRACTION_TARGET_SIZE)

        Timber.tag(TAG).i(
            "Extracted YouTube refresh entries (search=%s, filtered=%s, ranked=%s, eager=%s, metadata=%s)",
            searchResults.size,
            filteredCandidates.size,
            rankedCandidates.size,
            eagerEntries.size,
            metadataEntries.size,
        )
        return extractedEntries
    }

    /**
     * Metadata-only entries: no network, no player calls. Playback resolves
     * the stream just-in-time via resolveEntryPlayback.
     */
    private fun buildMetadataEntries(
        candidates: List<SearchCandidate>,
        cachedAt: Long,
    ): List<YouTubeCacheEntity> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val seenIds = mutableSetOf<String>()
        return candidates.mapNotNull { candidate ->
            val item = candidate.item
            val videoPageUrl = item.getUrl().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val videoId = extractVideoId(videoPageUrl) ?: return@mapNotNull null
            if (!seenIds.add(videoId)) {
                return@mapNotNull null
            }
            val title = item.getName().takeIf { it.isNotBlank() } ?: videoPageUrl
            val uploaderName = item.getUploaderName().orEmpty()
            YouTubeCacheEntity(
                videoId = videoId,
                videoPageUrl = videoPageUrl,
                streamUrl = "",
                audioStreamUrl = "",
                title = title,
                uploaderName = uploaderName,
                durationSeconds = item.getDuration().toInt(),
                categoryKey = resolveCandidateCategoryKey(candidate, title, uploaderName),
                streamUrlExpiresAt = 0L,
                searchCachedAt = cachedAt,
                searchQuery = candidate.searchQuery,
            )
        }
    }

    private fun mergeRefreshedEntries(
        refreshPlan: RefreshPlan,
        extractedEntries: List<YouTubeCacheEntity>,
        replaceExistingCache: Boolean,
    ): List<YouTubeCacheEntity> {
        val dedupedExtractedEntries = deduplicateEntriesByVideoId(extractedEntries)
        val entries =
            if (replaceExistingCache) {
                applyEntryDiversityCaps(dedupedExtractedEntries, TARGET_CACHE_SIZE)
            } else {
                replenishEntriesFromExistingCache(
                    extractedEntries = dedupedExtractedEntries,
                    existingEntries = refreshPlan.existingEntries,
                    entropySeed = refreshPlan.entropySeed,
                    recentRefreshIds = refreshPlan.recentRefreshIds,
                ).let { applyEntryDiversityCaps(it, TARGET_CACHE_SIZE) }
            }

        if (entries.isEmpty()) {
            val fallbackEntries = categoryManager.filteredExistingEntries(refreshPlan.existingEntries)
            if (fallbackEntries.isNotEmpty()) {
                Timber.tag(TAG).w("Reusing filtered cached YouTube entries because refresh produced no results")
                return fallbackEntries
            }
            throw YouTubeSourceException("No videos available")
        }

        val quotaBalancedEntries =
            categoryManager.rebalanceEntriesToCategoryTargets(
                entries = entries,
                enabledCategoryKeys = enabledCategoryKeys(),
                totalSlots = TARGET_CACHE_SIZE,
            )
        val finalEntries = quotaBalancedEntries.ifEmpty { entries }

        val existingPlaybackHistory =
            refreshPlan.existingEntries.associate { existingEntry ->
                existingEntry.videoId to existingEntry.lastPlayedAt
            }

        return finalEntries.map { entry ->
            existingPlaybackHistory[entry.videoId]
                ?.takeIf { playedAt -> playedAt > 0L }
                ?.let { playedAt -> entry.copy(lastPlayedAt = playedAt) }
                ?: entry
        }
    }

    private suspend fun persistFreshEntries(
        refreshPlan: RefreshPlan,
        entries: List<YouTubeCacheEntity>,
    ) {
        // Stable labels: the same video surfaces under many category queries
        // ("4K aerial" matches everything), so each refresh would otherwise
        // relabel it to whichever query found it this time. After a few
        // refreshes a category's rows scatter across labels and toggle-off
        // removal finds only a handful (e.g. 3 of ~25). First-seen label wins:
        // reuse the stored categoryKey (unfiltered — including currently
        // disabled categories) and only label genuinely new videos.
        val stableCategoryKeys =
            cacheDao.getAllGood()
                .associate { it.videoId to it.categoryKey }
        val uniqueEntries =
            deduplicateEntriesByVideoId(entries).map { entry ->
                val stableKey = stableCategoryKeys[entry.videoId]
                if (!stableKey.isNullOrBlank() && stableKey != entry.categoryKey) {
                    entry.copy(categoryKey = stableKey)
                } else {
                    entry
                }
            }
        cacheDao.clearAndInsert(uniqueEntries)
        badCountThisSession = 0
        recordRefreshHistory(uniqueEntries)
        val persistedCount = cacheDao.countGoodEntries()
        markCategoryStateFresh(persistedCount)
        // The ONLY progress number that may claim persisted videos.
        _libraryState.value = YouTubeLibraryState.Populating(persistedCount = persistedCount)
        Log.i(
            TAG,
            "Cached YouTube videos for query \"${refreshPlan.query}\" across ${refreshPlan.queryPool.size} searches " +
                "(requested=${entries.size}, unique=${uniqueEntries.size}, persisted=$persistedCount, " +
                "categories=${uniqueEntries.groupingBy { it.categoryKey.ifBlank { "unknown" } }.eachCount()})",
        )
    }

    private suspend fun topUpCacheToTargetAfterRefresh(
        persistedEntries: List<YouTubeCacheEntity>,
        novelty: NoveltyState,
    ): List<YouTubeCacheEntity> {
        var entriesSnapshot = deduplicateEntriesByVideoId(persistedEntries)
        var remainingToInsert = (TARGET_CACHE_SIZE - entriesSnapshot.size).coerceAtLeast(0)
        if (remainingToInsert <= 0) {
            return entriesSnapshot
        }

        val enabledCategories = enabledCategoryKeys()
        if (enabledCategories.isEmpty()) {
            return entriesSnapshot
        }

        val targets = categoryManager.allocateCategoryTargets(enabledCategories, TARGET_CACHE_SIZE)
        var preferredOrder =
            categoryManager.computeDeficitPriorityList(
                targets = targets,
                counts = categoryManager.computeCategoryCounts(entriesSnapshot, targets.keys),
            ).ifEmpty { targets.keys.toList() }
        var rounds = 0
        var insertedTotal = 0

        while (remainingToInsert > 0 && rounds < FULL_REFRESH_TOPUP_ROUNDS) {
            val counts = categoryManager.computeCategoryCounts(entriesSnapshot, targets.keys)
            val categoriesToFill =
                categoryManager.computeDeficitPriorityList(
                    targets = targets,
                    counts = counts,
                    preferredOrder = preferredOrder,
                ).ifEmpty { targets.keys.toList() }

            var insertedThisRound = 0
            categoriesToFill.forEach { category ->
                if (remainingToInsert <= 0) {
                    return@forEach
                }

                val categoryDeficit =
                    ((targets[category] ?: 0) - (counts[category] ?: 0)).coerceAtLeast(0)
                val extractionLimitForCategory =
                    when {
                        categoryDeficit > 0 ->
                            minOf(categoryDeficit, remainingToInsert, FULL_REFRESH_TOPUP_BATCH_PER_CATEGORY)
                        else ->
                            minOf(remainingToInsert, FULL_REFRESH_TOPUP_BATCH_PER_CATEGORY)
                    }
                if (extractionLimitForCategory <= 0) {
                    return@forEach
                }

                val insertedForCategory =
                    addEntriesForCategories(
                        categoryKeys = listOf(category),
                        existingEntries = entriesSnapshot,
                        initialCount = cacheDao.countGoodEntries(),
                        extractionLimit = extractionLimitForCategory,
                        metadataOnly = true,
                        novelty = novelty,
                    )
                if (insertedForCategory > 0) {
                    insertedTotal += insertedForCategory
                    insertedThisRound += insertedForCategory
                    remainingToInsert -= insertedForCategory
                    entriesSnapshot = cacheDao.getAllGood()
                }
            }

            if (insertedThisRound <= 0) {
                break
            }
            preferredOrder = categoriesToFill
            rounds += 1
        }

        val finalEntries = cacheDao.getAllGood()
        if (insertedTotal > 0) {
            recordRefreshHistory(finalEntries)
            markCategoryStateFresh(finalEntries.size)
            // DB-committed count only — never the in-memory insert tally.
            _libraryState.value = YouTubeLibraryState.Populating(persistedCount = cacheDao.countGoodEntries())
        }
        Timber.tag(TAG).i(
            "Full refresh top-up complete (initial=%s, inserted=%s, final=%s)",
            persistedEntries.size,
            insertedTotal,
            finalEntries.size,
        )
        return finalEntries
    }

    private suspend fun searchCandidateVideos(
        queries: List<String>,
        onProgress: (suspend (completed: Int, total: Int) -> Unit)? = null,
    ): List<SearchCandidate> {
        val variantBuckets = linkedMapOf<String, ArrayDeque<SearchCandidate>>()
        var completed = 0

        for (variantChunk in queries.chunked(QUERY_SEARCH_BATCH_SIZE)) {
            searchVariantChunk(variantChunk).forEach { (variant, results) ->
                addVariantResults(variantBuckets, variant, results)
            }
            completed += variantChunk.size
            onProgress?.invoke(completed.coerceAtMost(queries.size), queries.size)
        }

        return interleaveVariantResults(variantBuckets).take(TARGET_CANDIDATE_POOL_SIZE)
    }

    private suspend fun searchVariantChunk(
        variants: List<String>,
    ): List<Pair<String, List<StreamInfoItem>>> =
        supervisorScope {
            variants.map { variant ->
                async {
                    val category = QueryFormulaEngine.categoryForQuery(variant)
                    val results =
                        withTimeoutOrNull(SEARCH_CALL_TIMEOUT_MS) {
                            runCatching {
                                searcher.searchVideos(
                                    query = variant,
                                    category = category,
                                )
                            }.getOrElse { exception ->
                                Timber.tag(TAG).w(exception, "YouTube search failed for variant \"%s\"", variant)
                                emptyList()
                            }
                        }

                    variant to (results ?: emptyList())
                }
            }.awaitAll()
        }

    private fun addVariantResults(
        variantBuckets: LinkedHashMap<String, ArrayDeque<SearchCandidate>>,
        variant: String,
        results: List<StreamInfoItem>,
    ) {
        if (results.isEmpty()) {
            Timber.tag(TAG).w("YouTube search returned no usable results for variant \"%s\"", variant)
            return
        }

        val shuffledCandidates =
            results
                .shuffled()
                .map { item ->
                    SearchCandidate(
                        item = item,
                        searchQuery = variant,
                        category = QueryFormulaEngine.categoryForQuery(variant),
                    )
                }
        variantBuckets[variant] = ArrayDeque(shuffledCandidates)
    }

    private suspend fun maybeExpandWithLongTail(
        mainSearchResults: List<SearchCandidate>,
        onSearchProgress: (suspend (poolSize: Int, completed: Int) -> Unit)? = null,
    ): List<SearchCandidate> {
        val uniqueMainResults = uniqueCandidateCount(mainSearchResults)
        if (uniqueMainResults >= MIN_MAIN_SEARCH_UNIQUE_VIDEOS) {
            return mainSearchResults
        }

        val longTailQueries =
            QueryFormulaEngine.generateFallbackQueryPool(
                baseQuery = "",
                count = LONG_TAIL_QUERY_COUNT,
                entropySeed = System.nanoTime() xor uniqueMainResults.toLong(),
                prefs = categoryPreferences(),
            )
        onSearchProgress?.invoke(longTailQueries.size, 0)
        val longTailResults =
            searchCandidateVideos(longTailQueries) { completed, _ ->
                onSearchProgress?.invoke(longTailQueries.size, completed)
            }
        val mergedResults = mergeCandidatePools(mainSearchResults, longTailResults)
        Timber.tag(TAG).i(
            "Expanded YouTube candidate pool with %s category fallback queries (%s -> %s unique candidates)",
            longTailQueries.size,
            uniqueMainResults,
            uniqueCandidateCount(mergedResults),
        )
        return mergedResults
    }

    private suspend fun filterRecentlyPlayedCandidates(candidates: List<SearchCandidate>): List<SearchCandidate> {
        val recentPlayedIds = playHistory().toSet()
        if (recentPlayedIds.isEmpty() || candidates.size < MIN_HEALTHY_CANDIDATE_POOL_SIZE) {
            return candidates
        }

        return candidates.filter { candidate ->
            val candidateId = extractVideoId(candidate.item.getUrl())
            candidateId == null || candidateId !in recentPlayedIds
        }
    }

    private fun filterCategoryMismatchedCandidates(candidates: List<SearchCandidate>): List<SearchCandidate> {
        // Keep category matching as a ranking signal only; do not hard-drop candidates by title keywords.
        return candidates
    }

    private suspend fun ensureHealthyCandidatePool(
        query: String,
        candidates: List<SearchCandidate>,
        onSearchProgress: (suspend (poolSize: Int, completed: Int) -> Unit)? = null,
    ): List<SearchCandidate> {
        if (candidates.size >= MIN_HEALTHY_CANDIDATE_POOL_SIZE) {
            return candidates
        }

        val fallbackQueries =
            QueryFormulaEngine.generateFallbackQueryPool(
                baseQuery = "",
                count = FALLBACK_QUERY_POOL_SIZE,
                entropySeed = System.nanoTime(),
                prefs = categoryPreferences(),
            )
        onSearchProgress?.invoke(fallbackQueries.size, 0)
        val fallbackCandidates =
            searchCandidateVideos(fallbackQueries) { completed, _ ->
                onSearchProgress?.invoke(fallbackQueries.size, completed)
            }
        if (fallbackCandidates.isEmpty()) {
            return candidates
        }

        val mergedCandidates = linkedMapOf<String, SearchCandidate>()
        (candidates + fallbackCandidates).forEach { candidate ->
            val url = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return@forEach
            val key = extractVideoId(url) ?: url
            mergedCandidates.putIfAbsent(key, candidate)
        }

        Timber.tag(TAG).i(
            "Expanded YouTube candidate pool from %s to %s using %s fallback queries",
            candidates.size,
            mergedCandidates.size,
            fallbackQueries.size,
        )

        return mergedCandidates.values.take(TARGET_CANDIDATE_POOL_SIZE)
    }

    private fun mergeCandidatePools(
        primary: List<SearchCandidate>,
        secondary: List<SearchCandidate>,
    ): List<SearchCandidate> {
        val merged = linkedMapOf<String, SearchCandidate>()
        (primary + secondary).forEach { candidate ->
            val candidateUrl = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return@forEach
            val candidateKey = extractVideoId(candidateUrl) ?: candidateUrl
            merged.putIfAbsent(candidateKey, candidate)
        }
        return merged.values.take(TARGET_CANDIDATE_POOL_SIZE)
    }

    private fun uniqueCandidateCount(candidates: List<SearchCandidate>): Int =
        candidates
            .asSequence()
            .map { candidate ->
                val candidateUrl = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return@map null
                extractVideoId(candidateUrl) ?: candidateUrl
            }.filterNotNull()
            .distinct()
            .count()

    private fun replenishEntriesFromExistingCache(
        extractedEntries: List<YouTubeCacheEntity>,
        existingEntries: List<YouTubeCacheEntity>,
        entropySeed: Long,
        recentRefreshIds: Set<String>,
    ): List<YouTubeCacheEntity> {
        if (existingEntries.isEmpty()) {
            return prioritizeNovelEntries(extractedEntries, recentRefreshIds, entropySeed).take(TARGET_CACHE_SIZE)
        }

        val mergedEntries = linkedMapOf<String, YouTubeCacheEntity>()
        prioritizeNovelEntries(extractedEntries, recentRefreshIds, entropySeed).forEach { entry ->
            mergedEntries.putIfAbsent(entry.videoId, entry)
        }

        appendReusableEntries(
            mergedEntries = mergedEntries,
            existingEntries = existingEntries,
            entropySeed = entropySeed,
            recentRefreshIds = recentRefreshIds,
            reuseLimit = reuseLimitFor(extractedEntries.size),
        )

        val mergedList = mergedEntries.values.take(TARGET_CACHE_SIZE)
        if (mergedList.size > extractedEntries.size) {
            Timber.tag(TAG).i(
                "Reused %s prior YouTube entries to prevent a small repetitive cache (new=%s, final=%s)",
                mergedList.size - extractedEntries.size,
                extractedEntries.size,
                mergedList.size,
            )
        }
        return mergedList
    }

    private fun reuseLimitFor(extractedEntryCount: Int): Int =
        when {
            extractedEntryCount >= MIN_HEALTHY_CACHE_SIZE -> TARGET_CACHE_SIZE
            extractedEntryCount == 0 -> TARGET_CACHE_SIZE
            else -> MIN_HEALTHY_CACHE_SIZE
        }

    private fun appendReusableEntries(
        mergedEntries: LinkedHashMap<String, YouTubeCacheEntity>,
        existingEntries: List<YouTubeCacheEntity>,
        entropySeed: Long,
        recentRefreshIds: Set<String>,
        reuseLimit: Int,
    ) {
        prioritizeNovelEntries(existingEntries, recentRefreshIds, entropySeed)
            .forEach { existingEntry ->
                if (mergedEntries.size >= reuseLimit) {
                    return
                }
                mergedEntries.putIfAbsent(existingEntry.videoId, existingEntry)
            }
    }

    private fun prioritizeNovelEntries(
        entries: List<YouTubeCacheEntity>,
        recentRefreshIds: Set<String>,
        entropySeed: Long,
    ): List<YouTubeCacheEntity> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val random = Random(entropySeed)
        val unseen = entries.filterNot { it.videoId in recentRefreshIds }.shuffled(random)
        val repeated = entries.filter { it.videoId in recentRefreshIds }.shuffled(random)
        return unseen + repeated
    }

    private fun interleaveVariantResults(variantBuckets: LinkedHashMap<String, ArrayDeque<SearchCandidate>>): List<SearchCandidate> {
        val selectedItems = mutableListOf<SearchCandidate>()
        val seenVideoKeys = linkedSetOf<String>()

        while (selectedItems.size < TARGET_CANDIDATE_POOL_SIZE && variantBuckets.isNotEmpty()) {
            val exhaustedVariants = mutableListOf<String>()

            variantBuckets.forEach { (variant, bucket) ->
                var nextItem: SearchCandidate? = null
                while (bucket.isNotEmpty() && nextItem == null) {
                    val candidate = bucket.removeFirst()
                    val candidateUrl = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: continue
                    val candidateKey = extractVideoId(candidateUrl) ?: candidateUrl
                    if (seenVideoKeys.add(candidateKey)) {
                        nextItem = candidate
                    }
                }

                if (nextItem != null) {
                    selectedItems += nextItem
                }

                if (bucket.isEmpty()) {
                    exhaustedVariants += variant
                }
            }

            exhaustedVariants.forEach(variantBuckets::remove)
        }

        return selectedItems
    }

    private suspend fun extractEntries(
        items: List<SearchCandidate>,
        cachedAt: Long,
        preferredQuality: String,
        limit: Int,
    ): List<YouTubeCacheEntity> =
        supervisorScope {
            val entries = mutableListOf<YouTubeCacheEntity>()

            for ((chunkIndex, chunk) in items.chunked(EXTRACTION_BATCH_SIZE).withIndex()) {
                if (YouTubeThrottling.isBlocked()) {
                    Timber.tag(TAG).w(
                        "Skipping remaining refresh extractions, bot cooldown has %sms left",
                        YouTubeThrottling.remainingCooldownMs(),
                    )
                    break
                }
                // Pacing: bursts of anonymous player requests are what trips
                // the IP bot gate. One gentle pause between chunks keeps us
                // under it; playback resolves stay unthrottled.
                if (chunkIndex > 0) {
                    delay(EXTRACTION_CHUNK_PAUSE_MS)
                }
                val extractedChunk =
                    chunk
                        .map { candidate ->
                            async {
                                withTimeoutOrNull(EXTRACTION_CALL_TIMEOUT_MS) {
                                    buildCacheEntry(
                                        candidate = candidate,
                                        cachedAt = cachedAt,
                                        preferredQuality = preferredQuality,
                                    )
                                } ?: run {
                                    Timber.tag(TAG).w("Timed out extracting YouTube stream for %s", candidate.item.getUrl())
                                    null
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()

                if (extractedChunk.isNotEmpty()) {
                    val toInsert = if (entries.size + extractedChunk.size > limit) {
                        extractedChunk.take(limit - entries.size)
                    } else {
                        extractedChunk
                    }
                    if (toInsert.isNotEmpty()) {
                        entries += toInsert
                    }
                }

                if (entries.size >= limit) {
                    break
                }
            }

            entries.take(limit)
        }

    private suspend fun refreshExpiringStreamUrls(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
        val entriesToRefresh =
            entries
                .filter { entry -> entry.streamUrl.isBlank() || isStreamUrlExpiringSoon(entry) }
                .take(MAX_STREAM_URL_REFRESHES_PER_WARM)

        if (entriesToRefresh.isEmpty()) {
            return entries
        }

        supervisorScope {
            entriesToRefresh
                .chunked(EXTRACTION_BATCH_SIZE)
                .forEach { chunk -> refreshStreamChunk(chunk) }
        }

        return cacheDao.getAllGood()
    }

    private suspend fun refreshStreamChunk(chunk: List<YouTubeCacheEntity>) =
        supervisorScope {
            chunk
                .map { entry ->
                    async {
                        val refreshed =
                            withTimeoutOrNull(EXTRACTION_CALL_TIMEOUT_MS) {
                                runCatching {
                                    refreshStreamUrl(entry)
                                }.onFailure { exception ->
                                    Timber.tag(TAG).w(exception, "Failed to warm YouTube stream URL for %s", entry.videoId)
                                }
                            }

                        if (refreshed == null) {
                            Timber.tag(TAG).w("Timed out warming YouTube stream URL for %s", entry.videoId)
                        }
                    }
                }.awaitAll()
        }

    private suspend fun refreshStreamUrl(entry: YouTubeCacheEntity) {
        if (YouTubeThrottling.isBlocked()) {
            return
        }
        val now = System.currentTimeMillis()
        val updatedPlayback =
            streamExtractor.extractPlaybackStreams(
                entry.videoPageUrl,
                preferredQuality(),
                preferVideoOnly = shouldPreferVideoOnly(),
            )
        cacheDao.updateStreamUrl(
            entry.videoId,
            updatedPlayback.videoUrl,
            updatedPlayback.audioUrl,
            now + STREAM_URL_TTL_MS,
        )
    }

    private suspend fun shouldRunBackgroundSearchWarm(cachedEntries: List<YouTubeCacheEntity>): Boolean =
        isSearchCacheExpired() ||
            isCacheVersionStale() ||
            isCacheSignatureStale() ||
            isCacheUndersized(cachedEntries)

    private fun hasExpiringStreams(cachedEntries: List<YouTubeCacheEntity>): Boolean =
        cachedEntries.any(::isStreamUrlExpiringSoon)

    private fun hasFreshStreamUrl(entry: YouTubeCacheEntity): Boolean =
        entry.streamUrl.isNotBlank() &&
            !isStreamUrlExpiringSoon(entry) &&
            !isBelowMinimumCachedQuality(entry.streamUrl)

    private fun isStreamUrlExpiringSoon(entry: YouTubeCacheEntity): Boolean =
        entry.streamUrlExpiresAt < System.currentTimeMillis() + STREAM_REEXTRACT_BUFFER_MS

    private fun scheduleBackgroundWarmCache(
        forceSearchRefresh: Boolean,
        bypassCooldown: Boolean = false,
        replaceExistingCacheOverride: Boolean? = null,
    ) {
        val now = System.currentTimeMillis()
        if (!bypassCooldown && now - lastBackgroundWarmAt.get() < BACKGROUND_REFRESH_COOLDOWN_MS) {
            Log.d(TAG, "Background warm skipped by cooldown")
            return
        }

        if (!backgroundWarmInFlight.compareAndSet(false, true)) {
            return
        }

        lastBackgroundWarmAt.set(now)
        repositoryScope.launch {
            try {
                warmCache(
                    forceSearchRefresh = forceSearchRefresh,
                    replaceExistingCacheOverride = replaceExistingCacheOverride,
                )
            } catch (exception: Exception) {
                Timber.tag(TAG).w(exception, "Background YouTube warm refresh failed")
            } finally {
                backgroundWarmInFlight.set(false)
            }
        }
    }

    private suspend fun selectNextCandidate(): YouTubeCacheEntity? {
        val cachedEntries = cacheDao.getAllGood()
        if (cachedEntries.isEmpty()) {
            return null
        }

        prunePlayHistory(cachedEntries)
        return selectEntryForPlayback(cachedEntries)
    }

    private suspend fun buildPlaylistEntries(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
        val goodEntries = entries.filterNot { it.isBad }
        if (goodEntries.isEmpty()) {
            return emptyList()
        }

        if (!shouldShuffle() && !isFirstLaunchActive()) {
            return goodEntries
        }

        // Apply device-entropy starting offset: each device begins traversal at a unique offset
        val deviceSeed = DeviceEntropyEngine.getDeviceSeed(context)
        val startOffset = (kotlin.math.abs(deviceSeed) % goodEntries.size).toInt()
        val rotatedEntries = goodEntries.drop(startOffset) + goodEntries.take(startOffset)

        val playbackOrder = mutableListOf<YouTubeCacheEntity>()
        val remainingEntries = rotatedEntries.toMutableList()
        val simulation = createPlaylistSimulation()

        while (remainingEntries.isNotEmpty()) {
            val nextEntry = selectSimulatedEntry(remainingEntries, simulation)

            playbackOrder += nextEntry
            remainingEntries.removeAll { it.videoId == nextEntry.videoId }
            simulation.record(nextEntry, PlaylistOrderer.detectTheme(nextEntry.title))
        }

        return playbackOrder
    }

    // Simulation stays DB-free: pure pick plus an in-list random fallback.
    private fun selectSimulatedEntry(
        entries: List<YouTubeCacheEntity>,
        simulation: PlaylistOrderer.PlaylistSimulation,
    ): YouTubeCacheEntity =
        PlaylistOrderer.pickCandidate(
            entries = entries,
            playbackHistory = simulation.history.toList(),
            recentThemes = simulation.themeHistory.toList(),
            lastChannel = simulation.lastChannel,
            firstLaunchActive = simulation.firstLaunchActive,
            firstLaunchSequenceIndex = simulation.firstLaunchIndex,
            recentPlaybackCutoff = recentPlaybackCutoff(),
            random = simulation.random,
            recentCategories = simulation.categoryHistory.toList(),
            hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        ) ?: entries.random(simulation.random)

    private suspend fun selectEntryForPlayback(entries: List<YouTubeCacheEntity>): YouTubeCacheEntity? {
        return selectEntryForPlayback(
            entries = entries,
            playbackHistory = playHistory().toList(),
            recentThemes = themeHistory().toList(),
            lastChannel = lastPlayedChannel(),
            firstLaunchActive = isFirstLaunchActive(),
            firstLaunchSequenceIndex = firstLaunchIndex(),
            random = Random(System.nanoTime()),
        )
    }

    private suspend fun selectEntryForPlayback(
        entries: List<YouTubeCacheEntity>,
        playbackHistory: List<String>,
        recentThemes: List<String>,
        lastChannel: String,
        firstLaunchActive: Boolean,
        firstLaunchSequenceIndex: Int,
        random: Random,
    ): YouTubeCacheEntity? =
        PlaylistOrderer.pickCandidate(
            entries = entries,
            playbackHistory = playbackHistory,
            recentThemes = recentThemes,
            lastChannel = lastChannel,
            firstLaunchActive = firstLaunchActive,
            firstLaunchSequenceIndex = firstLaunchSequenceIndex,
            recentPlaybackCutoff = recentPlaybackCutoff(),
            random = random,
            recentCategories = categoryHistory().toList(),
            hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        ) ?: cacheDao.getUnwatchedEntry(recentPlaybackCutoff())
            ?: cacheDao.getLeastRecentlyPlayed()

    private suspend fun prunePlayHistory(cachedEntries: List<YouTubeCacheEntity>) =
        historyTracker.prunePlayHistory(cachedEntries)

    private fun isCacheUndersized(entries: List<YouTubeCacheEntity>): Boolean =
        entries.size < TARGET_CACHE_SIZE

    private suspend fun playHistory(): ArrayDeque<String> = historyTracker.playHistory()

    private fun categoryHistory(): ArrayDeque<String> = historyTracker.categoryHistory()

    private fun recentRefreshIds(): ArrayDeque<String> = historyTracker.recentRefreshIds()

    private fun themeHistory(): ArrayDeque<String> = historyTracker.themeHistory()

    private fun recordRefreshHistory(entries: List<YouTubeCacheEntity>) =
        historyTracker.recordRefreshHistory(entries)

    private fun lastPlayedChannel(): String = historyTracker.lastPlayedChannel()

    private fun isFirstLaunchActive(): Boolean = historyTracker.isFirstLaunchActive()

    private fun firstLaunchIndex(): Int = historyTracker.firstLaunchIndex()

    private suspend fun recordPlayback(entry: YouTubeCacheEntity) =
        historyTracker.recordPlayback(entry)

    private suspend fun maybeWarmSearchCacheNearPlaylistEnd() {
        val cachedEntries = cacheDao.getAllGood()
        if (cachedEntries.isEmpty()) {
            return
        }

        val cacheIds = cachedEntries.mapTo(mutableSetOf()) { it.videoId }
        val playedInCache = playHistory().asSequence().filter { it in cacheIds }.toSet().size
        val remainingUnique = (cachedEntries.size - playedInCache).coerceAtLeast(0)
        when {
            remainingUnique <= EMERGENCY_REFILL_REMAINING_ITEMS -> {
                scheduleBackgroundWarmCache(
                    forceSearchRefresh = true,
                    bypassCooldown = true,
                    replaceExistingCacheOverride = false,
                )
            }
            remainingUnique <= FORCE_REFRESH_REMAINING_ITEMS -> {
                scheduleBackgroundWarmCache(
                    forceSearchRefresh = true,
                    bypassCooldown = true,
                    replaceExistingCacheOverride = false,
                )
            }
            remainingUnique <= BACKGROUND_PREWARM_REMAINING_ITEMS -> {
                scheduleBackgroundWarmCache(
                    forceSearchRefresh = true,
                    replaceExistingCacheOverride = false,
                )
            }
        }
    }

    private fun peekPreResolvedEntry(videoPageUrl: String): YouTubeCacheEntity? =
        synchronized(preResolvedLock) {
            preResolvedEntry?.takeIf { entry -> entry.videoPageUrl == videoPageUrl }
        }

    private fun consumePreResolvedEntry(videoPageUrl: String): YouTubeCacheEntity? =
        synchronized(preResolvedLock) {
            val entry = preResolvedEntry?.takeIf { cachedEntry -> cachedEntry.videoPageUrl == videoPageUrl }
            if (entry != null) {
                preResolvedEntry = null
            }
            entry
        }

    private fun consumeAnyPreResolvedEntry(): YouTubeCacheEntity? =
        synchronized(preResolvedLock) {
            val entry = preResolvedEntry
            preResolvedEntry = null
            entry
        }

    private fun cachePreResolvedEntry(entry: YouTubeCacheEntity) {
        synchronized(preResolvedLock) {
            preResolvedEntry = entry
        }
    }

    private fun clearPreResolvedEntry() {
        synchronized(preResolvedLock) {
            preResolvedEntry = null
        }
    }

    private suspend fun ensureStreamQualitySignatureFresh() {
        val currentSignature = currentStreamQualitySignature()
        val storedSignature = sharedPreferences.getString(KEY_STREAM_QUALITY_SIGNATURE, "").orEmpty()
        if (storedSignature == currentSignature) {
            return
        }

        preResolvingJob?.cancel()
        preResolvingTarget = null
        clearPreResolvedEntry()
        val invalidatedCount = cacheDao.invalidateAllStreamUrls()
        sharedPreferences.edit {
            putString(KEY_STREAM_QUALITY_SIGNATURE, currentSignature)
        }
        Timber.tag(TAG).i(
            "Invalidated %s cached YouTube stream URLs after quality target changed from \"%s\" to \"%s\"",
            invalidatedCount,
            storedSignature,
            currentSignature,
        )
    }

    private fun currentStreamQualitySignature(): String =
        buildString {
            append(playbackResolutionQuality().lowercase(Locale.US))
            append("|videoOnly=")
            append(playbackPreferVideoOnly())
            append("|selector=v")
            append(STREAM_SELECTION_STRATEGY_VERSION)
        }

    private fun cacheSignature(): String {
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        // DO NOT include categorySignature here; category changes should not invalidate the whole cache.
        return "$appVersion|v$CURRENT_CACHE_VERSION"
    }

    private fun isCacheSignatureStale(): Boolean {
        val currentSignature = cacheSignature()
        val storedSignature = sharedPreferences.getString(KEY_CACHE_SIGNATURE, "")
        val isStale = storedSignature != currentSignature
        if (isStale) {
            Timber.tag(TAG).d("YouTube cache signature stale: current=\"%s\", stored=\"%s\"", currentSignature, storedSignature)
        }
        return isStale
    }

    private suspend fun resolveEntryStreamUrl(entry: YouTubeCacheEntity): String =
        resolveEntryPlayback(entry).videoUrl

    private suspend fun resolveEntryStreamUrlOrNull(entry: YouTubeCacheEntity): String? =
        resolveEntryPlaybackOrNull(entry)?.videoUrl

    private suspend fun resolveEntryPlayback(entry: YouTubeCacheEntity): YouTubePlaybackUrls {
        return resolveEntryPlayback(entry, recordPlayback = true)
    }

    private suspend fun resolveEntryPlaybackOrNull(entry: YouTubeCacheEntity): YouTubePlaybackUrls? =
        runCatching { resolveEntryPlayback(entry) }
            .onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                markEntryAsBadIfPermanent(entry, exception)
            }.getOrNull()

    private suspend fun resolveEntryPlayback(
        entry: YouTubeCacheEntity,
        recordPlayback: Boolean,
    ): YouTubePlaybackUrls {
        // While the bot gate is up, never touch the network: every blocked
        // extraction resets YouTube's sliding-window penalty clock. Reuse any
        // usable cached URL; with nothing cached, fail fast as BotBlocked so
        // callers skip instead of burning a 25s timeout per candidate.
        if (YouTubeThrottling.isBlocked()) {
            if (isUsableStreamUrl(entry.streamUrl)) {
                Timber.tag(TAG).w("Bot cooldown active, reusing cached stream for %s", entry.videoId)
                val reused = entryPlaybackUrls(entry)
                if (recordPlayback) {
                    recordPlayback(entry)
                }
                return reused
            }
            throw YouTubeBotBlockedException(YouTubeThrottling.remainingCooldownMinutes())
        }
        val now = System.currentTimeMillis()
        val resolvedPlayback =
            if (!hasFreshStreamUrl(entry)) {
                try {
                    val updatedPlayback =
                        streamExtractor.extractPlaybackStreams(
                            entry.videoPageUrl,
                            playbackResolutionQuality(),
                            preferVideoOnly = playbackPreferVideoOnly(),
                        )
                    val newExpiresAt = now + STREAM_URL_TTL_MS
                    if (!isUsableStreamUrl(updatedPlayback.videoUrl)) {
                        markEntryAsBad(entry)
                        throw YouTubeSourceException("No videos available")
                    }
                    cacheDao.updateStreamUrl(
                        entry.videoId,
                        updatedPlayback.videoUrl,
                        updatedPlayback.audioUrl,
                        newExpiresAt,
                    )
                    badCountThisSession = 0
                    YouTubePlaybackUrls(
                        videoUrl = updatedPlayback.videoUrl,
                        audioUrl = updatedPlayback.audioUrl,
                    )
                } catch (exception: Exception) {
                    if (isUsableCachedStream(entry)) {
                        Timber.tag(TAG).w(exception, "Falling back to cached YouTube stream URL for %s", entry.videoId)
                        scheduleBackgroundWarmCache(forceSearchRefresh = false)
                        entryPlaybackUrls(entry)
                    } else {
                        markEntryAsBadIfPermanent(entry, exception)
                        throw YouTubeSourceException("No videos available", exception)
                    }
                }
            } else {
                if (!isUsableCachedStream(entry)) {
                    markEntryAsBad(entry)
                    throw YouTubeSourceException("No videos available")
                }
                entryPlaybackUrls(entry)
            }

        if (recordPlayback) {
            recordPlayback(entry)
            maybeWarmSearchCacheNearPlaylistEnd()
        }
        return resolvedPlayback
    }

    private suspend fun resolveProjectivyStreamUrl(
        videoPageUrl: String,
        preferredQuality: String = projectivyPlaybackResolutionQuality(),
        preferVideoOnly: Boolean = projectivyPreferVideoOnly(),
    ): String? =
        runCatching {
            streamExtractor.extractStreamUrl(
                videoPageUrl,
                preferredQuality,
                preferVideoOnly = preferVideoOnly,
                allowAdaptiveManifests = true,
                preferAdaptiveManifests = projectivyPreferAdaptiveManifests(preferredQuality),
                preferManifests = false,
            )
        }.getOrNull()?.takeIf(::isProjectivyUsableStreamUrl)

    /**
     * Burns a cache row only for permanent content failures (age/geo blocked,
     * video gone, rejected shape). Timeouts, network blips and cancellations
     * must never poison good rows — a tunnel drive-by used to burn entries.
     */
    private suspend fun markEntryAsBadIfPermanent(
        entry: YouTubeCacheEntity,
        exception: Throwable,
    ) {
        if (isPermanentFailure(exception)) {
            markEntryAsBad(entry, exception)
        } else {
            Timber.tag(TAG).w(exception, "Transient YouTube failure for %s, keeping cache entry", entry.videoId)
        }
    }

    private fun isPermanentFailure(exception: Throwable): Boolean =
        !exception.isNetworkError() && shouldSilentlySkip(exception)

    private suspend fun markEntryAsBad(
        entry: YouTubeCacheEntity,
        exception: Throwable? = null,
    ) {        val rowsMarkedBad = cacheDao.markAsBad(entry.videoId)
        if (rowsMarkedBad > 0) {
            val liveCount = cacheDao.countGoodEntries()
            sharedPreferences.edit { putString(KEY_COUNT, liveCount.toString()) }
        }
        badCountThisSession += 1
        if (exception != null) {
            Timber.tag(TAG).w(exception, "Marking broken YouTube cache entry as bad: %s", entry.videoId)
        } else {
            Timber.tag(TAG).w("Marking broken YouTube cache entry as bad: %s", entry.videoId)
        }
        if (badCountThisSession >= BAD_ENTRY_REFRESH_THRESHOLD) {
            Timber.tag(TAG).w("Too many broken YouTube entries, triggering background refresh")
            scheduleBackgroundWarmCache(forceSearchRefresh = true)
            badCountThisSession = 0
        }
    }

    private fun isUsableStreamUrl(url: String): Boolean =
        url.isNotBlank() && url.startsWith("http", ignoreCase = true)

    private fun isProjectivyUsableStreamUrl(url: String): Boolean {
        if (!isUsableStreamUrl(url)) {
            return false
        }

        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = parsed.host.orEmpty().lowercase(Locale.US)

        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            return false
        }

        return true
    }

    private fun hasFreshProjectivyStreamUrl(
        entry: YouTubeCacheEntity,
        preferredQuality: String,
    ): Boolean =
        entry.streamUrl.isNotBlank() &&
            !isStreamUrlExpiringSoon(entry) &&
            isProjectivyStableStreamUrl(entry.streamUrl, preferredQuality)

    private fun isProjectivyPreferredReusableStream(
        streamUrl: String,
        preferredQuality: String,
    ): Boolean =
        isProjectivyStableStreamUrl(streamUrl, preferredQuality) &&
            (!projectivyPreferAdaptiveManifests(preferredQuality) || isAdaptiveManifestStreamUrl(streamUrl))

    private fun isProjectivyStableStreamUrl(
        streamUrl: String,
        preferredQuality: String,
    ): Boolean {
        if (!isProjectivyUsableStreamUrl(streamUrl)) {
            return false
        }

        val parsed = runCatching { Uri.parse(streamUrl) }.getOrNull() ?: return false
        if (isAdaptiveManifestUrl(parsed)) {
            return projectivyPreferAdaptiveManifests(preferredQuality)
        }

        val mime = parsed.getQueryParameter("mime")?.lowercase(Locale.US).orEmpty()
        val itag = parsed.getQueryParameter("itag")?.toIntOrNull()
        val streamHeight = cachedStreamHeight(streamUrl)
        val minimumHeight = projectivyMinimumHeightForQuality(preferredQuality)

        if (streamHeight != null) {
            return streamHeight >= minimumHeight
        }
        if (mime.contains("video/webm")) {
            return !projectivyPreferAdaptiveManifests(preferredQuality)
        }
        if (mime.contains("video/mp4")) {
            return true
        }

        return itag in PROJECTIVY_STABLE_MP4_ITAGS || mime.isBlank()
    }

    private fun isAdaptiveManifestStreamUrl(streamUrl: String): Boolean =
        runCatching { Uri.parse(streamUrl) }.getOrNull()?.let(::isAdaptiveManifestUrl) == true

    private fun projectivyMinimumHeightForQuality(preferredQuality: String): Int =
        when (projectivyPlaybackResolutionQualityFor(preferredQuality).lowercase(Locale.US)) {
            "best",
            "2160p",
            "4k",
            -> 2160

            "1440p" -> 1440
            "1080p" -> 1080
            "720p" -> 720
            else -> 2160
        }

    private fun isUsableCachedStream(entry: YouTubeCacheEntity): Boolean {
        if (!isUsableStreamUrl(entry.streamUrl)) {
            return false
        }
        val streamHeight = cachedStreamHeight(entry.streamUrl)
        val minimumHeight = minimumAcceptableCachedStreamHeight()
        if (streamHeight != null && streamHeight in 1 until minimumHeight) {
            Log.i(
                TAG,
                "Rejecting cached low-quality stream for ${entry.videoId}: ${streamHeight}p (< ${minimumHeight}p). Forcing re-extraction.",
            )
            return false
        }
        return true
    }

    private fun isBelowMinimumCachedQuality(streamUrl: String): Boolean =
        cachedStreamHeight(streamUrl)?.let { height ->
            height in 1 until minimumAcceptableCachedStreamHeight()
        } == true

    private fun minimumAcceptableCachedStreamHeight(): Int =
        when (playbackResolutionQuality().trim().lowercase(Locale.US)) {
            "best",
            "2160p",
            "4k",
            -> 1440

            "1440p" -> 1080
            else -> MIN_ACCEPTABLE_CACHED_STREAM_HEIGHT
        }

    private fun cachedStreamHeight(streamUrl: String): Int? {
        val uri = runCatching { Uri.parse(streamUrl) }.getOrNull()
        parseHeightHint(uri?.getQueryParameter("quality_label"))?.let { return it }
        parseHeightHint(uri?.getQueryParameter("quality"))?.let { return it }
        parseHeightHint(streamUrl)?.let { return it }

        val itag = uri?.getQueryParameter("itag")?.toIntOrNull() ?: return null
        return STREAM_ITAG_HEIGHT_HINTS[itag]
    }

    private fun parseHeightHint(value: String?): Int? {
        val normalized = value?.lowercase(Locale.US)?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }

        HEIGHT_HINT_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return QUALITY_LABEL_HEIGHT_HINTS[normalized]
    }

    private fun buildResolvedEntry(
        entry: YouTubeCacheEntity,
        resolvedPlayback: YouTubePlaybackUrls,
        resolvedAt: Long,
    ): YouTubeCacheEntity =
        if (
            entry.streamUrl == resolvedPlayback.videoUrl &&
            entry.audioStreamUrl == resolvedPlayback.audioUrl &&
            entry.streamUrlExpiresAt >= resolvedAt + STREAM_REEXTRACT_BUFFER_MS
        ) {
            entry
        } else {
            entry.copy(
                streamUrl = resolvedPlayback.videoUrl,
                audioStreamUrl = resolvedPlayback.audioUrl,
                streamUrlExpiresAt = resolvedAt + STREAM_URL_TTL_MS,
            )
        }

    private suspend fun buildCacheEntry(
        candidate: SearchCandidate,
        cachedAt: Long,
        preferredQuality: String,
    ): YouTubeCacheEntity? {
        val item = candidate.item
        val title = item.getName().takeIf { it.isNotBlank() } ?: item.getUrl()

        return try {
            val videoPageUrl = item.getUrl().takeIf { it.isNotBlank() } ?: return null
            val videoId = extractVideoId(videoPageUrl) ?: return null
            val uploaderName = item.getUploaderName().orEmpty()
            val playbackStreams =
                streamExtractor.extractPlaybackStreams(
                    videoPageUrl,
                    preferredQuality,
                    preferVideoOnly = shouldPreferVideoOnly(),
                )

            YouTubeCacheEntity(
                videoId = videoId,
                videoPageUrl = videoPageUrl,
                streamUrl = playbackStreams.videoUrl,
                audioStreamUrl = playbackStreams.audioUrl,
                title = title,
                uploaderName = uploaderName,
                durationSeconds = item.getDuration().toInt(),
                categoryKey = resolveCandidateCategoryKey(candidate, title, uploaderName),
                streamUrlExpiresAt = cachedAt + STREAM_URL_TTL_MS,
                searchCachedAt = cachedAt,
                searchQuery = candidate.searchQuery,
            )
        } catch (exception: Exception) {
            if (shouldSilentlySkip(exception)) {
                Timber.tag(TAG).w("Skipping unavailable YouTube result: %s", title)
            } else {
                Timber.tag(TAG).w(exception, "Skipping YouTube result: %s", title)
            }
            null
        }
    }

    private suspend fun buildDirectCacheEntry(
        videoPageUrl: String,
        cachedAt: Long,
        preferredQuality: String,
        preferVideoOnly: Boolean = playbackPreferVideoOnly(),
        allowAdaptiveManifests: Boolean = true,
        preferAdaptiveManifests: Boolean = false,
        preferManifests: Boolean = true,
    ): YouTubeCacheEntity? {
        val videoId = extractVideoId(videoPageUrl) ?: return null
        val playbackStreams =
            streamExtractor.extractPlaybackStreams(
                videoPageUrl,
                playbackResolutionQuality(fallbackQuality = preferredQuality),
                preferVideoOnly = preferVideoOnly,
                allowAdaptiveManifests = allowAdaptiveManifests,
                preferAdaptiveManifests = preferAdaptiveManifests,
                preferManifests = preferManifests,
            )
        return YouTubeCacheEntity(
            videoId = videoId,
            videoPageUrl = videoPageUrl,
            streamUrl = playbackStreams.videoUrl,
            audioStreamUrl = playbackStreams.audioUrl,
            title = videoId,
            uploaderName = "",
            durationSeconds = 0,
            categoryKey = QueryFormulaEngine.categoryForQuery(searchQuery())?.key.orEmpty(),
            streamUrlExpiresAt = cachedAt + STREAM_URL_TTL_MS,
            searchCachedAt = cachedAt,
            searchQuery = searchQuery(),
        )
    }

    private fun shouldSilentlySkip(exception: Throwable): Boolean =
        when (exception) {
            is AgeRestrictedContentException,
            is GeographicRestrictionException,
            is ContentNotAvailableException,
            -> true

            is ExtractionException -> skipMessage(exception.message)
            is YouTubeExtractionException -> skipMessage(exception.message) || exception.cause?.let(::shouldSilentlySkip) == true
            else -> skipMessage(exception.message)
        }

    private fun skipMessage(message: String?): Boolean {
        val normalizedMessage = message?.lowercase().orEmpty()
        return normalizedMessage.contains("403") || normalizedMessage.contains("not available")
    }

    private fun extractVideoId(videoPageUrl: String): String? {
        QUERY_VIDEO_ID_REGEX
            .find(videoPageUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val trimmedPath =
            videoPageUrl
                .substringAfter("://", videoPageUrl)
                .substringAfter('/', "")
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')

        // Keep in sync with NewPipeHelper: a bare endpoint path is not a video ID.
        return trimmedPath.takeIf { it.isNotBlank() && it.lowercase() !in NON_VIDEO_ID_PATH_SEGMENTS }
    }

    private suspend fun isSearchCacheExpired(): Boolean {
        // Newest, not oldest: top-ups keep trickling in fresh rows while the
        // bulk ages, so MIN condemned the cache the moment a single row went
        // stale and forced a full refresh on nearly every access. Whole-cache
        // staleness is when even the freshest row is older than the TTL;
        // the daily worker still forces a full refresh on schedule.
        val newestCachedAt = cacheDao.getNewestCachedAt() ?: return true
        return System.currentTimeMillis() - newestCachedAt >= SEARCH_CACHE_TTL_MS
    }

    private fun isCacheVersionStale(): Boolean =
        sharedPreferences.getInt(KEY_CACHE_VERSION, 0) != CURRENT_CACHE_VERSION

    private fun initializeCategorySnapshotIfNeeded() =
        categoryManager.initializeCategorySnapshotIfNeeded()

    private fun readCategorySnapshot(): Set<String> =
        categoryManager.readCategorySnapshot()

    private fun persistCategorySnapshot(enabledCategoryKeys: Set<String>) =
        categoryManager.persistCategorySnapshot(enabledCategoryKeys)

    private suspend fun addEntriesForCategories(
        categoryKeys: List<String>,
        existingEntries: List<YouTubeCacheEntity>,
        initialCount: Int,
        extractionLimit: Int = CATEGORY_DELTA_EXTRACTION_LIMIT,
        // Full-refresh top-up passes true: beyond the eager budget, entries
        // are metadata-only and resolve just-in-time at playback. Without
        // this the top-up reintroduces the bulk-extraction burst the hybrid
        // budget exists to prevent.
        metadataOnly: Boolean = false,
        // Novelty snapshot: Tier-1 IDs are excluded from backfill candidates
        // so top-ups stop re-injecting yesterday's unplayed videos.
        novelty: NoveltyState? = null,
    ): Int {
        val normalizedCategories =
            categoryKeys.map(String::trim).filter(String::isNotBlank)
        if (normalizedCategories.isEmpty() || extractionLimit <= 0) {
            return 0
        }

        val uniqueCategories = normalizedCategories.distinct()
        val existingById = existingEntries.associateBy { entry -> entry.videoId }.toMutableMap()
        var insertedTotal = 0
        var remainingToInsert = extractionLimit

        repeat(CATEGORY_DELTA_FETCH_ATTEMPTS) { attempt ->
            if (remainingToInsert <= 0) {
                return@repeat
            }

            val cachedAt = System.currentTimeMillis()
            val entropySeed = (System.nanoTime() xor cachedAt) + attempt
            val demandDrivenQueryCount =
                ((remainingToInsert + CATEGORY_DELTA_QUERY_TO_VIDEO_RATIO - 1) / CATEGORY_DELTA_QUERY_TO_VIDEO_RATIO)
                    .coerceAtLeast(1)
            val queryCount =
                maxOf(
                    uniqueCategories.size * CATEGORY_DELTA_QUERY_COUNT_PER_CATEGORY,
                    demandDrivenQueryCount * uniqueCategories.size,
                ).coerceAtMost(MAX_CATEGORY_DELTA_QUERY_COUNT)
                    .coerceAtLeast(uniqueCategories.size)

            val queryPool =
                QueryFormulaEngine.generateQueryPool(
                    count = queryCount,
                    prefs = categoryPreferencesForKeys(uniqueCategories.toSet()),
                    entropySeed = entropySeed,
                )
            if (queryPool.isEmpty()) {
                return@repeat
            }

            val searchResults = searchCandidateVideos(queryPool)
            if (searchResults.isEmpty()) {
                return@repeat
            }

            val filteredCandidates =
                filterCategoryMismatchedCandidates(
                    filterRecentlyPlayedCandidates(searchResults),
                ).filter { candidate ->
                    candidate.category?.key in uniqueCategories
                }
            val rankedCandidates =
                rankCandidatesWithStyleBalance(filteredCandidates, novelty)
                    .let(::deduplicateCandidatesByTitle)
                    .let(::deduplicateCandidatesByVideoId)
                    .let { applyCandidateDiversityCaps(it, remainingToInsert) }
            val novelRanked =
                if (novelty == null || novelty.tier1.isEmpty()) {
                    rankedCandidates
                } else {
                    val novel = rankedCandidates.filterNot { candidateVideoId(it) in novelty.tier1 }
                    novelty.tier1Excluded += rankedCandidates.size - novel.size
                    if (novel.isEmpty() && rankedCandidates.isNotEmpty()) {
                        Log.i(TAG, "Novelty emergency valve in backfill: admitting all candidates")
                        rankedCandidates
                    } else {
                        novel
                    }
                }
            if (novelRanked.isEmpty()) {
                return@repeat
            }

            val extractedEntries =
                if (metadataOnly) {
                    buildMetadataEntries(
                        candidates = novelRanked.take(remainingToInsert),
                        cachedAt = cachedAt,
                    )
                } else {
                    extractEntries(
                        items = novelRanked,
                        cachedAt = cachedAt,
                        preferredQuality = preferredQuality(),
                        limit = remainingToInsert,
                    )
                }
            if (extractedEntries.isEmpty()) {
                return@repeat
            }

            val entriesToInsert =
                deduplicateEntriesByVideoId(extractedEntries.map { extracted ->
                    existingById[extracted.videoId]
                        ?.takeIf { existing -> existing.lastPlayedAt > 0L }
                        ?.let { existing -> extracted.copy(lastPlayedAt = existing.lastPlayedAt) }
                        ?: extracted
                })
            if (entriesToInsert.isEmpty()) {
                return@repeat
            }

            val beforeInsertCount = cacheDao.countGoodEntries()
            // IGNORE keeps the first-seen categoryKey stable: when the same
            // video is rediscovered under another category, REPLACE would
            // silently relabel it and toggle-off removal could no longer find
            // it (only a handful of rows removed instead of ~25). Upgrade
            // streams on existing rows without touching their category.
            val insertResults = cacheDao.insertIgnore(entriesToInsert)
            entriesToInsert.forEachIndexed { index, entry ->
                if (insertResults.getOrNull(index) == -1L && entry.streamUrl.isNotBlank()) {
                    cacheDao.updateStreamUrl(
                        entry.videoId,
                        entry.streamUrl,
                        entry.audioStreamUrl,
                        entry.streamUrlExpiresAt,
                    )
                }
            }
            val afterInsertCount = cacheDao.countGoodEntries()

            val insertedThisAttempt =
                (afterInsertCount - beforeInsertCount).coerceAtLeast(0)
            entriesToInsert.forEach { entry ->
                existingById[entry.videoId] = entry
            }

            if (insertedThisAttempt > 0) {
                insertedTotal += insertedThisAttempt
                remainingToInsert = (extractionLimit - insertedTotal).coerceAtLeast(0)
                // Report once per committed attempt, never per in-memory extraction.
                _libraryState.value =
                    YouTubeLibraryState.Populating(persistedCount = afterInsertCount)
            }
        }

        return insertedTotal
    }

    private data class CategoryBalancePlan(
        val targets: Map<String, Int>,
        val deficitCategories: List<String>,
    )

    private data class RebalanceOutcome(
        val evictedVideoIds: List<String>,
        val deficitCategories: List<String>,
    )

    private fun resolveCandidateCategoryKey(
        candidate: SearchCandidate,
        title: String,
        uploaderName: String,
    ): String {
        candidate.category?.key?.takeIf { it.isNotBlank() }?.let { return it }
        QueryFormulaEngine.categoryForQuery(candidate.searchQuery)?.key?.let { return it }
        return categoryManager.inferCategoryKeyFromMetadata(
            title = title,
            uploader = uploaderName,
            allowedKeys = YouTubeCategoryManager.ALL_CATEGORY_KEYS,
        ).orEmpty()
    }

    private fun categoryPreferencesForKeys(categoryKeys: Set<String>): QueryFormulaEngine.CategoryPreferences =
        QueryFormulaEngine.CategoryPreferences(
            categoryNature = QueryFormulaEngine.ContentCategory.NATURE.key in categoryKeys,
            categoryAnimals = QueryFormulaEngine.ContentCategory.ANIMALS.key in categoryKeys,
            categoryDrone = QueryFormulaEngine.ContentCategory.DRONE.key in categoryKeys,
            categoryCities = QueryFormulaEngine.ContentCategory.CITIES.key in categoryKeys,
            categorySpace = QueryFormulaEngine.ContentCategory.SPACE.key in categoryKeys,
            categoryOcean = QueryFormulaEngine.ContentCategory.OCEAN.key in categoryKeys,
            categoryWeather = QueryFormulaEngine.ContentCategory.WEATHER.key in categoryKeys,
            categoryWinter = QueryFormulaEngine.ContentCategory.WINTER.key in categoryKeys,
        )

    private fun categoryPreferences(): QueryFormulaEngine.CategoryPreferences =
        categoryManager.categoryPreferences()

    private fun categorySignature(): String =
        categoryManager.categorySignature()

    private fun enabledCategoryKeys(): List<String> =
        categoryManager.enabledCategoryKeys()

    private suspend fun currentFilteredCount(): Int =
        categoryManager.filteredExistingEntries(cacheDao.getAllGood()).size

    private suspend fun applyCurrentCategoryFilterInternal(): Int =
        categoryManager.applyCurrentCategoryFilterInternal()

    private fun searchQuery(): String =
        YouTubeSettings.read(sharedPreferences).query

    private fun preferredQuality(): String =
        YouTubeSettings.read(sharedPreferences).quality

    private fun shouldShuffle(): Boolean =
        YouTubeSettings.read(sharedPreferences).shuffle

    private fun shouldPreferVideoOnly(): Boolean =
        true

    private fun playbackResolutionQuality(fallbackQuality: String = preferredQuality()): String =
        fallbackQuality

    private fun playbackPreferVideoOnly(): Boolean =
        shouldPreferVideoOnly()

    private fun projectivyPlaybackResolutionQuality(): String =
        projectivyPlaybackResolutionQualityFor(playbackResolutionQuality())

    private fun projectivyPreferVideoOnly(): Boolean =
        false

    private fun projectivyPreferAdaptiveManifests(preferredQuality: String): Boolean =
        projectivyMinimumHeightForQuality(preferredQuality) >= 2160

    private fun streamMode(): String =
        "video_only_preferred"

    private fun updateCachedCount(count: Int) {
        sharedPreferences.edit {
            putString(KEY_COUNT, count.toString())
        }
    }

    private fun markCategoryStateFresh(count: Int) {
        val appSignature = cacheSignature()
        val categorySig = categorySignature()
        sharedPreferences.edit {
            putString(KEY_COUNT, count.toString())
            putString(KEY_CACHE_SIGNATURE, appSignature)
            putString(KEY_STREAM_QUALITY_SIGNATURE, currentStreamQualitySignature())
            putStringSet(YouTubeCategoryManager.KEY_CATEGORY_SNAPSHOT, enabledCategoryKeys().toSet())
            putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION)
            putLong(KEY_LAST_SEARCH_AT, System.currentTimeMillis())
        }
        Timber.tag(TAG).d("Marked YouTube cache fresh: count=%s, signature=\"%s\" categories=\"%s\"", count, appSignature, categorySig)
    }

    private fun markSearchCacheFresh(count: Int) {
        markCategoryStateFresh(count)
    }

    private fun applyCandidateDiversityCaps(
        candidates: List<SearchCandidate>,
        limit: Int,
    ): List<SearchCandidate> =
        applyDiversityCaps(
            items = candidates,
            limit = limit,
            idSelector = { candidate ->
                extractVideoId(candidate.item.getUrl()) ?: candidate.item.getUrl()
            },
            channelSelector = { candidate ->
                candidate.item.getUploaderName().orEmpty()
            },
            querySelector = { candidate ->
                candidate.searchQuery
            },
            titleSelector = { candidate ->
                candidate.item.getName()
            },
        )

    private fun applyEntryDiversityCaps(
        entries: List<YouTubeCacheEntity>,
        limit: Int,
    ): List<YouTubeCacheEntity> =
        applyDiversityCaps(
            items = entries,
            limit = limit,
            idSelector = { entry -> entry.videoId },
            channelSelector = { entry -> entry.uploaderName },
            querySelector = { entry -> entry.searchQuery.orEmpty() },
            titleSelector = { entry -> entry.title },
        )

    private fun <T> applyDiversityCaps(
        items: List<T>,
        limit: Int,
        idSelector: (T) -> String,
        channelSelector: (T) -> String,
        querySelector: (T) -> String,
        titleSelector: (T) -> String,
    ): List<T> {
        if (items.isEmpty()) {
            return emptyList()
        }

        val filteredItems =
            filterItemsByChannelAndQueryCaps(
                items = items,
                idSelector = idSelector,
                channelSelector = channelSelector,
                querySelector = querySelector,
            )
        val themeBuckets = bucketItemsByTheme(filteredItems, titleSelector)
        return selectItemsWithThemeCaps(themeBuckets, limit)
    }

    private fun rankCandidatesWithStyleBalance(
        candidates: List<SearchCandidate>,
        novelty: NoveltyState? = null,
    ): List<SearchCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val scoredCandidates = scoreCandidates(candidates, novelty)
        val balancedSelection = selectBalancedCandidates(scoredCandidates)
        val rankedCandidates = if (balancedSelection.isEmpty()) scoredCandidates else balancedSelection

        return rankedCandidates
            .sortedByDescending { (_, score) -> score }
            .map { (candidate, _) -> candidate }
    }

    private fun deduplicateCandidatesByTitle(candidates: List<SearchCandidate>): List<SearchCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val dedupedByTitle = linkedMapOf<String, SearchCandidate>()
        candidates.forEach { candidate ->
            val fallbackKey = extractVideoId(candidate.item.getUrl()) ?: candidate.item.getUrl()
            val normalizedTitle = normalizeTitleFingerprint(candidate.item.getName()).ifBlank { fallbackKey }
            dedupedByTitle.putIfAbsent(normalizedTitle, candidate)
        }

        return dedupedByTitle.values.toList()
    }

    private fun deduplicateCandidatesByVideoId(candidates: List<SearchCandidate>): List<SearchCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val dedupedByVideoId = linkedMapOf<String, SearchCandidate>()
        candidates.forEach { candidate ->
            val candidateUrl = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return@forEach
            val key = extractVideoId(candidateUrl) ?: candidateUrl
            dedupedByVideoId.putIfAbsent(key, candidate)
        }
        return dedupedByVideoId.values.toList()
    }

    private fun deduplicateEntriesByVideoId(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val deduped = linkedMapOf<String, YouTubeCacheEntity>()
        entries.forEach { entry ->
            deduped.putIfAbsent(entry.videoId, entry)
        }
        return deduped.values.toList()
    }

    private fun normalizeTitleFingerprint(title: String): String =
        title
            .lowercase()
            .replace("\\b(4k|8k|hdr|uhd|ambient|no music|no talking|screensaver|hours?|hour|mins?|minutes?)\\b".toRegex(), " ")
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()

    private fun scoreVideo(
        candidate: SearchCandidate,
        novelty: NoveltyState? = null,
    ): Int {
        val item = candidate.item
        val title = item.getName().lowercase()
        val uploaderName = item.getUploaderName().orEmpty()
        val qualitySignalScore =
            QueryFormulaEngine.qualitySignals.count { signal ->
                title.contains(signal.lowercase())
            }
        val durationScore =
            when {
                item.getDuration() > YouTubeCategoryManager.LONG_FORM_DURATION_SECONDS -> YouTubeCategoryManager.LONG_FORM_BONUS + YouTubeCategoryManager.VERY_LONG_FORM_BONUS
                item.getDuration() > YouTubeCategoryManager.MEDIUM_FORM_DURATION_SECONDS -> YouTubeCategoryManager.LONG_FORM_BONUS
                else -> 0
            }
        val categoryScore =
            QueryFormulaEngine.categoryMatchScore(
                title = item.getName(),
                uploader = uploaderName,
                category = candidate.category,
            ) +
                when (queryCategory(candidate)) {
                    QueryFormulaEngine.QueryCategory.AERIAL -> YouTubeCategoryManager.AERIAL_CATEGORY_BONUS
                    QueryFormulaEngine.QueryCategory.NATURE -> 0
                }
        val penaltyScore =
            (if (categoryManager.isVlogLikeTitle(title)) YouTubeCategoryManager.VLOG_TITLE_PENALTY else 0) +
                (if (categoryManager.isDigitHeavyChannelName(uploaderName)) YouTubeCategoryManager.DIGIT_HEAVY_CHANNEL_PENALTY else 0)
        // Tier-2 soft demotion: batches N-2/N-3 yield to fresh candidates but
        // stay eligible as fallback. Winners score ~8-14, so -8 drops old
        // winners below viable fresh candidates without burying them as junk.
        val noveltyPenalty =
            if (novelty != null && candidateVideoId(candidate) in novelty.tier2) {
                novelty.tier2Demoted += 1
                NOVELTY_TIER2_PENALTY
            } else {
                0
            }

        return qualitySignalScore + durationScore + categoryScore - penaltyScore - noveltyPenalty
    }

    private fun candidateVideoId(candidate: SearchCandidate): String {
        val url = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return ""
        return extractVideoId(url) ?: url
    }

    private fun queryCategory(candidate: SearchCandidate): QueryFormulaEngine.QueryCategory =
        candidate.category?.queryCategory ?: QueryFormulaEngine.categoryOf(candidate.searchQuery)

    private fun <T> filterItemsByChannelAndQueryCaps(
        items: List<T>,
        idSelector: (T) -> String,
        channelSelector: (T) -> String,
        querySelector: (T) -> String,
    ): List<T> {
        val filteredItems = mutableListOf<T>()
        val channelCounts = mutableMapOf<String, Int>()
        val queryCounts = mutableMapOf<String, Int>()

        items.forEach { item ->
            val channelKey = channelSelector(item).trim().ifBlank { idSelector(item) }
            val queryKey = querySelector(item).trim().ifBlank { DEFAULT_CATEGORY_KEY }
            if ((channelCounts[channelKey] ?: 0) >= MAX_VIDEOS_PER_CHANNEL) {
                return@forEach
            }
            if ((queryCounts[queryKey] ?: 0) >= MAX_VIDEOS_PER_QUERY_BUCKET) {
                return@forEach
            }

            filteredItems += item
            channelCounts[channelKey] = (channelCounts[channelKey] ?: 0) + 1
            queryCounts[queryKey] = (queryCounts[queryKey] ?: 0) + 1
        }

        return filteredItems
    }

    private fun <T> bucketItemsByTheme(
        items: List<T>,
        titleSelector: (T) -> String,
    ): LinkedHashMap<String, ArrayDeque<T>> {
        val themeBuckets = linkedMapOf<String, ArrayDeque<T>>()
        items.forEach { item ->
            val theme = PlaylistOrderer.detectTheme(titleSelector(item))
            themeBuckets.getOrPut(theme) { ArrayDeque() }.addLast(item)
        }
        return themeBuckets
    }

    private fun <T> selectItemsWithThemeCaps(
        themeBuckets: LinkedHashMap<String, ArrayDeque<T>>,
        limit: Int,
    ): List<T> {
        val selectedItems = mutableListOf<T>()
        val perThemeSelections = mutableMapOf<String, Int>()

        while (selectedItems.size < limit) {
            var addedAny = false
            themeBuckets.forEach { (theme, bucket) ->
                if (selectedItems.size >= limit || bucket.isEmpty()) {
                    return@forEach
                }
                if ((perThemeSelections[theme] ?: 0) >= INITIAL_THEME_ROUND_ROBIN_CAP) {
                    return@forEach
                }

                selectedItems += bucket.removeFirst()
                perThemeSelections[theme] = (perThemeSelections[theme] ?: 0) + 1
                addedAny = true
            }

            if (!addedAny) {
                break
            }
        }

        if (selectedItems.size < limit) {
            themeBuckets.values.forEach { bucket ->
                while (selectedItems.size < limit && bucket.isNotEmpty()) {
                    selectedItems += bucket.removeFirst()
                }
            }
        }

        return selectedItems.take(limit)
    }

    private fun scoreCandidates(
        candidates: List<SearchCandidate>,
        novelty: NoveltyState? = null,
    ): List<Pair<SearchCandidate, Int>> =
        candidates
            .map { candidate -> candidate to scoreVideo(candidate, novelty) }
            .sortedByDescending { (_, score) -> score }

    private fun selectBalancedCandidates(
        candidates: List<Pair<SearchCandidate, Int>>,
    ): List<Pair<SearchCandidate, Int>> {
        val categoryBuckets =
            linkedMapOf<QueryFormulaEngine.ContentCategory?, ArrayDeque<Pair<SearchCandidate, Int>>>().apply {
                candidates.forEach { candidate ->
                    getOrPut(candidate.first.category) { ArrayDeque() }.addLast(candidate)
                }
            }
        val selected = mutableListOf<Pair<SearchCandidate, Int>>()

        while (selected.size < EXTRACTION_TARGET_SIZE && categoryBuckets.isNotEmpty()) {
            val exhausted = mutableListOf<QueryFormulaEngine.ContentCategory?>()
            categoryBuckets.forEach { (category, bucket) ->
                if (bucket.isNotEmpty()) {
                    selected += bucket.removeFirst()
                }
                if (bucket.isEmpty()) {
                    exhausted += category
                }
                if (selected.size >= EXTRACTION_TARGET_SIZE) {
                    return@forEach
                }
            }
            exhausted.forEach(categoryBuckets::remove)
        }

        return selected.take(EXTRACTION_TARGET_SIZE)
    }

    private fun recentPlaybackCutoff(): Long =
        System.currentTimeMillis() - YouTubeHistoryTracker.RECENT_PLAYBACK_WINDOW_MS

    private suspend fun createPlaylistSimulation(): PlaylistOrderer.PlaylistSimulation {
        val deviceSeed = DeviceEntropyEngine.getDeviceSeed(context)
        return PlaylistOrderer.PlaylistSimulation(
            history = playHistory(),
            themeHistory = themeHistory(),
            lastChannel = lastPlayedChannel(),
            firstLaunchActive = isFirstLaunchActive(),
            firstLaunchIndex = firstLaunchIndex(),
            random = Random(deviceSeed xor System.nanoTime()),
        )
    }

    private data class RefreshPlan(
        val query: String,
        val queryPool: List<String>,
        val preferredQuality: String,
        val cachedAt: Long,
        val entropySeed: Long,
        val existingEntries: List<YouTubeCacheEntity>,
        val recentRefreshIds: Set<String>,
        val isColdStart: Boolean,
    )

    private data class SearchCandidate(
        val item: StreamInfoItem,
        val searchQuery: String,
        val category: QueryFormulaEngine.ContentCategory?,
    )

    /**
     * Per-refresh novelty state: Tier-1 IDs (last batch) are hard-excluded
     * from candidates, Tier-2 IDs (the ~2 batches before) take a score
     * penalty but stay eligible. Counters feed the novelty report log.
     */
    private class NoveltyState(
        val tier1: Set<String>,
        val tier2: Set<String>,
        var tier1Excluded: Int = 0,
        var tier2Demoted: Int = 0,
    ) {
        fun isEmpty(): Boolean = tier1.isEmpty() && tier2.isEmpty()
    }

    companion object {
        private const val TAG = "YouTubeSource"
        const val KEY_QUERY = "yt_query"
        const val KEY_QUALITY = "yt_quality"
        const val KEY_MIX_WEIGHT = "yt_mix_weight"
        const val KEY_SHUFFLE = "yt_shuffle"
        const val KEY_ENABLED = "yt_enabled"
        const val KEY_COUNT = "yt_count"
        const val KEY_CACHE_VERSION = "yt_cache_version"
        const val KEY_CACHE_SIGNATURE = "yt_cache_signature"
        const val KEY_STREAM_QUALITY_SIGNATURE = "yt_stream_quality_signature"
        const val KEY_LAST_SEARCH_AT = "yt_last_search_at"
        const val KEY_CATEGORY_NATURE = "yt_category_nature"
        const val KEY_CATEGORY_ANIMALS = "yt_category_animals"
        const val KEY_CATEGORY_DRONE = "yt_category_drone"
        const val KEY_CATEGORY_CITIES = "yt_category_cities"
        const val KEY_CATEGORY_SPACE = "yt_category_space"
        const val KEY_CATEGORY_OCEAN = "yt_category_ocean"
        const val KEY_CATEGORY_WEATHER = "yt_category_weather"
        const val KEY_CATEGORY_WINTER = "yt_category_winter"
        private const val KEY_MUTE_VIDEOS = "mute_videos"

        const val DEFAULT_QUERY = "4K aerial nature ambient"
        const val DEFAULT_QUALITY = "best"
        const val DEFAULT_MIX_WEIGHT = "1"
        const val DEFAULT_SHUFFLE = true
        private const val DEFAULT_MUTE_VIDEOS = true
        const val DEFAULT_CATEGORY_NATURE = true
        const val DEFAULT_CATEGORY_ANIMALS = true
        const val DEFAULT_CATEGORY_DRONE = true
        const val DEFAULT_CATEGORY_CITIES = true
        const val DEFAULT_CATEGORY_SPACE = true
        const val DEFAULT_CATEGORY_OCEAN = true
        const val DEFAULT_CATEGORY_WEATHER = true
        const val DEFAULT_CATEGORY_WINTER = true

        private const val TARGET_CACHE_SIZE = 200
        private const val EXTRACTION_TARGET_SIZE = 200
        // Eager extraction budget per full refresh: instant-start buffer +
        // offline resilience for ~2-4h of viewing. Everything beyond this is
        // metadata-only and resolves just-in-time at playback. 12 videos x up
        // to ~3 player calls each stays far below bot tripwires.
        private const val EAGER_EXTRACTION_BUDGET = 12
        private const val MIN_HEALTHY_CACHE_SIZE = 200
        private const val TARGET_CANDIDATE_POOL_SIZE = 600
        private const val EXTRACTION_BATCH_SIZE = 4
        private const val EXTRACTION_CHUNK_PAUSE_MS = 1_000L
        private const val CATEGORY_DELTA_QUERY_COUNT_PER_CATEGORY = 12
        private const val CATEGORY_DELTA_QUERY_TO_VIDEO_RATIO = 4
        private const val MAX_CATEGORY_DELTA_QUERY_COUNT = 120
        private const val CATEGORY_DELTA_FETCH_ATTEMPTS = 3
        private const val CATEGORY_DELTA_BACKFILL_ROUNDS = 3
        private const val CATEGORY_DELTA_FALLBACK_BATCH_PER_CATEGORY = 6
        private const val CATEGORY_DELTA_EXTRACTION_LIMIT = 300
        private const val FULL_REFRESH_TOPUP_ROUNDS = 5
        private const val FULL_REFRESH_TOPUP_BATCH_PER_CATEGORY = 12
        private const val MAX_STREAM_URL_REFRESHES_PER_WARM = 24
        // Bounded search concurrency (do not fan out one worker per category).
        private const val QUERY_SEARCH_BATCH_SIZE = 4
        private const val COLD_START_QUERY_POOL_SIZE = 10
        private const val QUERY_POOL_SIZE = 25
        private const val FALLBACK_QUERY_POOL_SIZE = 12
        private const val SUPPLEMENTAL_QUERY_POOL_SIZE = 16
        private const val MIN_HEALTHY_CANDIDATE_POOL_SIZE = 250
        private const val BACKGROUND_PREWARM_REMAINING_ITEMS = 60
        private const val FORCE_REFRESH_REMAINING_ITEMS = 50
        private const val EMERGENCY_REFILL_REMAINING_ITEMS = 25
        private const val MINIMUM_VIABLE_CACHE_SIZE = 10
        private const val COLD_CACHE_SKIP_THRESHOLD = 5
        private const val BACKGROUND_REFRESH_COOLDOWN_MS = 10L * 60L * 1000L
        /**
         * Dedicated threads for the toggle delete path. Blocking NewPipe
         * network calls can saturate Dispatchers.IO for tens of seconds
         * (observed on TV: a millisecond DB read starved ~40s while a stream
         * refresh worker ran extractions); the counter must never queue
         * behind that pool. Daemon threads, shared across instances.
         */
        private val dbDispatcher by lazy {
            java.util.concurrent.Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "yt-toggle-db").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
        private const val SEARCH_CALL_TIMEOUT_MS = 20_000L
        private const val EXTRACTION_CALL_TIMEOUT_MS = 25_000L
        private const val SEARCH_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        // YouTube stream URLs typically expire around the 6 hour mark.
        private const val STREAM_URL_TTL_MS = 5L * 60L * 60L * 1000L + 30L * 60L * 1000L
        private const val STREAM_REEXTRACT_BUFFER_MS = 30L * 60L * 1000L
        private const val MAX_PLAYBACK_RESOLVE_ATTEMPTS = 5
        private const val BAD_ENTRY_REFRESH_THRESHOLD = 10
        // Tier-2 novelty demotion: batches N-2/N-3 yield to fresh candidates
        // but stay eligible. Winners score ~8-14, so -8 drops old winners
        // below viable fresh candidates without burying them as junk.
        private const val NOVELTY_TIER2_PENALTY = 8
        private const val CURRENT_CACHE_VERSION = 29
        internal const val STREAM_SELECTION_STRATEGY_VERSION = 4
        private const val MIN_ACCEPTABLE_CACHED_STREAM_HEIGHT = 720
        private const val PROJECTIVY_DEFAULT_QUALITY = "2160p"
        private const val DEFAULT_CATEGORY_KEY = "__uncategorized__"
        private const val MIN_MAIN_SEARCH_UNIQUE_VIDEOS = 180
        private const val MAX_VIDEOS_PER_CHANNEL = 7
        private const val MAX_VIDEOS_PER_QUERY_BUCKET = 10
        private const val INITIAL_THEME_ROUND_ROBIN_CAP = 40
        private val QUERY_VIDEO_ID_REGEX = Regex("[?&]v=([^&#]+)")
        private val NON_VIDEO_ID_PATH_SEGMENTS =
            setOf(
                "watch",
                "results",
                "playlist",
                "channel",
                "feed",
                "hashtag",
                "shorts",
                "live",
            )
        private val HEIGHT_HINT_REGEX = Regex("(\\d{3,4})p")
        private val QUALITY_LABEL_HEIGHT_HINTS =
            mapOf(
                "hd2160" to 2160,
                "hd1440" to 1440,
                "hd1080" to 1080,
                "hd720" to 720,
                "large" to 480,
                "medium" to 360,
                "small" to 240,
                "tiny" to 144,
            )
        private val STREAM_ITAG_HEIGHT_HINTS =
            mapOf(
                5 to 240,
                6 to 270,
                13 to 144,
                17 to 144,
                18 to 360,
                22 to 720,
                34 to 360,
                36 to 240,
                37 to 1080,
                43 to 360,
                82 to 360,
                83 to 480,
                92 to 240,
                93 to 360,
                94 to 480,
                100 to 360,
                101 to 480,
                132 to 240,
                133 to 240,
                134 to 360,
                135 to 480,
                136 to 720,
                137 to 1080,
                160 to 144,
                242 to 240,
                243 to 360,
                244 to 480,
                247 to 720,
                248 to 1080,
                264 to 1440,
                266 to 2160,
                271 to 1440,
                272 to 2160,
                278 to 144,
                298 to 720,
                299 to 1080,
                313 to 2160,
                315 to 2160,
                394 to 144,
                395 to 240,
                396 to 360,
            )
        private val PROJECTIVY_STABLE_MP4_ITAGS =
            setOf(
                18,
                22,
                37,
                135,
                136,
                137,
                160,
                298,
                299,
            )

        internal fun projectivyPlaybackResolutionQualityFor(quality: String): String {
            val normalized = quality.trim().lowercase(Locale.US)
            return when {
                normalized.isBlank() -> PROJECTIVY_DEFAULT_QUALITY
                normalized == "best" -> "2160p"
                normalized.contains("4k") -> "2160p"
                else -> quality.trim()
            }
        }

        private fun isAdaptiveManifestUrl(parsed: Uri): Boolean {
            val host = parsed.host.orEmpty().lowercase(Locale.US)
            val path = parsed.encodedPath.orEmpty().lowercase(Locale.US)
            val mime = parsed.getQueryParameter("mime")?.lowercase(Locale.US).orEmpty()
            return host.contains("manifest.googlevideo.com") ||
                path.endsWith(".mpd") ||
                path.endsWith(".m3u8") ||
                path.contains("/manifest/") ||
                mime.contains("application/dash+xml") ||
                mime.contains("application/vnd.apple.mpegurl") ||
                mime.contains("application/x-mpegurl")
        }

        private const val LONG_TAIL_QUERY_COUNT = 16

    }
}
