/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp

data class IntentResult(
    val label: String,
    val confidence: Float,
    val secondLabel: String,
    val secondConfidence: Float
)

/**
 * On-device intent classifier: quantized BERT-Tiny (INT8) over 106 medical intents.
 * Fully offline; inference takes single-digit milliseconds on tablet CPUs.
 */
class MedIntentClassifier(private val context: Context) {

    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private val labels = mutableListOf<String>()
    private val env = OrtEnvironment.getEnvironment()

    companion object {
        private const val TAG = "MedIntentClassifier"
        private const val MODEL_FILE = "medical_intent.onnx"
        private const val VOCAB_FILE = "vocab.txt"
        private const val LABELS_FILE = "labels_medical.txt"
        const val MAX_SEQ_LEN = 32
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(context.cacheDir, MODEL_FILE)
            context.assets.open(MODEL_FILE).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
            session?.close()
            session = env.createSession(modelFile.absolutePath)

            val vocab = mutableMapOf<String, Int>()
            context.assets.open(VOCAB_FILE).bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, s -> vocab[s] = index }
            }
            tokenizer = WordPieceTokenizer(vocab, MAX_SEQ_LEN)

            labels.clear()
            context.assets.open(LABELS_FILE).bufferedReader().useLines { lines ->
                lines.forEach { if (it.isNotBlank()) labels.add(it.trim()) }
            }

            Log.d(TAG, "Initialized: ${labels.size} labels, vocab ${vocab.size}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun classify(text: String): IntentResult? = withContext(Dispatchers.Default) {
        val currentSession = session ?: return@withContext null
        val currentTokenizer = tokenizer ?: return@withContext null

        try {
            val inputIds = currentTokenizer.tokenize(text)
            val attentionMask = LongArray(MAX_SEQ_LEN) { if (inputIds[it] != 0L) 1L else 0L }

            val inputIdsTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(inputIds), longArrayOf(1, MAX_SEQ_LEN.toLong())
            )
            val maskTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(attentionMask), longArrayOf(1, MAX_SEQ_LEN.toLong())
            )

            currentSession.run(
                mapOf("input_ids" to inputIdsTensor, "attention_mask" to maskTensor)
            ).use { results ->
                @Suppress("UNCHECKED_CAST")
                val logits = (results[0].value as Array<FloatArray>)[0]
                val probs = softmax(logits)

                var best = 0
                var second = -1
                for (i in probs.indices) {
                    if (probs[i] > probs[best]) {
                        second = best
                        best = i
                    } else if (second == -1 || probs[i] > probs[second]) {
                        second = i
                    }
                }

                IntentResult(
                    label = labels.getOrElse(best) { "unknown" },
                    confidence = probs[best],
                    secondLabel = labels.getOrElse(second) { "unknown" },
                    secondConfidence = if (second >= 0) probs[second] else 0f
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference failure: ${e.message}", e)
            null
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(logits.size) { exps[it] / sum }
    }

    fun close() {
        try {
            session?.close()
            session = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session: ${e.message}")
        }
    }
}
