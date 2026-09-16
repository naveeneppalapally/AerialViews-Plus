package com.neilturner.aerialviews.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.databinding.MainActivityBinding
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.prefs.UpdatePrefs
import com.neilturner.aerialviews.ui.helpers.PreferenceHelper
import com.neilturner.aerialviews.ui.screensaver.TestActivity
import com.neilturner.aerialviews.ui.settings.ImportExportFragment
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.ui.helpers.ToastHelper
import com.neilturner.aerialviews.utils.HomeUpdatePromptHelper
import com.neilturner.aerialviews.utils.UpdateCheckerHelper
import com.neilturner.aerialviews.utils.UpdateInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private lateinit var binding: MainActivityBinding
    private lateinit var resultLauncher: ActivityResultLauncher<Intent>
    private var fromScreensaver = false
    private var updateDownloadId: Long = -1L
    private var startupUpdatePromptHandled = false
    private var isDownloadReceiverRegistered = false
    private var bannerDismissJob: Job? = null
    private var downloadProgressJob: Job? = null

    private val downloadReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != updateDownloadId) return

                val didLaunchInstaller = UpdateCheckerHelper.installDownloadedApk(this@MainActivity, updateDownloadId)
                if (!didLaunchInstaller) {
                    lifecycleScope.launch {
                        ToastHelper.show(this@MainActivity, R.string.home_update_download_failed)
                    }
                }
                updateDownloadId = -1L
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(binding.container.id, MainFragment())
            }
        } else {
            title = savedInstanceState.getCharSequence("TITLE_TAG")
        }

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                setTitle(R.string.app_name)
            }
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        resultLauncher =
            registerForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val exitApp = result.data?.getBooleanExtra("exit_app", false)
                    Timber.i("Exit app now? $exitApp")
                    if (exitApp == true) {
                        fromScreensaver = false
                        finishAndRemoveTask()
                    } else {
                        fromScreensaver = true
                    }
                }
            }
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("Main", this)
        registerDownloadReceiver()
        lifecycleScope.launch {
            val shouldShowStartupPrompt = handleCustomLaunching()
            if (shouldShowStartupPrompt) {
                maybeShowStartupUpdatePrompt()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterDownloadReceiver()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence("TITLE_TAG", title)
    }

    private fun handleCustomLaunching(): Boolean {
        val fromAppRestart = intent.getBooleanExtra("from_app_restart", false)
        val hasIntentUri = intent.data != null
        val hasValidIntentAndData = hasIntentUri && intent.action == Intent.ACTION_VIEW
        val shouldExitApp =
            GeneralPrefs.startScreensaverOnLaunch &&
                PreferenceHelper.isExitToSettingSet() &&
                !hasIntentUri &&
                !fromAppRestart &&
                !fromScreensaver

        Timber.i(
            "isExitToSettingSet: ${PreferenceHelper.isExitToSettingSet()}, fromScreensaver:$fromScreensaver fromAppRestart:$fromAppRestart, hasIntentUri:$hasIntentUri, startScreensaverOnLaunch:${GeneralPrefs.startScreensaverOnLaunch}",
        )

        if (shouldExitApp) {
            startScreensaver()
            fromScreensaver = false
            return false
        } else if (hasValidIntentAndData) {
            val bundle =
                Bundle().apply {
                    putParcelable("dataUri", intent.data)
                }
            supportFragmentManager.commit {
                replace(
                    binding.container.id,
                    ImportExportFragment().apply {
                        arguments = bundle
                    },
                ).addToBackStack(null)
            }
            fromScreensaver = false
            return false
        }
        fromScreensaver = false
        return true
    }

    fun startAppUpdateDownload(updateInfo: UpdateInfo) {
        runCatching {
            updateDownloadId = UpdateCheckerHelper.enqueueDownload(this, updateInfo)
        }.onSuccess {
            lifecycleScope.launch {
                ToastHelper.show(
                    this@MainActivity,
                    getString(R.string.home_update_download_started, updateInfo.tagName.removePrefix("v")),
                )
            }
        }.onFailure { exception ->
            Timber.e(exception, "UpdateChecker: failed to enqueue home-screen update download")
            lifecycleScope.launch {
                ToastHelper.show(this@MainActivity, R.string.home_update_download_failed)
            }
        }
    }

    /**
     * Option B ambient banner: non-modal, bottom-right, auto-dismisses after
     * [UPDATE_BANNER_TIMEOUT_MS] without writing the dismissed tag (an
     * untouched banner re-appears next launch). Details opens the C4 dialog.
     */
    fun showUpdateBanner(updateInfo: UpdateInfo) {
        val slot = binding.updateBannerSlot
        slot.removeAllViews()
        val banner =
            layoutInflater.inflate(
                com.neilturner.aerialviews.R.layout.update_banner,
                slot,
                false,
            )
        banner.findViewById<android.widget.TextView>(
            com.neilturner.aerialviews.R.id.update_banner_title,
        ).text =
            getString(
                R.string.home_update_banner_title,
                updateInfo.tagName.removePrefix("v"),
            )
        banner.findViewById<android.widget.TextView>(
            com.neilturner.aerialviews.R.id.update_banner_subtitle,
        ).text = getString(R.string.home_update_banner_subtitle)
        banner.findViewById<android.widget.Button>(
            com.neilturner.aerialviews.R.id.update_banner_details,
        ).apply {
            text = getString(R.string.home_update_details)
            setOnClickListener {
                dismissUpdateBanner()
                openUpdateDetails(updateInfo)
            }
        }
        banner.findViewById<android.widget.Button>(
            com.neilturner.aerialviews.R.id.update_banner_dismiss,
        ).apply {
            text = getString(R.string.home_update_dismiss)
            setOnClickListener {
                UpdatePrefs.homeUpdatePromptDismissedTag = updateInfo.tagName
                dismissUpdateBanner()
            }
        }
        slot.addView(banner)
        slot.visibility = android.view.View.VISIBLE
        // Deliberately no requestFocus(): the banner must never steal
        // d-pad focus from whatever the user is doing.
        bannerDismissJob?.cancel()
        bannerDismissJob =
            lifecycleScope.launch {
                delay(UPDATE_BANNER_TIMEOUT_MS)
                dismissUpdateBanner()
            }
    }

    fun openUpdateDetails(updateInfo: UpdateInfo) {
        HomeUpdatePromptHelper.show(
            context = this,
            currentVersion = com.neilturner.aerialviews.BuildConfig.VERSION_NAME,
            updateInfo = updateInfo,
            onDownload = { handle ->
                UpdatePrefs.homeUpdatePromptDismissedTag = ""
                val downloadId =
                    runCatching {
                        UpdateCheckerHelper.enqueueDownload(this, updateInfo)
                    }.getOrElse { exception ->
                        Timber.e(exception, "UpdateChecker: failed to enqueue home-screen update download")
                        handle.showFailed()
                        lifecycleScope.launch {
                            ToastHelper.show(this@MainActivity, R.string.home_update_download_failed)
                        }
                        return@show
                    }
                updateDownloadId = downloadId
                handle.showDownloading()
                downloadProgressJob?.cancel()
                downloadProgressJob =
                    lifecycleScope.launch {
                        pollDownloadProgress(downloadId, handle)
                    }
                handle.dialog.setOnDismissListener {
                    downloadProgressJob?.cancel()
                }
            },
            onLater = {
                UpdatePrefs.homeUpdatePromptDismissedTag = updateInfo.tagName
            },
        )
    }

    private suspend fun pollDownloadProgress(
        downloadId: Long,
        handle: HomeUpdatePromptHelper.UpdateDialogHandle,
    ) {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            currentCoroutineContext().ensureActive()
            val query = DownloadManager.Query().setFilterById(downloadId)
            val (status, downloaded, total) =
                runCatching {
                    downloadManager.query(query)?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        Triple(
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        )
                    }
                }.getOrNull() ?: break
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // The download receiver launches the installer; the
                    // dialog just reports arrival.
                    handle.showDownloaded()
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    handle.showFailed()
                    lifecycleScope.launch {
                        ToastHelper.show(this@MainActivity, R.string.home_update_download_failed)
                    }
                    break
                }
                else -> {
                    if (total > 0) {
                        handle.setProgress(((downloaded * 100) / total).toInt())
                    }
                    delay(DOWNLOAD_PROGRESS_POLL_MS)
                }
            }
        }
    }

    private fun dismissUpdateBanner() {
        bannerDismissJob?.cancel()
        binding.updateBannerSlot.removeAllViews()
        binding.updateBannerSlot.visibility = android.view.View.GONE
    }

    private fun maybeShowStartupUpdatePrompt() {
        if (BuildConfig.FLAVOR != "github" || startupUpdatePromptHandled) return

        binding.container.post {
            val mainFragment = supportFragmentManager.findFragmentById(binding.container.id) as? MainFragment ?: return@post
            startupUpdatePromptHandled = true
            mainFragment.maybeShowStartupUpdatePrompt()
        }
    }

    private fun registerDownloadReceiver() {
        if (isDownloadReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        isDownloadReceiverRegistered = true
    }

    private fun unregisterDownloadReceiver() {
        if (!isDownloadReceiverRegistered) return
        runCatching { unregisterReceiver(downloadReceiver) }
        isDownloadReceiverRegistered = false
    }

    companion object {
        private const val UPDATE_BANNER_TIMEOUT_MS = 15_000L
        private const val DOWNLOAD_PROGRESS_POLL_MS = 500L
    }

    fun startScreensaver() {
        fromScreensaver = false
        try {
            val intent = Intent(this, TestActivity::class.java)
            resultLauncher.launch(intent)
        } catch (ex: Exception) {
            Timber.e(ex)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference,
    ): Boolean {
        val fragment =
            supportFragmentManager.fragmentFactory
                .instantiate(
                    classLoader,
                    pref.fragment.toString(),
                ).apply {
                    arguments = pref.extras
                }

        supportFragmentManager
            .commit {
                setCustomAnimations(
                    R.anim.slide_in,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.slide_out,
                )
                replace(binding.container.id, fragment)
                    .addToBackStack(null)
            }.apply {
                title = pref.title
            }

        return true
    }
}
