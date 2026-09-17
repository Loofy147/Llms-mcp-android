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
class CallerRecoveryFailureInjectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun callerDiesBeforeDispatchAndRecoveryUsesExplicitProviderAbsence() {
        val operationId = "t2-caller-b5-${UUID.randomUUID()}"
        val initialProvider = stage("b5.bind.provider.initial") { bindProvider() }
        try {
            stage("b5.provider.absent.before_dispatch") {
                assertEquals(T2ProviderService.ABSENT, initialProvider.lookupState(operationId))
            }
        } finally {
            stage("b5.unbind.provider.initial") { unbindProvider() }
        }

        val initialCaller = stage("b5.bind.caller.initial") { bindCaller() }
        val initialCallerInstance = stage("b5.initial.caller_instance") {
            initialCaller.getProcessInstanceId()
        }
        try {
            expectCallerDeath("b5.caller_dies_before_dispatch") {
                initialCaller.startOperation(operationId, T2CallerService.DIE_BEFORE_DISPATCH)
            }
        } finally {
            stage("b5.unbind.caller.after_death") { unbindCaller() }
        }

        val recoveredCaller = stage("b5.bind.caller.recovered") { bindCaller() }
        try {
            stage("b5.recovered.caller_instance_changed") {
                assertNotEquals(
                    "Recovery must bind to a new caller process instance",
                    initialCallerInstance,
                    recoveredCaller.getProcessInstanceId(),
                )
            }

            stage("b5.recover") { recoveredCaller.recover(operationId) }

            stage("b5.caller.completed") {
                assertEquals(T2CallerState.COMPLETED.name, recoveredCaller.getState(operationId))
            }
        } finally {
            stage("b5.unbind.caller.recovered") { unbindCaller() }
        }

        val provider = stage("b5.bind.provider.verify") { bindProvider() }
        try {
            stage("b5.provider.completed") {
                assertEquals(T2OperationState.COMPLETED.name, provider.getState(operationId))
            }
            stage("b5.effect_count") {
                assertEquals(1, provider.getEffectCount(operationId))
            }
        } finally {
            stage("b5.unbind.provider.verify") { unbindProvider() }
        }
    }

    @Test
    fun callerDiesAfterProviderCompletionAndReconciliationDoesNotDuplicateEffect() {
        val operationId = "t2-caller-b4-${UUID.randomUUID()}"
        val initialCaller = stage("b4.bind.caller.initial") { bindCaller() }
        val initialCallerInstance = stage("b4.initial.caller_instance") {
            initialCaller.getProcessInstanceId()
        }
        try {
            expectCallerDeath("b4.caller_dies_after_provider_reply") {
                initialCaller.startOperation(
                    operationId,
                    T2CallerService.DIE_AFTER_PROVIDER_REPLY_BEFORE_COMPLETE,
                )
            }
        } finally {
            stage("b4.unbind.caller.after_death") { unbindCaller() }
        }

        val provider = stage("b4.bind.provider.after_caller_death") { bindProvider() }
        try {
            stage("b4.provider.completed_before_recovery") {
                assertEquals(T2OperationState.COMPLETED.name, provider.getState(operationId))
            }
            stage("b4.effect_count.before_recovery") {
                assertEquals(1, provider.getEffectCount(operationId))
            }
        } finally {
            stage("b4.unbind.provider.after_observation") { unbindProvider() }
        }

        val recoveredCaller = stage("b4.bind.caller.recovered") { bindCaller() }
        try {
            stage("b4.recovered.caller_instance_changed") {
                assertNotEquals(
                    "Recovery must bind to a new caller process instance",
                    initialCallerInstance,
                    recoveredCaller.getProcessInstanceId(),
                )
            }

            stage("b4.recover") { recoveredCaller.recover(operationId) }

            stage("b4.caller.completed") {
                assertEquals(T2CallerState.COMPLETED.name, recoveredCaller.getState(operationId))
            }
        } finally {
            stage("b4.unbind.caller.recovered") { unbindCaller() }
        }

        val verifiedProvider = stage("b4.bind.provider.verify") { bindProvider() }
        try {
            stage("b4.effect_count.after_recovery") {
                assertEquals(
                    "Caller reconciliation must not execute the completed effect again",
                    1,
                    verifiedProvider.getEffectCount(operationId),
                )
            }
            stage("b4.provider.still_completed") {
                assertEquals(T2OperationState.COMPLETED.name, verifiedProvider.getState(operationId))
            }
        } finally {
            stage("b4.unbind.provider.verify") { unbindProvider() }
        }
    }

    private fun expectCallerDeath(stage: String, block: () -> Unit) {
        try {
            block()
            fail("[$stage] Caller process should die before returning")
        } catch (_: RemoteException) {
            // Expected: the caller process dies while serving the Binder request.
        }
    }

    private fun <T> stage(name: String, block: () -> T): T {
        return try {
            block()
        } catch (error: Throwable) {
            throw AssertionError(
                "T2_CALLER_STAGE[$name] failed: ${error::class.java.name}: ${error.message}",
                error,
            )
        }
    }

    private var callerConnection: ServiceConnection? = null
    private var caller: IT2Caller? = null
    private var providerConnection: ServiceConnection? = null
    private var provider: IT2Provider? = null

    private fun bindCaller(): IT2Caller {
        check(callerConnection == null) { "Caller already bound" }
        val connected = CountDownLatch(1)
        var failure: Throwable? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                caller = IT2Caller.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                caller = null
            }

            override fun onBindingDied(name: ComponentName) {
                caller = null
            }

            override fun onNullBinding(name: ComponentName) {
                failure = IllegalStateException("Caller returned a null binding")
                connected.countDown()
            }
        }
        callerConnection = connection

        check(
            context.bindService(
                Intent(context, T2CallerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        ) { "bindService(caller) failed" }
        check(connected.await(10, TimeUnit.SECONDS)) { "Timed out waiting for caller bind" }
        failure?.let { throw it }
        return checkNotNull(caller) { "Caller binder missing after connection" }
    }

    private fun unbindCaller() {
        val current = callerConnection ?: return
        runCatching { context.unbindService(current) }
        callerConnection = null
        caller = null
    }

    private fun bindProvider(): IT2Provider {
        check(providerConnection == null) { "Provider already bound" }
        val connected = CountDownLatch(1)
        var failure: Throwable? = null
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

        check(
            context.bindService(
                Intent(context, T2ProviderService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        ) { "bindService(provider) failed" }
        check(connected.await(10, TimeUnit.SECONDS)) { "Timed out waiting for provider bind" }
        failure?.let { throw it }
        return checkNotNull(provider) { "Provider binder missing after connection" }
    }

    private fun unbindProvider() {
        val current = providerConnection ?: return
        runCatching { context.unbindService(current) }
        providerConnection = null
        provider = null
    }
}
