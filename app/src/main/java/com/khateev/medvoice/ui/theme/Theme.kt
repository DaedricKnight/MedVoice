/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MedColorScheme = lightColorScheme(
    primary = Color(0xFF0D3B66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4F0),
    onPrimaryContainer = Color(0xFF0D3B66),
    secondary = Color(0xFF1B998B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCEEE9),
    onSecondaryContainer = Color(0xFF0B4A42),
    background = Color(0xFFF4F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EEF4),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC)
)

@Composable
fun MedVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MedColorScheme,
        content = content
    )
}
