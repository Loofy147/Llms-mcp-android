package com.hicham.llmchat.runtime.t2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
    fun effectPersistedBeforeProviderDeathAndDuplicateRecoveryDoesNotReapply() {
        val operationId = "t2-${UUID.randomUUID()}"
        val first = bind()
        try {
            assertEquals(0, first.getEffectCount(operationId))
            try {
                first.execute(operationId, T2ProviderService.DIE_AFTER_EFFECT)
                fail("Provider process should die before returning")
            } catch (_: DeadObjectException) {
                // Expected transport ambiguity: provider persisted the effect and then died.
            }
        } finally {
            unbind()
        }

        val recovered = bind()
        try {
            assertEquals(T2OperationState.EFFECT_APPLIED.name, recovered.getState(operationId))
            assertEquals(1, recovered.getEffectCount(operationId))

            recovered.execute(operationId, T2ProviderService.NO_FAILURE)

            assertEquals(T2OperationState.COMPLETED.name, recovered.getState(operationId))
            assertEquals("Recovery must reconcile, not re-apply, the effect", 1, recovered.getEffectCount(operationId))
        } finally {
            unbind()
        }
    }

    @Test
    fun providerReceiptSurvivesDeathBeforeEffect() {
        val operationId = "t2-${UUID.randomUUID()}"
        val first = bind()
        try {
            try {
                first.execute(operationId, T2ProviderService.DIE_AFTER_RECEIVED)
                fail("Provider process should die before returning")
            } catch (_: DeadObjectException) {
                // Expected.
            }
        } finally {
            unbind()
        }

        val recovered = bind()
        try {
            assertEquals(T2OperationState.RECEIVED.name, recovered.getState(operationId))
            assertEquals(0, recovered.getEffectCount(operationId))

            recovered.execute(operationId, T2ProviderService.NO_FAILURE)

            assertEquals(T2OperationState.COMPLETED.name, recovered.getState(operationId))
            assertEquals(1, recovered.getEffectCount(operationId))
        } finally {
            unbind()
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
