package com.hicham.llmchat.runtime.t2

import android.app.Service
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConcurrentRecoveryFailureInjectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun twoIndependentCallersRecoverSameOperationWithoutDuplicateEffect() {
        val operationId = "t2-b6-${UUID.randomUUID()}"

        // Establish provider-side RECEIVED with no effect, then force provider
        // process replacement exactly as B2 did.
        val initialProvider = stage("b6.bind.provider.initial") { bindProvider() }
        try {
            expectProviderDeath("b6.provider.dies_after_received") {
                initialProvider.execute(operationId, T2ProviderService.DIE_AFTER_RECEIVED)
            }
        } finally {
            stage("b6.unbind.provider.after_seed") { unbindProvider() }
        }

        val providerRecovered = stage("b6.bind.provider.recovered") { bindProvider() }
        try {
            stage("b6.provider.received") {
                assertEquals(T2OperationState.RECEIVED.name, providerRecovered.getState(operationId))
            }
            stage("b6.provider.effect_count.zero") {
                assertEquals(0, providerRecovered.getEffectCount(operationId))
            }
        } finally {
            stage("b6.unbind.provider.after_seed_verify") { unbindProvider() }
        }

        // Create the same durable caller intent in two independently killable
        // caller processes, then kill both before dispatch.
        seedCallerProcess(::T2CallerServiceA, operationId, "b6.callerA")
        seedCallerProcess(::T2CallerServiceB, operationId, "b6.callerB")

        val callerA = stage("b6.bind.callerA.recovered") { bindCaller(T2CallerServiceA::class.java) }
        val callerB = stage("b6.bind.callerB.recovered") { bindCaller(T2CallerServiceB::class.java) }
        try {
            val callerAInstance = stage("b6.callerA.instance") { callerA.getProcessInstanceId() }
            val callerBInstance = stage("b6.callerB.instance") { callerB.getProcessInstanceId() }
            stage("b6.distinct_caller_processes") {
                assertNotEquals(callerAInstance, callerBInstance)
            }

            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val pool: ExecutorService = Executors.newFixedThreadPool(2)
            try {
                val futureA = pool.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "caller A start barrier timed out" }
                    callerA.recover(operationId)
                }
                val futureB = pool.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "caller B start barrier timed out" }
                    callerB.recover(operationId)
                }

                check(ready.await(10, TimeUnit.SECONDS)) { "caller recovery threads failed to reach barrier" }
                start.countDown()

                stage("b6.callerA.recover") { futureA.get(20, TimeUnit.SECONDS) }
                stage("b6.callerB.recover") { futureB.get(20, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            stage("b6.callerA.completed") {
                assertEquals(T2CallerState.COMPLETED.name, callerA.getState(operationId))
            }
            stage("b6.callerB.completed") {
                assertEquals(T2CallerState.COMPLETED.name, callerB.getState(operationId))
            }
        } finally {
            stage("b6.unbind.callerA") { unbindCallerA() }
            stage("b6.unbind.callerB") { unbindCallerB() }
        }

        val provider = stage("b6.bind.provider.final") { bindProvider() }
        try {
            stage("b6.provider.completed") {
                assertEquals(T2OperationState.COMPLETED.name, provider.getState(operationId))
            }
            stage("b6.effect_count.exactly_one") {
                assertEquals(
                    "Concurrent recovery must not duplicate the non-repeatable effect",
                    1,
                    provider.getEffectCount(operationId),
                )
            }
        } finally {
            stage("b6.unbind.provider.final") { unbindProvider() }
        }
    }

    private fun seedCallerProcess(serviceClass: Class<out Service>, operationId: String, label: String) {
        val caller = stage("$label.bind.seed") { bindCaller(serviceClass) }
        try {
            stage("$label.seed") {
                expectCallerDeath {
                    caller.startOperation(operationId, T2CallerService.DIE_BEFORE_DISPATCH)
                }
            }
        } finally {
            stage("$label.unbind.seed") { unbindCaller(serviceClass) }
        }
    }

    private fun expectProviderDeath(block: () -> Unit) {
        try {
            block()
            fail("Provider process should die before returning")
        } catch (_: RemoteException) {
            // Expected Binder transport failure after provider process death.
        } catch (error: IllegalArgumentException) {
            assertEquals("Required value was null.", error.message)
        }
    }

    private fun expectCallerDeath(block: () -> Unit) {
        try {
            block()
            fail("Caller process should die before returning")
        } catch (_: RemoteException) {
            // Expected Binder transport failure after caller process death.
        }
    }

    private fun <T> stage(name: String, block: () -> T): T {
        return try {
            block()
        } catch (error: Throwable) {
            throw AssertionError(
                "T2_B6_STAGE[$name] failed: ${error::class.java.name}: ${error.message}",
                error,
            )
        }
    }

    private var callerAConnection: ServiceConnection? = null
    private var callerA: IT2Caller? = null
    private var callerBConnection: ServiceConnection? = null
    private var callerB: IT2Caller? = null
    private var providerConnection: ServiceConnection? = null
    private var provider: IT2Provider? = null

    private fun bindCaller(serviceClass: Class<out Service>): IT2Caller {
        val isA = serviceClass == T2CallerServiceA::class.java
        val existingConnection = if (isA) callerAConnection else callerBConnection
        check(existingConnection == null) { "Caller already bound for ${serviceClass.name}" }

        val connected = CountDownLatch(1)
        var failure: Throwable? = null
        var binder: IT2Caller? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = IT2Caller.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                binder = null
            }

            override fun onBindingDied(name: ComponentName) {
                binder = null
            }

            override fun onNullBinding(name: ComponentName) {
                failure = IllegalStateException("Caller returned a null binding")
                connected.countDown()
            }
        }

        if (isA) callerAConnection = connection else callerBConnection = connection

        check(
            context.bindService(
                Intent(context, serviceClass),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        ) { "bindService(${serviceClass.name}) failed" }
        check(connected.await(10, TimeUnit.SECONDS)) { "Timed out waiting for caller bind" }
        failure?.let { throw it }
        val connectedBinder = checkNotNull(binder) { "Caller binder missing after connection" }
        if (isA) callerA = connectedBinder else callerB = connectedBinder
        return connectedBinder
    }

    private fun unbindCaller(serviceClass: Class<out Service>) {
        val isA = serviceClass == T2CallerServiceA::class.java
        val connection = if (isA) callerAConnection else callerBConnection
        if (connection != null) runCatching { context.unbindService(connection) }
        if (isA) {
            callerAConnection = null
            callerA = null
        } else {
            callerBConnection = null
            callerB = null
        }
    }

    private fun unbindCallerA() = unbindCaller(T2CallerServiceA::class.java)
    private fun unbindCallerB() = unbindCaller(T2CallerServiceB::class.java)

    private fun bindProvider(): IT2Provider {
        check(providerConnection == null) { "Provider already bound" }
        val connected = CountDownLatch(1)
        var failure: Throwable? = null
        var binder: IT2Provider? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = IT2Provider.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                binder = null
            }

            override fun onBindingDied(name: ComponentName) {
                binder = null
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
        provider = checkNotNull(binder) { "Provider binder missing after connection" }
        return provider!!
    }

    private fun unbindProvider() {
        val connection = providerConnection ?: return
        runCatching { context.unbindService(connection) }
        providerConnection = null
        provider = null
    }
}
