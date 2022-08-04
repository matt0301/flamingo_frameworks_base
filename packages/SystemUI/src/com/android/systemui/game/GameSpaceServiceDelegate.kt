/*
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.game

import android.app.Activity
import android.app.IActivityManager
import android.app.PendingIntent
import android.app.TaskStackListener
import android.app.WindowConfiguration
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.telephony.TelephonyCallback.CallStateListener
import android.telephony.TelephonyManager
import android.util.Log

import androidx.lifecycle.Observer

import com.android.internal.annotations.GuardedBy
import com.android.internal.statusbar.IStatusBarService
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Prefs
import com.android.systemui.R
import com.android.systemui.SystemUI
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.screenrecord.RecordingController.RecordingStateChangeCallback
import com.android.systemui.screenrecord.RecordingService
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.ScreenRecordDialog.MODES
import com.android.systemui.screenrecord.ScreenRecordDialog.PREFS
import com.android.systemui.screenrecord.ScreenRecordDialog.PREF_AUDIO
import com.android.systemui.screenrecord.ScreenRecordDialog.PREF_AUDIO_SOURCE
import com.android.systemui.screenrecord.ScreenRecordDialog.PREF_DOT
import com.android.systemui.screenrecord.ScreenRecordDialog.PREF_LONGER
import com.android.systemui.screenrecord.ScreenRecordDialog.PREF_LOW
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.events.PrivacyDotViewController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor
import com.android.systemui.telephony.TelephonyListenerManager
import com.android.systemui.util.RingerModeTracker
import com.android.systemui.util.settings.SystemSettings

import javax.inject.Inject

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@SysUISingleton
class GameSpaceServiceDelegate @Inject constructor(
    private val systemSettings: SystemSettings,
    private val notificationInterruptStateProvider: NotificationInterruptStateProvider,
    private val screenLifecycle: ScreenLifecycle,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val iStatusBarService: IStatusBarService,
    private val ringerModeTracker: RingerModeTracker,
    private val telephonyListenerManager: TelephonyListenerManager,
    private val iActivityManager: IActivityManager,
    private val recordingController: RecordingController,
    private val userContextProvider: UserContextProvider,
    private val privacyDotViewController: PrivacyDotViewController,
    context: Context
) : SystemUI(context) {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val settingsObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            coroutineScope.launch(Dispatchers.IO) {
                when (val key = uri?.lastPathSegment) {
                    Settings.System.GAMESPACE_ENABLED -> {
                        stateMutex.withLock {
                            gameSpaceEnabled = getBoolSetting(key, DEFAULT_GAMESPACE_ENABLED)
                            if (gameSpaceEnabled) {
                                registerTaskStackListenerLocked()
                            } else {
                                unregisterTaskStackListenerLocked()
                                disableGameModeLocked()
                            }
                        }
                    }
                    Settings.System.GAMESPACE_PACKAGE_LIST -> {
                        stateMutex.withLock {
                            gameSpacePackages = getPackages(key)
                            if (!gameSpacePackages.contains(currentTopPackageName)) {
                                disableGameModeLocked()
                            }
                        }
                    }
                    Settings.System.GAMESPACE_DYNAMIC_MODE -> {
                        stateMutex.withLock {
                            dynamicMode = getBoolSetting(key, DEFAULT_GAMESPACE_DYNAMIC_MODE)
                        }
                    }
                    Settings.System.GAMESPACE_DISABLE_HEADSUP -> {
                        stateMutex.withLock {
                            disableHeadsUp = getBoolSetting(key, DEFAULT_GAMESPACE_DISABLE_HEADSUP)
                        }
                    }
                    Settings.System.GAMESPACE_DISABLE_FULLSCREEN_INTENT -> {
                        stateMutex.withLock {
                            disableFullscreenIntent = getBoolSetting(key, DEFAULT_GAMESPACE_DISABLE_FULLSCREEN_INTENT)
                        }
                    }
                    Settings.System.GAMESPACE_DISABLE_CALL_RINGING -> {
                        stateMutex.withLock {
                            disableCallRinging = getBoolSetting(key, DEFAULT_GAMESPACE_DISABLE_CALL_RINGING)
                        }
                    }
                    Settings.System.GAMESPACE_HIDE_PRIVACY_INDICATORS -> {
                        stateMutex.withLock {
                            hidePrivacyIndicators = getBoolSetting(key, DEFAULT_GAMESPACE_HIDE_PRIVACY_INDICATORS)
                            updatePrivacyIndicatorsLocked()
                        }
                    }
                }
            }
        }
    }

    private val stateMutex = Mutex()

    @GuardedBy("stateMutex")
    private var gameSpaceEnabled = DEFAULT_GAMESPACE_ENABLED

    @GuardedBy("stateMutex")
    private var gameSpacePackages = emptyList<String>()

    @GuardedBy("stateMutex")
    private var dynamicMode = DEFAULT_GAMESPACE_DYNAMIC_MODE

    @GuardedBy("stateMutex")
    private var disableHeadsUp = DEFAULT_GAMESPACE_DISABLE_HEADSUP

    @GuardedBy("stateMutex")
    private var disableFullscreenIntent = DEFAULT_GAMESPACE_DISABLE_FULLSCREEN_INTENT

    @GuardedBy("stateMutex")
    private var disableCallRinging = DEFAULT_GAMESPACE_DISABLE_CALL_RINGING

    @GuardedBy("stateMutex")
    private var hidePrivacyIndicators = DEFAULT_GAMESPACE_HIDE_PRIVACY_INDICATORS

    @GuardedBy("stateMutex")
    private var taskStackListenerRegistered = false

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            logD("onTaskStackChanged")
            coroutineScope.launch {
                val topApp = withContext(Dispatchers.Default) {
                    getTopApp()
                } ?: return@launch
                taskStackChangeChannel.send(topApp)
            }
        }
    }
    private val taskStackChangeChannel = Channel<String>(capacity = Channel.CONFLATED)
    private var currentTopPackageName: String? = null
    private var taskStackChannelReceiveJob: Job? = null

    private val notificationInterruptSuppressor = object : NotificationInterruptSuppressor {
        override fun suppressAwakeInterruptions(entry: NotificationEntry) =
            runBlocking {
                stateMutex.withLock {
                    gameModeEnabled && disableHeadsUp
                }
            }
    }

    @GuardedBy("stateMutex")
    private var gameModeEnabled = false

    private val audioManager = context.getSystemService(AudioManager::class.java)

    @GuardedBy("stateMutex")
    private var ringerModeChanged = false
    @GuardedBy("stateMutex")
    private var previousRingerMode = AudioManager.RINGER_MODE_NORMAL

    @GuardedBy("stateMutex")
    private var brightnessModeChanged = false
    @GuardedBy("stateMutex")
    private var previousBrightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL

    @GuardedBy("stateMutex")
    private val gameSpaceConfig = Bundle()
    private val iGameSpaceServiceCallback = object : IGameSpaceServiceCallback.Stub() {
        override fun setGesturalNavigationLocked(isLocked: Boolean) {
            coroutineScope.launch(Dispatchers.Default) {
                try {
                    iStatusBarService.setBlockedGesturalNavigation(isLocked)
                    val configCopy = stateMutex.withLock {
                        gameSpaceConfig.putBoolean(CONFIG_BACK_GESTURE_LOCKED, isLocked)
                        gameSpaceConfig.deepCopy()
                    }
                    iGameSpaceService?.onStateChanged(configCopy) ?:
                        Log.wtf(TAG, "Service binder is null, failed to notify gesture lock change")
                } catch(e: RemoteException) {
                    Log.e(TAG, "Failed to set gestural navigation lock", e)
                }
            }
        }

        override fun setRingerMode(mode: Int) {
            coroutineScope.launch {
                val currentRingerMode = audioManager.ringerModeInternal
                if (currentRingerMode == mode) return@launch
                when (mode) {
                    AudioManager.RINGER_MODE_NORMAL,
                    AudioManager.RINGER_MODE_VIBRATE,
                    AudioManager.RINGER_MODE_SILENT -> { 
                        audioManager.ringerModeInternal = mode
                        stateMutex.withLock {
                            if (!ringerModeChanged) {
                                previousRingerMode = currentRingerMode
                                ringerModeChanged = true
                            }
                        }
                    }
                    else -> Log.e(TAG, "Unknown ringer mode $mode")
                }
            }
        }

        override fun setAdaptiveBrightnessDisabled(disabled: Boolean) {
            coroutineScope.launch(Dispatchers.IO) {
                val currentMode = systemSettings.getIntForUser(
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT
                )
                val isDisabled = currentMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                if (isDisabled == disabled) return@launch
                setBrightnessMode(
                    if (disabled)
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    else
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                )
                stateMutex.withLock {
                    if (!brightnessModeChanged) {
                        previousBrightnessMode = currentMode
                        brightnessModeChanged = true
                    }
                }
            }
        }

        override fun startScreenRecording() {
            if (recordingController.isRecording || recordingController.isStarting) return
            coroutineScope.launch(Dispatchers.IO) {
                val userContext = userContextProvider.userContext

                val showStopDot = Prefs.getInt(userContext, PREFS + PREF_DOT, 0) == 1
                val longerDuration = Prefs.getInt(userContext, PREFS + PREF_LONGER, 0) == 1
                val hasAudioSource = Prefs.getInt(userContext, PREFS + PREF_AUDIO, 0) == 1
                val audioSource = if (hasAudioSource) {
                    val index = Prefs.getInt(userContext, PREFS + PREF_AUDIO_SOURCE, 0)
                    MODES[index]
                } else {
                    ScreenRecordingAudioSource.NONE
                }
                val lowQuality = Prefs.getInt(userContext, PREFS + PREF_LOW, 0) == 1

                val startIntent = PendingIntent.getForegroundService(userContext,
                    RecordingService.REQUEST_CODE,
                    RecordingService.getStartIntent(
                        userContext,
                        Activity.RESULT_OK,
                        audioSource.ordinal,
                        showStopDot,
                        lowQuality,
                        longerDuration
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val stopIntent = PendingIntent.getService(
                    userContext,
                    RecordingService.REQUEST_CODE,
                    RecordingService.getStopIntent(userContext),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                withContext(Dispatchers.Main) {
                    recordingController.startCountdown(
                        1000, /* delay */
                        500, /* interval */
                        startIntent,
                        stopIntent
                    )
                }
            }
        }

        override fun stopScreenRecording() {
            if (recordingController.isStarting) {
                recordingController.cancelCountdown()
            } else if (recordingController.isRecording) {
                recordingController.stopRecording()
            }
        }
    }

    private var iGameSpaceService: IGameSpaceService? = null
    private val gameSpaceIntent = Intent()
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            logD("onServiceConnected")
            iGameSpaceService = IGameSpaceService.Stub.asInterface(service)
            coroutineScope.launch {
                iGameSpaceService?.let {
                    try {
                        it.setCallback(iGameSpaceServiceCallback)

                        val configCopy = stateMutex.withLock { gameSpaceConfig.deepCopy() }
                        it.onStateChanged(configCopy)

                        val topPackage = stateMutex.withLock { currentTopPackageName }
                        logD("Showing game ui")
                        it.showGameUI(topPackage)
                    } catch (e: RemoteException) {
                        Log.e(TAG, "Failed to communicate with binder because", e)
                    }
                } ?: Log.e(TAG, "Failed to communicate with null binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            iGameSpaceService = null
        }

        override fun onBindingDied(name: ComponentName) {
            Log.e(TAG, "Service binding died, severing connection")
            iGameSpaceService = null
            coroutineScope.launch {
                disableGameMode()
            }
        }

        override fun onNullBinding(name: ComponentName) {
            Log.wtf(TAG, "Service returned null binder, severing connection")
            iGameSpaceService = null
            coroutineScope.launch {
                disableGameMode()
            }
        }
    }

    private val pm = mContext.packageManager

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(mContext: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_GAME_MODE) {
                if (screenLifecycleObserverRegistered) {
                    screenLifecycle.removeObserver(screenLifecycleObserver)
                    screenLifecycleObserverRegistered = false
                }
                if (keyguardUpdateMonitorCallbackRegistered) {
                    keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
                    keyguardUpdateMonitorCallbackRegistered = false
                }
                coroutineScope.launch {
                    disableGameMode()
                }
            }
        }
    }

    private var screenLifecycleObserverRegistered = false
    private val screenLifecycleObserver = object : ScreenLifecycle.Observer {
        override fun onScreenTurnedOn() {
            logD("onScreenTurnedOn")
            if (keyguardUpdateMonitor.isKeyguardVisible()) {
                logD("Keyguard is visible, registering keyguardUpdateMonitorCallback")
                // Turn gaming mode back on when keyguard visibility changes.
                if (!keyguardUpdateMonitorCallbackRegistered) {
                    keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
                    keyguardUpdateMonitorCallbackRegistered = true
                }
            } else {
                coroutineScope.launch {
                    val topPackage = stateMutex.withLock { currentTopPackageName }
                    if (topPackage != null) {
                        logD("onScreenTurnedOn: Attempting to start game mode back")
                        checkTopAppAndUpdateState(topPackage)
                    }
                }
            }
            if (screenLifecycleObserverRegistered) {
                // keyguardUpdateMonitorCallback will handle the job of enabling
                // gaming mode back. If keyguard is not visible, game mode will be
                // enabled back if a game is in foreground, if not, we don't have to
                // observe screen lifecycle until a game is opened in the future.
                logD("Removing screenLifecycleObserver")
                screenLifecycle.removeObserver(this)
                screenLifecycleObserverRegistered = false
            }
        }

        override fun onScreenTurningOff() {
            logD("onScreenTurningOff")
            coroutineScope.launch {
                disableGameMode()
            }
        }
    }

    private var keyguardUpdateMonitorCallbackRegistered = false
    private val keyguardUpdateMonitorCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onKeyguardVisibilityChanged(showing: Boolean) {
            logD("onKeyguardVisibilityChanged: showing = $showing")
            if (!showing) {
                coroutineScope.launch {
                    val topPackage = stateMutex.withLock { currentTopPackageName }
                    if (topPackage != null) {
                        logD("onKeyguardVisibilityChanged: Attempting to start game mode back")
                        checkTopAppAndUpdateState(topPackage)
                    }
                }
                if (keyguardUpdateMonitorCallbackRegistered) {
                    logD("Removing keyguardUpdateMonitorCallback")
                    // Unregister even if top package is not a game since we don't have
                    // to observe keyguard state until next screen off.
                    keyguardUpdateMonitor.removeCallback(this)
                    keyguardUpdateMonitorCallbackRegistered = false
                }
            }
        }
    }

    private var ringerModeObserverRegistered = false
    private val ringerModeObserver = Observer<Int> {
        logD("Ringer mode changed to $it")
        coroutineScope.launch(Dispatchers.Default) {
            val configCopy = stateMutex.withLock {
                gameSpaceConfig.putInt(CONFIG_RINGER_MODE, it)
                gameSpaceConfig.deepCopy()
            }
            try {
                iGameSpaceService?.onStateChanged(configCopy) ?:
                    Log.wtf(TAG, "Service binder is null, failed to notify ringer mode change")
            } catch(e: RemoteException) {
                Log.e(TAG, "Failed to notify ringer mode change", e)
            }
        }
    }

    private val callStateChannel = Channel<Int>(capacity = Channel.CONFLATED)
    private val callstateListener = CallStateListener {
        coroutineScope.launch {
            callStateChannel.send(it)
        }
    }
    private var callStateListeningJob: Job? = null
    @GuardedBy("stateMutex")
    private var previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)

    private val recordingCallback = object : RecordingStateChangeCallback {

        override fun onCountdown(millisUntilFinished: Long) {
            coroutineScope.launch(Dispatchers.Default) {
                val configCopy = stateMutex.withLock {
                    gameSpaceConfig.putBoolean(CONFIG_SCREEN_RECORD, true)
                    gameSpaceConfig.deepCopy()
                }
                try {
                    iGameSpaceService?.onStateChanged(configCopy) ?:
                        Log.wtf(TAG, "Service binder is null, failed to notify screen record state change")
                } catch(e: RemoteException) {
                    Log.e(TAG, "Failed to notify screen record state change", e)
                }
            }
        }

        override fun onRecordingEnd() {
            coroutineScope.launch(Dispatchers.Default) {
                val configCopy = stateMutex.withLock {
                    gameSpaceConfig.putBoolean(CONFIG_SCREEN_RECORD, false)
                    gameSpaceConfig.deepCopy()
                }
                try {
                    iGameSpaceService?.onStateChanged(configCopy) ?:
                        Log.wtf(TAG, "Service binder is null, failed to notify screen record state change")
                } catch(e: RemoteException) {
                    Log.e(TAG, "Failed to notify screen record state change", e)
                }
            }
        }
    }

    override fun start() {
        logD("start")
        val serviceComponentString = mContext.getString(R.string.config_gameSpaceServiceComponent)
        if (serviceComponentString.isBlank()) {
            Log.i(TAG, "Not starting service since component is unavailable")
            return
        }
        val serviceComponent = ComponentName.unflattenFromString(serviceComponentString) ?: run {
            Log.wtf(TAG, "Service component could not be parsed from resource!")
            return
        }
        val serviceInfo = try {
            pm.getServiceInfo(serviceComponent, PackageManager.MATCH_SYSTEM_ONLY)
        } catch(_: PackageManager.NameNotFoundException) {
            Log.wtf(TAG, "Service $serviceComponent not found")
            return
        }
        if (serviceInfo.permission != SERVICE_PERMISSION) {
            Log.e(TAG, "Service $serviceComponent does not hold permission $SERVICE_PERMISSION")
            return
        }
        gameSpaceIntent.component = serviceComponent

        coroutineScope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                loadSettingsLocked()
                if (gameSpaceEnabled) {
                    registerTaskStackListenerLocked()
                }
            }
        }
        notificationInterruptStateProvider.addSuppressor(notificationInterruptSuppressor)
        registerSettingsObservers(
            Settings.System.GAMESPACE_ENABLED,
            Settings.System.GAMESPACE_PACKAGE_LIST,
            Settings.System.GAMESPACE_DYNAMIC_MODE,
            Settings.System.GAMESPACE_DISABLE_HEADSUP,
            Settings.System.GAMESPACE_DISABLE_FULLSCREEN_INTENT,
            Settings.System.GAMESPACE_HIDE_PRIVACY_INDICATORS
        )
    }

    private suspend fun loadSettingsLocked() {
        gameSpaceEnabled = getBoolSetting(Settings.System.GAMESPACE_ENABLED, DEFAULT_GAMESPACE_ENABLED)
        gameSpacePackages = getPackages(Settings.System.GAMESPACE_PACKAGE_LIST)
        dynamicMode = getBoolSetting(Settings.System.GAMESPACE_DYNAMIC_MODE, DEFAULT_GAMESPACE_DYNAMIC_MODE)
        disableHeadsUp = getBoolSetting(Settings.System.GAMESPACE_DISABLE_HEADSUP, DEFAULT_GAMESPACE_DISABLE_HEADSUP)
        disableFullscreenIntent = getBoolSetting(
            Settings.System.GAMESPACE_DISABLE_FULLSCREEN_INTENT,
            DEFAULT_GAMESPACE_DISABLE_FULLSCREEN_INTENT
        )
        disableCallRinging = getBoolSetting(
            Settings.System.GAMESPACE_DISABLE_CALL_RINGING,
            DEFAULT_GAMESPACE_DISABLE_CALL_RINGING
        )
        hidePrivacyIndicators = getBoolSetting(
            Settings.System.GAMESPACE_HIDE_PRIVACY_INDICATORS,
            DEFAULT_GAMESPACE_HIDE_PRIVACY_INDICATORS
        )
    }

    private fun registerSettingsObservers(vararg keys: String) {
        keys.forEach {
            systemSettings.registerContentObserverForUser(it, settingsObserver, UserHandle.USER_CURRENT)
        }
    }

    private fun getBoolSetting(key: String, def: Boolean) =
        systemSettings.getIntForUser(key, if (def) 1 else 0, UserHandle.USER_CURRENT) == 1

    private fun getPackages(key: String): List<String> {
        val flattendString = systemSettings.getStringForUser(key, UserHandle.USER_CURRENT)
        return flattendString?.split(PACKAGE_DELIMITER) ?: emptyList()
    }

    private suspend fun registerTaskStackListenerLocked() {
        if (taskStackListenerRegistered) return
        try {
            iActivityManager.registerTaskStackListener(taskStackListener)
            taskStackListenerRegistered = true
        } catch(e: RemoteException) {
            Log.e(TAG, "Failed to register task stack listener", e)
            return
        }
        taskStackChannelReceiveJob = coroutineScope.launch {
            for (packageName in taskStackChangeChannel) {
                onTopAppChanged(packageName)
            }
        }
    }

    private suspend fun unregisterTaskStackListenerLocked() {
        if (!taskStackListenerRegistered) return
        try {
            iActivityManager.unregisterTaskStackListener(taskStackListener)
            taskStackListenerRegistered = false
        } catch(e: RemoteException) {
            Log.e(TAG, "Failed to unregister task stack listener", e)
        } finally {
            // Cancel job no matter what
            taskStackChannelReceiveJob?.cancel()
            taskStackChannelReceiveJob = null
        }
    }

    private fun getTopApp(): String? {
        val focusedRootTask = try {
            iActivityManager.focusedRootTaskInfo
        } catch(e: RemoteException) {
            Log.e(TAG, "Failed to get focused root task info", e)
            null
        } ?: return null
        logD("Task windowing mode = ${focusedRootTask.windowingMode}")
        return when (focusedRootTask.windowingMode) {
            WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW,
            WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
            WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
            WindowConfiguration.WINDOWING_MODE_FREEFORM -> {
                // Game mode should not be enabled in any of these windowing modes
                null
            }
            else -> focusedRootTask.topActivity?.packageName
        }
    }

    private suspend fun onTopAppChanged(packageName: String) {
        logD("onTopAppChanged: currentTopPackageName = $currentTopPackageName, packageName = $packageName")
        if (currentTopPackageName == packageName) return
        currentTopPackageName = packageName
        checkTopAppAndUpdateState(packageName, true)
    }

    private suspend fun checkTopAppAndUpdateState(packageName: String, topAppChanged: Boolean = false) {
        val selectedPackages = stateMutex.withLock { gameSpacePackages.toMutableList() }
        logD("selectedPackages = $selectedPackages")
        // Top package is in user selected package list, start game mode.
        if (selectedPackages.contains(packageName)) {
            enableGameMode(packageName, topAppChanged)
        } else {
            val isDynamicMode = stateMutex.withLock { dynamicMode }
            // Dynamic mode is enabled and top package is a game, but not in list.
            // Add it to list and start game mode.
            if (isDynamicMode && isGame(packageName)) {
                logD("Dynamically adding $packageName to list")
                selectedPackages.add(packageName)
                withContext(Dispatchers.IO) {
                    systemSettings.putStringForUser(
                        Settings.System.GAMESPACE_PACKAGE_LIST,
                        selectedPackages.joinToString(PACKAGE_DELIMITER),
                        UserHandle.USER_CURRENT
                    )
                }
                enableGameMode(packageName, topAppChanged)
            } else {
                disableGameMode()
            }
        }
    }

    private fun updatePrivacyIndicatorsLocked() {
        privacyDotViewController.setHiddenForGameMode(gameModeEnabled && hidePrivacyIndicators)
    }

    private suspend fun enableGameMode(packageName: String, topAppChanged: Boolean) {
        logD("enableGameMode")
        stateMutex.withLock {
            enableGameModeLocked(packageName, topAppChanged)
        }
    }

    private suspend fun enableGameModeLocked(packageName: String, topAppChanged: Boolean) {
        if (gameModeEnabled) {
            if (topAppChanged) {
                logD("Notify top app changed to binder")
                try {
                    iGameSpaceService?.onGamePackageChanged(packageName) ?: run {
                        Log.wtf(TAG, "Failed to call onGamePackageChanged as service binder is null")
                    }
                } catch(e: RemoteException) {
                    Log.e(TAG, "Failed to call onGamePackageChanged", e)
                }
            }
            return
        }
        logD("Trying to bind")
        val bound = try {
            mContext.bindServiceAsUser(
                gameSpaceIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM
            )
        } catch(e: SecurityException) {
            Log.wtf(TAG, "SecurityException while trying to bind with service", e)
            false
        }
        if (bound) {
            logD("Enabling game mode")
            mContext.registerReceiverAsUser(
                broadcastReceiver,
                UserHandle.SYSTEM,
                IntentFilter(ACTION_STOP_GAME_MODE),
                null /* broadcastPermission */,
                null /* scheduler */
            )
            withContext(Dispatchers.Main) {
                if (!screenLifecycleObserverRegistered) {
                    screenLifecycle.addObserver(screenLifecycleObserver)
                    screenLifecycleObserverRegistered = true
                }
                if (!ringerModeObserverRegistered) {
                    ringerModeTracker.ringerModeInternal.observeForever(ringerModeObserver)
                    ringerModeObserverRegistered = true
                }
                recordingController.addCallback(recordingCallback)
            }
            registerCallStateChangeListener()
            gameModeEnabled = bound
            updatePrivacyIndicatorsLocked()
        }
    }

    private fun isGame(packageName: String): Boolean {
        val aInfo = try {
            pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.e(TAG, "$packageName does not exist")
            return false
        }
        return aInfo.category == ApplicationInfo.CATEGORY_GAME
    }

    private suspend fun registerCallStateChangeListener() {
        if (callStateListeningJob?.isActive == true) return
        telephonyListenerManager.addCallStateListener(callstateListener)
        callStateListeningJob = coroutineScope.launch {
            for (state in callStateChannel) {
                stateMutex.withLock {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_RING,
                        if (state == TelephonyManager.CALL_STATE_RINGING && disableCallRinging) {
                            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                            0
                        } else {
                            previousVolume
                        },
                        0 /* flags */
                    )
                }
            }
        }
    }

    private suspend fun disableGameMode() {
        logD("disableGameMode")
        stateMutex.withLock {
            disableGameModeLocked()
        }
    }

    private suspend fun disableGameModeLocked() {
        if (!gameModeEnabled) return
        logD("Disabling game mode")
        // We don't want anymore stop broadcasts, yeet.
        mContext.unregisterReceiver(broadcastReceiver)
        unregisterCallStateChangeListener()
        // Remove this observer first so as to not notify changes
        // after service is unbound.
        withContext(Dispatchers.Main) {
            if (ringerModeObserverRegistered) {
                ringerModeTracker.ringerModeInternal.removeObserver(ringerModeObserver)
                ringerModeObserverRegistered = false
            }
        }
        mContext.unbindService(serviceConnection)
        iGameSpaceService = null
        try {
            iStatusBarService.setBlockedGesturalNavigation(false)
            gameSpaceConfig.putBoolean(CONFIG_BACK_GESTURE_LOCKED, false)
        } catch(e: RemoteException) {
            Log.e(TAG, "Failed to unblock gestural navigation", e)
        }
        withContext(Dispatchers.Main) {
            if (ringerModeChanged) {
                audioManager.ringerModeInternal = previousRingerMode
                ringerModeChanged = false
            }
            recordingController.removeCallback(recordingCallback)
            gameSpaceConfig.putBoolean(CONFIG_SCREEN_RECORD, false)
        }
        if (brightnessModeChanged) {
            setBrightnessMode(previousBrightnessMode)
            brightnessModeChanged = false
        }
        gameModeEnabled = false
        updatePrivacyIndicatorsLocked()
    }

    private suspend fun unregisterCallStateChangeListener() {
        if (callStateListeningJob?.isActive != true) return
        telephonyListenerManager.removeCallStateListener(callstateListener)
        callStateListeningJob?.cancelAndJoin()
    }

    private fun setBrightnessMode(mode: Int) {
        systemSettings.putIntForUser(
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            mode,
            UserHandle.USER_CURRENT
        )
    }

    fun disallowLaunchingFullScreenIntent() =
        runBlocking {
            stateMutex.withLock {
                gameModeEnabled && disableFullscreenIntent
            }
        }

    companion object {
        private const val TAG = "GameSpaceServiceDelegate"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        private const val SERVICE_PERMISSION = "com.flamingo.permission.MANAGE_GAMESPACE"

        private const val PACKAGE_DELIMITER = ";"

        private const val ACTION_STOP_GAME_MODE = "com.flamingo.gamespace.action.STOP_GAME_MODE"

        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }
}