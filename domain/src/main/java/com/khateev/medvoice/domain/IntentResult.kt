/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

data class IntentResult(
    val label: String,
    val confidence: Float,
    val secondLabel: String,
    val secondConfidence: Float
)
