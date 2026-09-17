package com.hicham.llmchat.runtime.t2

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.util.UUID

class T2ProviderService : Service() {
    companion object {
        const val NO_FAILURE = 0
        const val DIE_AFTER_RECEIVED = 1
        const val DIE_AFTER_EFFECT = 2
    }

    private lateinit var store: T2ProviderStore
    private val processInstanceId = UUID.randomUUID().toString()

    override fun onCreate() {
        super.onCreate()
        store = T2ProviderStore(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IT2Provider.Stub() {
        override fun execute(operationId: String, faultMode: Int) {
            val received = store.receive(operationId)
            if (faultMode == DIE_AFTER_RECEIVED) {
                Process.killProcess(Process.myPid())
                return
            }

            store.applyEffect(operationId)
            if (faultMode == DIE_AFTER_EFFECT) {
                Process.killProcess(Process.myPid())
                return
            }

            if (received.state == T2OperationState.RECEIVED) {
                store.complete(operationId)
            } else if (store.get(operationId)?.state == T2OperationState.EFFECT_APPLIED) {
                store.complete(operationId)
            }
        }

        override fun reconcile(operationId: String) {
            check(store.get(operationId)?.state == T2OperationState.EFFECT_APPLIED) {
                "Cannot reconcile operation without a persisted applied effect: $operationId"
            }
            store.complete(operationId)
        }

        override fun getState(operationId: String): String =
            requireNotNull(store.get(operationId)).state.name

        override fun getEffectCount(operationId: String): Int =
            requireNotNull(store.get(operationId)).effectCount

        override fun getPid(): Int = Process.myPid()

        override fun getProcessInstanceId(): String = processInstanceId
    }
}
