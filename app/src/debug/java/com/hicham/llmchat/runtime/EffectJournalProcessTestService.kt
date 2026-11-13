package com.hicham.llmchat.runtime

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Debug-only remote process fixture for literal process-death qualification.
 * It is declared only in src/debug/AndroidManifest.xml and is not packaged in release.
 */
class EffectJournalProcessTestService : Service() {
    companion object {
        const val CMD_PREPARE = 1
        const val CMD_BLOCK_BEFORE_APPEND = 2
        const val CMD_RESERVE_AND_WAIT_AFTER_APPEND = 3
        const val CMD_KILL_PROCESS = 4
        const val CMD_RECOVER = 5

        const val REPLY_PREPARED = 101
        const val REPLY_BEFORE_APPEND = 102
        const val REPLY_AFTER_APPEND = 103
        const val REPLY_RECOVERED = 104
        const val REPLY_ERROR = 199

        const val KEY = "effect_key"
        const val MODE = "recovery_mode"
    }

    private val processInstanceId = UUID.randomUUID().toString()
    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message -> handle(message) }
    )

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(message: Message): Boolean {
        val replyTo = message.replyTo ?: return true
        val key = message.data.getString(KEY) ?: run {
            send(replyTo, REPLY_ERROR, Bundle().apply { putString("error", "missing effect key") })
            return true
        }

        when (message.what) {
            CMD_PREPARE -> runCatching { prepare(key) }
                .onSuccess {
                    send(replyTo, REPLY_PREPARED, Bundle().apply {
                        putString("processInstanceId", processInstanceId)
                        putString("status", it)
                    })
                }
                .onFailure { sendError(replyTo, it) }

            CMD_BLOCK_BEFORE_APPEND -> runOnWorker("effect-retry-before-append") {
                val prior = invocation(key, 1)
                val retry = invocation(key, 2)
                val verifier = verifier(prior) {
                    send(replyTo, REPLY_BEFORE_APPEND, Bundle().apply {
                        putString("processInstanceId", processInstanceId)
                        putString("checkpoint", "trust-verified-before-E4-append")
                    })
                    awaitExternalKill()
                }
                val result = JournalRuntimeStore(journalFile(key))
                    .reserveEffects(listOf(retry), verifier)
                send(replyTo, REPLY_ERROR, Bundle().apply {
                    putString("error", "pre-append worker unexpectedly returned: $result")
                })
            }

            CMD_RESERVE_AND_WAIT_AFTER_APPEND -> runOnWorker("effect-retry-after-append") {
                val prior = invocation(key, 1)
                val retry = invocation(key, 2)
                val result = JournalRuntimeStore(journalFile(key))
                    .reserveEffects(listOf(retry), verifier(prior))
                check(result == EffectReservation.RESERVED) {
                    "Expected durable E4 reservation, got $result"
                }
                send(replyTo, REPLY_AFTER_APPEND, Bundle().apply {
                    putString("processInstanceId", processInstanceId)
                    putString("reserveResult", result.name)
                    putString("checkpoint", "E4-appended-and-forced-before-execution")
                })
                awaitExternalKill()
            }

            CMD_KILL_PROCESS -> Process.killProcess(Process.myPid())

            CMD_RECOVER -> runCatching { recover(key) }
                .onSuccess { recovered ->
                    send(replyTo, REPLY_RECOVERED, Bundle().apply {
                        putString("processInstanceId", processInstanceId)
                        putInt("recoveredCount", recovered.recoveredCount)
                        putString("status", recovered.status)
                        putString("retryResult", recovered.retryResult)
                        putInt("e4Count", recovered.e4Count)
                    })
                }
                .onFailure { sendError(replyTo, it) }
        }
        return true
    }

    private fun prepare(key: String): String {
        val file = journalFile(key)
        check(!file.exists() || file.delete()) { "could not reset test journal" }
        val effect = invocation(key, 1)
        val store = JournalRuntimeStore(file)
        check(store.reserveEffects(listOf(effect)) == EffectReservation.RESERVED)
        check(store.markEffectUnknown(effect.effectId) == EffectTransitionResult.APPLIED)
        check(
            store.reconcileEffect(
                effect.effectId,
                attestation(effect),
                verifier(effect)
            ) == EffectReconciliationResult.RECONCILED
        )
        return requireNotNull(store.currentEffect(effect.effectId)).status.name
    }

    private data class RecoveryResult(
        val recoveredCount: Int,
        val status: String,
        val retryResult: String,
        val e4Count: Int
    )

    private fun recover(key: String): RecoveryResult {
        val file = journalFile(key)
        val store = JournalRuntimeStore(file)
        val recovered = store.recoverInterruptedEffects()
        val current = requireNotNull(store.currentEffect(invocation(key, 1).effectId))
        val nextAttempt = invocation(key, current.attemptNumber + 1)
        val retryResult = store.reserveEffects(listOf(nextAttempt))
        val e4Count = file.readLines(StandardCharsets.UTF_8).count { it.startsWith("E4|") }
        return RecoveryResult(
            recoveredCount = recovered,
            status = current.status.name,
            retryResult = retryResult.name,
            e4Count = e4Count
        )
    }

    private fun journalFile(key: String): File {
        require(Regex("^[A-Za-z0-9_-]{1,80}$").matches(key)) { "invalid test key" }
        val directory = File(noBackupFilesDir, "effect-journal-process-death")
        check(directory.exists() || directory.mkdirs()) { "could not create test journal directory" }
        return File(directory, "$key.journal")
    }

    private fun invocation(key: String, attempt: Int) = CapabilityInvocation(
        runId = "run:$key",
        capabilityId = "test.effect",
        actionId = "reserve_once",
        actionVersion = 1,
        effectId = "effect:$key",
        scope = setOf("test:effect"),
        attributedTo = "principal:process-death-test",
        parameters = mapOf("fixture" to key, "payload" to "synthetic"),
        attemptId = "attempt-$attempt",
        attemptNumber = attempt
    )

    private fun attestation(effect: CapabilityInvocation): EffectReconciliationAttestation {
        val unsigned = EffectReconciliationAttestation(
            schema = "open_system_one.effect_reconciliation_attestation.v0.1",
            effectIdentity = EffectIdentityAttestation(
                schema = "open_system_one.effect_identity.v0.1",
                effectId = effect.effectId,
                runId = effect.runId,
                capabilityId = effect.capabilityId,
                actionId = effect.actionId,
                actionVersion = effect.actionVersion,
                scope = effect.scope,
                attributedTo = effect.attributedTo,
                parametersDigest = canonicalEffectParametersDigest(effect.parameters)
            ),
            attemptId = effect.attemptId,
            attemptNumber = effect.attemptNumber,
            decision = EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED,
            decidedAt = "2026-11-12T12:01:00Z",
            authority = EffectReconciliationAuthority(
                kind = "synthetic_fixture",
                ref = "authority:process-death-test",
                observationRef = "observation:${effect.effectId}",
                queriedAt = "2026-11-12T12:00:30Z",
                observedAt = "2026-11-12T12:00:31Z"
            ),
            evidence = EffectReconciliationEvidence(
                artifactRef = "artifact:process-death:${effect.effectId}",
                digest = "sha256:" + "b".repeat(64)
            ),
            verifier = EffectReconciliationVerifierEvidence(
                ref = "verifier:process-death-test",
                version = "1.0.0",
                result = "PASS",
                verifiedAt = "2026-11-12T12:00:50Z"
            ),
            accountablePrincipal = EffectAccountablePrincipal(
                kind = "service",
                ref = "service:process-death-test"
            ),
            authentication = EffectAttestationAuthentication(
                scheme = "ed25519",
                keyRef = "key:synthetic-process-death-test",
                signedDigest = "sha256:" + "e".repeat(64),
                signature = "synthetic-process-death-test-signature"
            ),
            reason = "Synthetic process-death fixture; not provider evidence."
        )
        return unsigned.copy(
            authentication = unsigned.authentication.copy(
                signedDigest = effectReconciliationAttestationSignaturePreimageDigest(unsigned)
            )
        )
    }

    private fun verifier(
        priorEffect: CapabilityInvocation,
        beforeReturning: (() -> Unit)? = null
    ) = object : ReconciliationTrustVerifier {
        override fun verify(
            attestation: EffectReconciliationAttestation,
            expectedEffect: EffectRecord
        ): TrustVerificationResult {
            beforeReturning?.invoke()
            val digest = effectReconciliationAttestationDigest(attestation)
            return TrustVerificationResult(
                attestationRef = "process-death-fixture:$digest",
                attestationDigest = digest,
                trustPolicyRef = "process-death-test-policy:v1",
                signatureValid = true,
                authorityApplicable = true,
                evidenceDigestVerified = true,
                verifierPolicyValid = true,
                principalAuthorized = true
            )
        }

        override fun resolve(
            attestationRef: String,
            attestationDigest: String
        ): EffectReconciliationAttestation? {
            val storedFixture = attestation(priorEffect)
            val digest = effectReconciliationAttestationDigest(storedFixture)
            return storedFixture.takeIf {
                attestationRef == "process-death-fixture:$digest" && attestationDigest == digest
            }
        }
    }

    private fun runOnWorker(name: String, block: () -> Unit) {
        Thread({
            runCatching(block).onFailure { error ->
                // Worker callbacks are delivered only to the requesting instrumentation client.
                // The caller's reply Messenger is captured by the worker's block.
                @Suppress("UNUSED_VARIABLE")
                val ignored = error
            }
        }, name).start()
    }

    private fun awaitExternalKill() {
        check(CountDownLatch(1).await(45, TimeUnit.SECONDS)) {
            "test controller did not kill the helper process at the requested checkpoint"
        }
    }

    private fun send(replyTo: Messenger, what: Int, data: Bundle) {
        val response = Message.obtain(null, what)
        response.arg1 = Process.myPid()
        response.data = data
        response.data.putString("processInstanceId", processInstanceId)
        replyTo.send(response)
    }

    private fun sendError(replyTo: Messenger, error: Throwable) {
        send(replyTo, REPLY_ERROR, Bundle().apply {
            putString("error", "${error::class.java.name}: ${error.message}")
        })
    }
}
