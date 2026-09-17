package com.hicham.llmchat.runtime.t2

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class T2CallerService : Service() {
    companion object {
        const val NO_FAILURE = 0
        const val DIE_BEFORE_DISPATCH = 1
        const val DIE_AFTER_PROVIDER_REPLY_BEFORE_COMPLETE = 2
    }

    protected open val callerStoreNamespace: String = "main"

    private lateinit var store: T2CallerStore
    private val callerProcessInstanceId = UUID.randomUUID().toString()

    override fun onCreate() {
        super.onCreate()
        store = T2CallerStore(this, callerStoreNamespace)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IT2Caller.Stub() {
        override fun startOperation(operationId: String, faultMode: Int) {
            store.reserve(operationId)
            if (faultMode == DIE_BEFORE_DISPATCH) {
                Process.killProcess(Process.myPid())
                return
            }

            store.markDispatching(operationId)
            val provider = bindProvider()
            try {
                provider.execute(operationId, T2ProviderService.NO_FAILURE)
            } finally {
                unbindProvider()
            }

            if (faultMode == DIE_AFTER_PROVIDER_REPLY_BEFORE_COMPLETE) {
                Process.killProcess(Process.myPid())
                return
            }

            store.markCompleted(operationId)
        }

        override fun recover(operationId: String) {
            val current = requireNotNull(store.get(operationId)) {
                "Unknown caller operation_id=$operationId"
            }
            store.markUnknown(operationId)

            val provider = bindProvider()
            try {
                val providerState = provider.lookupState(operationId)
                when (providerState) {
                    T2ProviderService.ABSENT -> {
                        store.markKnownNotExecuted(operationId)
                        provider.execute(operationId, T2ProviderService.NO_FAILURE)
                        store.markCompleted(operationId)
                    }
                    T2OperationState.RECEIVED.name -> {
                        provider.execute(operationId, T2ProviderService.NO_FAILURE)
                        store.markCompleted(operationId)
                    }
                    T2OperationState.EFFECT_APPLIED.name -> {
                        provider.reconcile(operationId)
                        store.markCompleted(operationId)
                    }
                    T2OperationState.COMPLETED.name -> {
                        store.markCompleted(operationId)
                    }
                    else -> error("Unknown provider state for ${current.state}: $providerState")
                }
            } finally {
                unbindProvider()
            }
        }

        override fun getState(operationId: String): String =
            requireNotNull(store.get(operationId)).state.name

        override fun getPid(): Int = Process.myPid()

        override fun getProcessInstanceId(): String = callerProcessInstanceId
    }

    private var providerConnection: ServiceConnection? = null

    private fun bindProvider(): IT2Provider {
        check(providerConnection == null) { "Provider already bound" }
        val connected = CountDownLatch(1)
        var failure: Throwable? = null
        var provider: IT2Provider? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                provider = IT2Provider.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                provider = null
            }

            override fun onBindingDied(name: ComponentName) {
                provider = null
            }

            override fun onNullBinding(name: ComponentName) {
                failure = IllegalStateException("Provider returned a null binding")
                connected.countDown()
            }
        }
        providerConnection = connection

        val bound = bindService(
            Intent(this, T2ProviderService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        check(bound) { "bindService() failed" }
        check(connected.await(10, TimeUnit.SECONDS)) { "Timed out waiting for provider bind" }
        failure?.let { throw it }
        return checkNotNull(provider) { "Provider binder missing after connection" }
    }

    private fun unbindProvider() {
        val current = providerConnection ?: return
        runCatching { unbindService(current) }
        providerConnection = null
    }
}
