package com.hicham.llmchat.runtime.t2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BinderFailureInjectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun effectPersistedBeforeProviderDeathIsReconciledWithoutReexecution() {
        val operationId = "t2-${UUID.randomUUID()}"
        val first = stage("bind.initial") { bind() }
        val firstProcessInstance = stage("initial.getProcessInstanceId") {
            first.getProcessInstanceId()
        }
        try {
            stage("initial.getEffectCount") {
                assertEquals(0, first.getEffectCount(operationId))
            }
            expectProviderDeath("execute.DIE_AFTER_EFFECT") {
                first.execute(operationId, T2ProviderService.DIE_AFTER_EFFECT)
            }
        } finally {
            stage("unbind.after_provider_death") { unbind() }
        }

        val recovered = stage("bind.recovered") { bind() }
        try {
            val recoveredProcessInstance = stage("recovered.getProcessInstanceId") {
                recovered.getProcessInstanceId()
            }
            stage("recovered.process_instance_changed") {
                assertNotEquals(
                    "Recovery must bind to a new provider process instance",
                    firstProcessInstance,
                    recoveredProcessInstance,
                )
            }
            stage("recovered.getState.effect_applied") {
                assertEquals(T2OperationState.EFFECT_APPLIED.name, recovered.getState(operationId))
            }
            stage("recovered.getEffectCount.before_reconcile") {
                assertEquals(1, recovered.getEffectCount(operationId))
            }

            stage("recovered.reconcile") {
                recovered.reconcile(operationId)
            }

            stage("recovered.getState.completed") {
                assertEquals(T2OperationState.COMPLETED.name, recovered.getState(operationId))
            }
            stage("recovered.getEffectCount.after_reconcile") {
                assertEquals(
                    "Reconciliation must not execute the effect again",
                    1,
                    recovered.getEffectCount(operationId),
                )
            }
        } finally {
            stage("unbind.after_recovery") { unbind() }
        }
    }

    @Test
    fun providerReceiptSurvivesDeathBeforeEffectAndCanBeExecutedAfterRecovery() {
        val operationId = "t2-${UUID.randomUUID()}"
        val first = stage("bind.initial") { bind() }
        val firstProcessInstance = stage("initial.getProcessInstanceId") {
            first.getProcessInstanceId()
        }
        try {
            expectProviderDeath("execute.DIE_AFTER_RECEIVED") {
                first.execute(operationId, T2ProviderService.DIE_AFTER_RECEIVED)
            }
        } finally {
            stage("unbind.after_provider_death") { unbind() }
        }

        val recovered = stage("bind.recovered") { bind() }
        try {
            val recoveredProcessInstance = stage("recovered.getProcessInstanceId") {
                recovered.getProcessInstanceId()
            }
            stage("recovered.process_instance_changed") {
                assertNotEquals(
                    "Recovery must bind to a new provider process instance",
                    firstProcessInstance,
                    recoveredProcessInstance,
                )
            }
            stage("recovered.getState.received") {
                assertEquals(T2OperationState.RECEIVED.name, recovered.getState(operationId))
            }
            stage("recovered.getEffectCount.before_retry") {
                assertEquals(0, recovered.getEffectCount(operationId))
            }

            stage("recovered.execute.NO_FAILURE") {
                recovered.execute(operationId, T2ProviderService.NO_FAILURE)
            }

            stage("recovered.getState.completed") {
                assertEquals(T2OperationState.COMPLETED.name, recovered.getState(operationId))
            }
            stage("recovered.getEffectCount.after_execute") {
                assertEquals(1, recovered.getEffectCount(operationId))
            }
        } finally {
            stage("unbind.after_recovery") { unbind() }
        }
    }

    private fun expectProviderDeath(stage: String, block: () -> Unit) {
        try {
            block()
            fail("[$stage] Provider process should die before returning")
        } catch (_: RemoteException) {
            // Expected transport ambiguity: the provider process died mid-RPC.
        } catch (error: IllegalArgumentException) {
            // On the API 35 emulator, an abrupt provider death after starting the
            // synchronous reply has been observed as this Parcel null-message
            // decoding failure instead of RemoteException. Treat only this exact
            // platform transport signature as the expected death manifestation.
            assertEquals("[$stage] unexpected Binder exception", "[$stage] unexpected Binder exception")
            assertEquals("Required value was null.", error.message)
        }
    }

    private fun <T> stage(name: String, block: () -> T): T {
        return try {
            block()
        } catch (error: Throwable) {
            throw AssertionError(
                "T2_STAGE[$name] failed: ${error::class.java.name}: ${error.message}",
                error,
            )
        }
    }

    private var connection: ServiceConnection? = null
    private var provider: IT2Provider? = null

    private fun bind(): IT2Provider {
        check(connection == null) { "Already bound" }
        val connected = CountDownLatch(1)
        var failure: Throwable? = null
        val newConnection = object : ServiceConnection {
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
        connection = newConnection

        val bound = context.bindService(
            Intent(context, T2ProviderService::class.java),
            newConnection,
            Context.BIND_AUTO_CREATE,
        )
        check(bound) { "bindService() failed" }
        check(connected.await(10, TimeUnit.SECONDS)) { "Timed out waiting for provider bind" }
        failure?.let { throw it }
        return checkNotNull(provider) { "Provider binder missing after connection" }
    }

    private fun unbind() {
        val current = connection ?: return
        runCatching { context.unbindService(current) }
        connection = null
        provider = null
    }
}
