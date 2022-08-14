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
 * limitations under the License.
 */

package com.android.server.policy

import android.content.Context
import android.os.RemoteException
import android.util.Slog
import android.view.KeyEvent

import com.android.internal.annotations.GuardedBy
import com.android.internal.os.IDeviceKeyManager
import com.android.internal.os.IKeyHandler
import com.android.server.LocalServices
import com.android.server.SystemService

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val BINDER_SERVICE_NAME = "device_key_manager"
private val TAG = DeviceKeyManager::class.simpleName!!

/**
 * Class that manages connection with a service component that implements
 * [IDeviceKeyHandler] for handling device specific [KeyEvent]'s.
 * [ComponentName] of the service must be set with config_deviceKeyHandler
 * and the scan codes [KeyEvent.getScanCode()] of the [KeyEvent]'s that
 * are supposed to be handled should be set with config_deviceKeyScanCodes.
 * The service component must also hold the BIND_DEVICE_KEY_HANDLER_SERVICE
 * permission in order for the system to bind with it.
 */
class DeviceKeyManager(context: Context) : SystemService(context) {

    private val mutex = Mutex()
    @GuardedBy("mutex")
    private val keyHandlers = mutableListOf<KeyHandlerInfo>()

    private val iDeviceKeyManager = object : IDeviceKeyManager.Stub() {
        override fun registerKeyHandler(keyHandler: IKeyHandler, scanCodes: IntArray, actions: IntArray) {
            coroutineScope.launch {
                val keyHandlerInfo = KeyHandlerInfo(
                    keyHandler = keyHandler,
                    scanCodes = scanCodes,
                    actions = actions
                )
                mutex.withLock {
                    keyHandlers.add(keyHandlerInfo)
                }
            }
        }
    }

    private val internalService = object : DeviceKeyManagerInternal {
        override fun handleKeyEvent(keyEvent: KeyEvent): Boolean {
            return runBlocking {
                val keyHandlersToCall = mutex.withLock {
                    keyHandlers.filter {
                        it.scanCodes.contains(keyEvent.scanCode) &&
                            it.actions.contains(keyEvent.action)
                    }.map {
                        it.keyHandler
                    }
                }
                coroutineScope.launch {
                    keyHandlersToCall.forEach {
                        try {
                            it.handleKeyEvent(keyEvent)
                        } catch(e: RemoteException) {
                            Slog.e(TAG, "Failed to notify key event", e)
                        }
                    }
                }
                return@runBlocking keyHandlersToCall.isNotEmpty()
            }
        }
    }

    private lateinit var coroutineScope: CoroutineScope

    override fun onStart() {
        coroutineScope = CoroutineScope(Dispatchers.Default)
        publishBinderService(BINDER_SERVICE_NAME, iDeviceKeyManager)
        LocalServices.addService(DeviceKeyManagerInternal::class.java, internalService)
    }

    override fun onUserSwitching(from: TargetUser?, to: TargetUser) {
        coroutineScope.launch {
            mutex.withLock {
                keyHandlers.clear()
            }
        }
    }
}

private data class KeyHandlerInfo(
    val keyHandler: IKeyHandler,
    val scanCodes: IntArray,
    val actions: IntArray,
)

interface DeviceKeyManagerInternal {
    fun handleKeyEvent(keyEvent: KeyEvent): Boolean
}