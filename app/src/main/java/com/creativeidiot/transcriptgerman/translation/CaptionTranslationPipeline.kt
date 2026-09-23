package com.creativeidiot.transcriptgerman.translation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class CaptionTranslationPipeline(
    scope: CoroutineScope,
    private val translator: CaptionTranslator,
    private val onPartialTranslated: (sourceGerman: String, english: String) -> Unit,
    private val onFinalTranslated: (lineId: Long, english: String) -> Unit,
    private val onFailure: () -> Unit,
) {
    private sealed interface Command {
        data object PartialWake : Command
        data class Final(val lineId: Long, val german: String) : Command
        data object Finish : Command
    }

    private data class Partial(
        val german: String,
    )

    private val accepting = AtomicBoolean(true)
    private val failureSignalled = AtomicBoolean(false)
    private val partialWakeQueued = AtomicBoolean(false)
    private val pendingPartial = AtomicReference<Partial?>(null)
    private val commands = Channel<Command>(capacity = COMMAND_CAPACITY)

    private val worker: Job = scope.launch {
        try {
            for (command in commands) {
                when (command) {
                    Command.PartialWake -> {
                        partialWakeQueued.set(false)
                        pendingPartial.getAndSet(null)?.let(::translatePartial)
                    }

                    is Command.Final -> translateFinal(command)
                    Command.Finish -> break
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            signalFailure()
        } finally {
            translator.close()
        }
    }

    fun submitPartial(german: String): Boolean {
        if (!accepting.get()) return false

        val trimmed = german.trim()
        if (trimmed.isEmpty()) {
            pendingPartial.set(null)
            return true
        }

        pendingPartial.set(Partial(trimmed))
        if (partialWakeQueued.compareAndSet(false, true)) {
            if (!commands.trySend(Command.PartialWake).isSuccess) {
                partialWakeQueued.set(false)
                signalFailure()
                return false
            }
        }
        return true
    }

    fun submitFinal(
        lineId: Long,
        german: String,
    ): Boolean {
        if (!accepting.get()) return false
        pendingPartial.set(null)

        if (!commands.trySend(Command.Final(lineId, german)).isSuccess) {
            signalFailure()
            return false
        }
        return true
    }

    suspend fun finishAndDrain() {
        if (!accepting.compareAndSet(true, false)) {
            worker.join()
            return
        }

        pendingPartial.set(null)
        commands.send(Command.Finish)
        worker.join()
    }

    suspend fun cancel() {
        accepting.set(false)
        pendingPartial.set(null)
        worker.cancelAndJoin()
    }

    private suspend fun translatePartial(partial: Partial) {
        val english = translator.translateGermanToEnglish(partial.german)
        currentCoroutineContext().ensureActive()
        onPartialTranslated(partial.german, english)
    }

    private suspend fun translateFinal(command: Command.Final) {
        val english = translator.translateGermanToEnglish(command.german)
        currentCoroutineContext().ensureActive()
        onFinalTranslated(command.lineId, english)
    }

    private fun signalFailure() {
        accepting.set(false)
        if (failureSignalled.compareAndSet(false, true)) {
            onFailure()
        }
        worker.cancel()
    }

    private companion object {
        const val COMMAND_CAPACITY = 64
    }
}
