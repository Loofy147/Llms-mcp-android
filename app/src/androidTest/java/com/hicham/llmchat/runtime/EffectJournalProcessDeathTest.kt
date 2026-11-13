package com.hicham.llmchat.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EffectJournalProcessDeathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class BoundService(
        val connection: ServiceConnection,
        val messenger: Messenger,
        val clientMessenger: Messenger,
        val responses: LinkedBlockingQueue<Bundle>,
        val disconnected: CountDownLatch
    )

    @Test
    fun processKilledBeforeE4AppendLeavesPriorStateAndRequiresFreshTrust() {
        val key = "before-${UUID.randomUUID()}"
        val first = bind()
        val firstInstance = try {
            send(first, EffectJournalProcessTestService.CMD_PREPARE, key)
            val prepared = await(first, EffectJournalProcessTestService.REPLY_PREPARED)
            assertEquals("CONFIRMED_NOT_EXECUTED", prepared.getString("status"))
            prepared.getString("processInstanceId")
        } finally {
            // Keep the helper process bound while the retry verification is suspended.
        }

        send(first, EffectJournalProcessTestService.CMD_BLOCK_BEFORE_APPEND, key)
        val checkpoint = await(first, EffectJournalProcessTestService.REPLY_BEFORE_APPEND)
        assertEquals(firstInstance, checkpoint.getString("processInstanceId"))
        assertEquals(
            "trust-verifier-result-ready-before-E4-append",
            checkpoint.getString("checkpoint")
        )
        send(first, EffectJournalProcessTestService.CMD_KILL_PROCESS, key)
        assertTrue(
            "helper process must actually disconnect after SIGKILL",
            first.disconnected.await(10, TimeUnit.SECONDS)
        )
        unbind(first)

        val recovered = bind()
        try {
            send(recovered, EffectJournalProcessTestService.CMD_RECOVER, key)
            val result = await(recovered, EffectJournalProcessTestService.REPLY_RECOVERED)
            assertNotEquals(firstInstance, result.getString("processInstanceId"))
            assertEquals(0, result.getInt("recoveredCount"))
            assertEquals("CONFIRMED_NOT_EXECUTED", result.getString("status"))
            assertEquals("TRUST_UNAVAILABLE", result.getString("retryResult"))
            assertEquals(0, result.getInt("e4Count"))
        } finally {
            unbind(recovered)
        }
    }

    @Test
    fun processKilledAfterForcedE4AppendRecoversUnknownAndBlocksBlindRetry() {
        val key = "after-${UUID.randomUUID()}"
        val first = bind()
        val firstInstance = try {
            send(first, EffectJournalProcessTestService.CMD_PREPARE, key)
            val prepared = await(first, EffectJournalProcessTestService.REPLY_PREPARED)
            assertEquals("CONFIRMED_NOT_EXECUTED", prepared.getString("status"))
            prepared.getString("processInstanceId")
        } finally {
            // Keep the helper process bound until after the reservation checkpoint.
        }

        send(first, EffectJournalProcessTestService.CMD_RESERVE_AND_WAIT_AFTER_APPEND, key)
        val checkpoint = await(first, EffectJournalProcessTestService.REPLY_AFTER_APPEND)
        assertEquals(firstInstance, checkpoint.getString("processInstanceId"))
        assertEquals("RESERVED", checkpoint.getString("reserveResult"))
        assertEquals(
            "E4-appended-and-forced-before-execution",
            checkpoint.getString("checkpoint")
        )
        send(first, EffectJournalProcessTestService.CMD_KILL_PROCESS, key)
        assertTrue(
            "helper process must actually disconnect after SIGKILL",
            first.disconnected.await(10, TimeUnit.SECONDS)
        )
        unbind(first)

        val recovered = bind()
        try {
            send(recovered, EffectJournalProcessTestService.CMD_RECOVER, key)
            val result = await(recovered, EffectJournalProcessTestService.REPLY_RECOVERED)
            assertNotEquals(firstInstance, result.getString("processInstanceId"))
            assertEquals(1, result.getInt("recoveredCount"))
            assertEquals("UNKNOWN", result.getString("status"))
            assertEquals("REPLAY_BLOCKED", result.getString("retryResult"))
            assertEquals(1, result.getInt("e4Count"))
        } finally {
            unbind(recovered)
        }
    }

    private fun bind(): BoundService {
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val responses = LinkedBlockingQueue<Bundle>()
        val clientMessenger = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val copy = Bundle(message.data)
                copy.putInt("_replyWhat", message.what)
                copy.putInt("_replyPid", message.arg1)
                responses.offer(copy)
                true
            }
        )
        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote = Messenger(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                disconnected.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                disconnected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                disconnected.countDown()
            }
        }
        val bound = context.bindService(
            Intent(context, EffectJournalProcessTestService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        assertTrue("bindService() failed", bound)
        assertTrue("timed out waiting for helper process", connected.await(10, TimeUnit.SECONDS))
        return BoundService(
            connection = connection,
            messenger = requireNotNull(remote),
            clientMessenger = clientMessenger,
            responses = responses,
            disconnected = disconnected
        )
    }

    private fun send(bound: BoundService, command: Int, key: String) {
        val message = Message.obtain(null, command)
        message.replyTo = bound.clientMessenger
        message.data = Bundle().apply {
            putString(EffectJournalProcessTestService.KEY, key)
        }
        bound.messenger.send(message)
    }

    private fun await(bound: BoundService, expectedWhat: Int): Bundle {
        val response = bound.responses.poll(15, TimeUnit.SECONDS)
            ?: throw AssertionError("timed out waiting for helper response $expectedWhat")
        assertEquals(expectedWhat, response.getInt("_replyWhat"))
        return response
    }

    private fun unbind(bound: BoundService) {
        runCatching { context.unbindService(bound.connection) }
    }
}
