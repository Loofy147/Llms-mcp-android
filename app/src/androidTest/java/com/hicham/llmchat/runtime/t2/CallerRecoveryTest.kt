package com.hicham.llmchat.runtime.t2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CallerRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun b4_callerDiesAfterProviderCompletion_reconcilesWithoutRedispatch() {
        val operationId = "b4-" + UUID.randomUUID()
        val initial = bindCaller()
        val firstProcess = initial.getProcessInstanceId()
        initial.reset()
        unbindCaller()

        val caller = bindCaller()
        try {
            expectCallerDeath {
                caller.startB4(operationId)
            }
        } finally {
            unbindCaller()
        }

        val recovered = bindCaller()
        try {
            val recoveredProcess = recovered.getProcessInstanceId()
            assertNotEquals("Caller must have restarted", firstProcess, recoveredProcess)

            val result = recovered.recover(operationId)
            assertTrue("caller=COMPLETED", result.contains("caller=COMPLETED"))
            assertTrue(result.contains("decision=CONFIRMED_COMPLETED"), result)
            assertTrue(result.contains("provider=COMPLETED"), result)
            assertTrue(result.contains("requests=1"), result)
            assertTrue(result.contains("effects=1"), result)
            assertTrue(result.contains("replay=false"), result)
        } finally {
            unbindCaller()
        }
    }

    @Test
    fun b5_callerDiesBeforeDispatch_reconcilesNotExecutedThenDispatchesOnce() {
        val operationId = "b5-" + UUID.randomUUID()
        val initial = bindCaller()
        val firstProcess = initial.getProcessInstanceId()
        initial.reset()
        unbindCaller()

        val caller = bindCaller()
        try {
            expectCallerDeath {
                caller.startB5(operationId)
            }
        } finally {
            unbindCaller()
        }

        val recovered = bindCaller()
        try {
            val recoveredProcess = recovered.getProcessInstanceId()
            assertNotEquals("Caller must have restarted", firstProcess, recoveredProcess)

            val result = recovered.recover(operationId)
            assertTrue("caller=COMPLETED", result.contains("caller=COMPLETED"))
            assertTrue(result.contains("decision=CONFIRMED_NOT_EXECUTED"), result)
            assertTrue(result.contains("provider=COMPLETED"), result)
            assertTrue(result.contains("requests=1"), result)
            assertTrue(result.contains("effects=1"), result)
            assertTrue(result.contains("replay=true"), result)
        } finally {
            unbindCaller()
        }
    }

    private fun expectCallerDeath(block: () -> Unit) {
        try {
            block()
            fail("Caller process should die at the injected fault point")
        } catch (_: RemoteException) {
            // Expected binder death from the caller process.
        }
    }

    private var connection: ServiceConnection? = null
    private var caller: IT2Caller? = null

    private fun bindCaller(): IT2Caller {
        check(connection == null) { "Already bound" }
        val connected = CountDownLatch(1)
        var failure: Throwable? = null

        val newConnection = object : ServiceConnection {
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
                failure = IllegalStateException("Caller service returned a null binding")
                connected.countDown()
            }
        }
        connection = newConnection

        val bound = context.bindService(
            Intent(context, T2CallerService::class.java),
            newConnection,
            Context.BIND_AUTO_CREATE,
        )
        check(bound) { "bindService() failed" }
        check(connected.await(10, TimeUnit.SECONDS)) {
            "Timed out waiting for caller bind"
        }
        failure?.let { throw it }
        return checkNotNull(caller) { "Caller binder missing after bind" }
    }

    private fun unbindCaller() {
        val current = connection ?: return
        runCatching { context.unbindService(current) }
        connection = null
        caller = null
    }
}
