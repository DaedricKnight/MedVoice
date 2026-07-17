/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 (ONNX, ~2 MB): returns speech probability for consecutive
 * 512-sample chunks of 16 kHz audio. The model is stateful — it expects the
 * last 64 samples of the previous chunk prepended as context plus a recurrent
 * state tensor carried between calls.
 */
class SileroVad(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(context.assets.open("silero_vad.onnx").readBytes())

    private var state = FloatArray(2 * 1 * 128)
    private val contextTail = FloatArray(CONTEXT)

    companion object {
        const val CHUNK = 512
        private const val CONTEXT = 64
        private const val SR = 16_000L
    }

    fun reset() {
        state.fill(0f)
        contextTail.fill(0f)
    }

    /** @param chunk exactly [CHUNK] samples of 16 kHz mono PCM in [-1, 1]. */
    fun speechProbability(chunk: FloatArray): Float {
        require(chunk.size == CHUNK) { "expected $CHUNK samples, got ${chunk.size}" }

        val input = FloatArray(CONTEXT + CHUNK)
        contextTail.copyInto(input, 0)
        chunk.copyInto(input, CONTEXT)

        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + CHUNK).toLong())
        )
        val stateTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)
        )
        val srTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(SR)), longArrayOf()
        )

        return session.run(
            mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor)
        ).use { results ->
            @Suppress("UNCHECKED_CAST")
            val prob = (results[0].value as Array<FloatArray>)[0][0]

            @Suppress("UNCHECKED_CAST")
            val newState = results.get("stateN").get().value as Array<Array<FloatArray>>
            var i = 0
            for (a in newState) for (b in a) for (v in b) state[i++] = v

            chunk.copyInto(contextTail, 0, CHUNK - CONTEXT, CHUNK)
            prob
        }
    }

    fun close() = session.close()
}
