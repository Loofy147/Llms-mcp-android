package com.hicham.llmchat.runtime.t2

import com.hicham.llmchat.runtime.CapabilityInvocation
import com.hicham.llmchat.runtime.EffectRecord
import com.hicham.llmchat.runtime.EffectReconciliationDecision
import com.hicham.llmchat.runtime.EffectReconciliationResult
import com.hicham.llmchat.runtime.EffectReservation
import com.hicham.llmchat.runtime.JournalRuntimeStore
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.UUID
import java.util.concurrent.TimeUnit

class T2CallerService : Service() {
    private lateinit var store: JournalRuntimeStore
    private val callerProcessInstanceId = UUID.randomUUID().toString()

    override fun onCreate() {
        super.onCreate()
        store = newStore()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IT2Caller.Stub() {
        override fun reset() {
            val file = File(filesDir, "t2-caller-runtime.journal")
            file.delete()
            store = newStore()
        }

        override fun startB4(operationId: String) {
            val invocation = invocation(operationId)
            require(
                store.reserveEffects(listOf(invocation)) == EffectReservation.RESERVED
            )

            withProvider { provider ->
                provider.execute(operationId, T2ProviderService.NO_FAILURE)
            }

            Process.killProcess(Process.myPid())
        }

        override fun startB5(operationId: String) {
            val invocation = invocation(operationId)
            require(
                store.reserveEffects(listOf(invocation)) == EffectReservation.RESERVED
            )

            Process.killProcess(Process.myPid())
        }

        override fun getProcessInstanceId(): String = callerProcessInstanceId

        override fun recover(operationId: String): String {
            store.recoverInterruptedEffects()
            val effect = store.unknownEffects().singleOrNull { it.effectId == operationId }
                ?: throw IllegalStateException("Effect is not UNKNOWN after recovery: $operationId")

            val providerState = withProvider { provider ->
                provider.getState(operationId).takeUnless { it == "ABSENT" }
            }

            return if (
                providerState == T2OperationState.COMPLETED.name ||
                providerState == T2OperationState.EFFECT_APPLIED.name
            ) {
                require(
                    store.reconcileEffect(
                        operationId,
                        EffectReconciliationDecision.CONFIRMED_COMPLETED,
                    ) == EffectReconciliationResult.RECONCILED
                )
                val providerCounts = withProvider { provider ->
                    provider.getRequestCount(operationId).toString() to
                        provider.getEffectCount(operationId).toString()
                }
                "caller=COMPLETED;decision=CONFIRMED_COMPLETED;provider=$providerState;" +
                    "requests=${providerCounts.first};effects=${providerCounts.second};replay=false"
            } else {
                require(
                    store.reconcileEffect(
                        operationId,
                        EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED,
                    ) == EffectReconciliationResult.RECONCILED
                )
                require(
                    store.reserveEffects(listOf(effect.toInvocation())) == EffectReservation.RESERVED
                )
                withProvider { provider ->
                    provider.execute(operationId, T2ProviderService.NO_FAILURE)
                }
                store.completeEffect(operationId)

                val providerCounts = withProvider { provider ->
                    Triple(
                        provider.getState(operationId),
                        provider.getRequestCount(operationId).toString(),
                        provider.getEffectCount(operationId).toString(),
                    )
                }
                "caller=COMPLETED;decision=CONFIRMED_NOT_EXECUTED;provider=${providerCounts.first};" +
                    "requests=${providerCounts.second};effects=${providerCounts.third};replay=true"
            }
        }
    }

    private fun newStore(): JournalRuntimeStore =
        JournalRuntimeStore(File(filesDir, "t2-caller-runtime.journal"))

    private fun invocation(operationId: String): CapabilityInvocation =
        CapabilityInvocation(
            runId = "t2-caller-run-$operationId",
            capabilityId = "t2.test.capability",
            actionId = "t2.test.action",
            actionVersion = 1,
            effectId = operationId,
            scope = setOf("test"),
            attributedTo = "t2-caller-test",
            parameters = mapOf("operation_id" to operationId),
        )

    private fun EffectRecord.toInvocation(): CapabilityInvocation =
        CapabilityInvocation(
            runId = runId,
            capabilityId = capabilityId,
            actionId = actionId,
            actionVersion = actionVersion,
            effectId = effectId,
            scope = scope,
            attributedTo = attributedTo,
            parameters = parameters,
        )

    private fun <T> withProvider(block: (IT2Provider) -> T): T {
        val connected = CountDownLatch(1)
        var binderFailure: Throwable? = null
        val newConnection = object : ServiceConnection {
            var provider: IT2Provider? = null

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
                binderFailure = IllegalStateException("Provider returned a null binding")
                connected.countDown()
            }

            fun currentProvider(): IT2Provider =
                checkNotNull(provider) { "Provider binder missing after bind" }
        }

        try {
            check(
                bindService(
                    Intent(this, T2ProviderService::class.java),
                    newConnection,
                    Context.BIND_AUTO_CREATE,
                )
            ) { "bindService() failed" }

            check(connected.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting for provider bind"
            }

            binderFailure?.let { throw it }
            return block(newConnection.currentProvider())
        } finally {
            runCatching { unbindService(newConnection) }
        }
    }
}
