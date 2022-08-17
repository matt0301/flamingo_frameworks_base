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
import android.os.DeadObjectException
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
 * System service that provides an interface for device specific
 * [KeyEvent]'s to be handled by device specific system application
 * components. Binding with this class requires for the component to
 * extend a Service and register key handler with [IDeviceKeyManager],
 * providing the scan codes it is expecting to handle, and the event actions.
 * Unregister when lifecycle of Service ends.
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

        override fun unregisterKeyHandler(keyHandler: IKeyHandler) {
            removeKeyHandlerInternal(keyHandler)
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
                            if (e is DeadObjectException) {
                                Slog.e(TAG, "Remote process for keyhandler doesn't exist anymore, removing")
                                removeKeyHandlerInternal(it)
                                return@forEach
                            }
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

    private fun removeKeyHandlerInternal(keyHandler: IKeyHandler) {
        coroutineScope.launch {
            mutex.withLock {
                keyHandlers.removeIf { it.keyHandler == keyHandler }
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