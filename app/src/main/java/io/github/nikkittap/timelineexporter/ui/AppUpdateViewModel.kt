package io.github.nikkittap.timelineexporter.ui

import android.app.Application
import android.content.IntentSender
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the optional update prompt stands. [Available] and [Downloaded] each
 * drive one Snackbar in the UI; [None] shows nothing.
 */
sealed interface AppUpdateState {
    data object None : AppUpdateState
    /** Play has a newer flexible-eligible build; [AppUpdateViewModel.startUpdate] launches it. */
    data object Available : AppUpdateState
    /** The flexible update finished downloading and is waiting to install. */
    data object Downloaded : AppUpdateState
}

/**
 * Wraps Play Core's *flexible* in-app update flow: a Snackbar offering to
 * update, never a blocking screen. The user keeps using the current build
 * while the new one downloads in the background, can dismiss the prompt
 * outright, or restarts once it's ready — none of it is forced. The actual
 * download and install run in a Play Store service outside this process; this
 * class only asks Play where things stand and reacts.
 */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(application)

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.None)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /** Set by [dismissAvailable] so a resume check doesn't reopen a prompt the user just closed. */
    private var userDismissedAvailable = false

    /** From the latest successful check; [startUpdate] needs it to launch the flow. */
    private var latestInfo: AppUpdateInfo? = null

    private val installListener = InstallStateUpdatedListener { installState ->
        if (installState.installStatus() == InstallStatus.DOWNLOADED) {
            _state.value = AppUpdateState.Downloaded
        }
    }

    init {
        appUpdateManager.registerListener(installListener)
        checkForUpdate()
    }

    /**
     * Asks Play whether a newer build exists. Called on init and again from
     * the UI's onResume, so a flexible download that finished while the app
     * was backgrounded still surfaces the "restart" prompt. Silently does
     * nothing on failure (no Play Store on the device, no network) — the
     * prompt is a nicety, not something worth alarming a user over.
     */
    fun checkForUpdate() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                latestInfo = info
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED ->
                        _state.value = AppUpdateState.Downloaded
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                        !userDismissedAvailable ->
                        _state.value = AppUpdateState.Available
                    _state.value != AppUpdateState.Downloaded ->
                        _state.value = AppUpdateState.None
                }
            }
            .addOnFailureListener { e ->
                Log.d(TAG, "appUpdateInfo check failed: ${e.message}")
            }
    }

    /** User tapped the Snackbar's "Update" action. */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val info = latestInfo ?: return
        try {
            appUpdateManager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
        } catch (e: IntentSender.SendIntentException) {
            Log.w(TAG, "Could not start update flow", e)
        }
    }

    /** User tapped the Snackbar's "Restart" action once the download finished. */
    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    /** User dismissed the "update available" Snackbar; don't reopen it this session. */
    fun dismissAvailable() {
        userDismissedAvailable = true
        if (_state.value == AppUpdateState.Available) {
            _state.value = AppUpdateState.None
        }
    }

    override fun onCleared() {
        super.onCleared()
        appUpdateManager.unregisterListener(installListener)
    }

    companion object {
        private const val TAG = "AppUpdateVM"
    }
}
