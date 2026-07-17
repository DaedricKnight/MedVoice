/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

/**
 * Boundary between the command-processing domain and the concrete NLU engine
 * (ONNX BERT today, anything tomorrow). Lets the domain be tested on the JVM
 * with fakes and the engine be swapped per customer hardware.
 */
interface IntentClassifierPort {
    /** @return best intent for [text], or null if the engine is not ready. */
    suspend fun classify(text: String): IntentResult?
}
