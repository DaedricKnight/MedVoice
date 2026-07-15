/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.ml

/**
 * WordPiece tokenizer compatible with the BERT-Tiny vocab used to train medical_intent.onnx.
 * Mirrors the tokenization scheme of the LocalLLM app (lowercase, punctuation split,
 * greedy longest-match subword lookup with ## continuation prefix).
 */
class WordPieceTokenizer(private val vocab: Map<String, Int>, private val maxSeqLen: Int) {

    fun tokenize(text: String): LongArray {
        val resultIds = mutableListOf<Long>()
        resultIds.add(vocab["[CLS]"]?.toLong() ?: 101L)

        val initialWords = text.lowercase().trim().split(Regex("\\s+"))
        val tokens = mutableListOf<String>()

        for (word in initialWords) {
            var current = ""
            for (char in word) {
                if (char.isLetterOrDigit()) {
                    current += char
                } else {
                    if (current.isNotEmpty()) tokens.add(current)
                    tokens.add(char.toString())
                    current = ""
                }
            }
            if (current.isNotEmpty()) tokens.add(current)
        }

        for (token in tokens) {
            if (resultIds.size >= maxSeqLen - 1) break

            var remaining = token
            while (remaining.isNotEmpty()) {
                var subword = ""
                var end = remaining.length

                while (end > 0) {
                    val candidate = if (remaining == token) {
                        remaining.substring(0, end)
                    } else {
                        "##" + remaining.substring(0, end)
                    }

                    if (vocab.containsKey(candidate)) {
                        subword = candidate
                        break
                    }
                    end--
                }

                if (subword.isEmpty()) {
                    resultIds.add(vocab["[UNK]"]?.toLong() ?: 100L)
                    break
                } else {
                    resultIds.add(vocab[subword]!!.toLong())
                    remaining = remaining.substring(end)
                }
            }
        }

        if (resultIds.size < maxSeqLen) {
            resultIds.add(vocab["[SEP]"]?.toLong() ?: 102L)
        }

        val finalIds = LongArray(maxSeqLen) { 0L }
        for (i in 0 until resultIds.size.coerceAtMost(maxSeqLen)) {
            finalIds[i] = resultIds[i]
        }
        return finalIds
    }
}
