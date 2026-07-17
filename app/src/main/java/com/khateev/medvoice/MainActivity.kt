/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.khateev.medvoice.ui.PatientChartScreen
import com.khateev.medvoice.ui.theme.MedVoiceTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChartViewModel by viewModels()
    private var requestedMode = DictationMode.COMMAND

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startDictation(requestedMode)
        } else {
            viewModel.reportError("Microphone permission denied — use the text field instead")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without edge-to-edge, adjustResize consumes IME insets at the window
        // level and Compose never sees isImeVisible — the keyboard-aware layout
        // (hiding the vitals grid while typing) depends on it.
        enableEdgeToEdge()
        setContent {
            MedVoiceTheme {
                PatientChartScreen(
                    viewModel = viewModel,
                    onDictateClick = { mode -> toggleDictation(mode) }
                )
            }
        }
    }

    private fun toggleDictation(mode: DictationMode) {
        val state = viewModel.uiState.value
        if (state.listening && state.dictationMode == mode) {
            viewModel.stopDictation()
            return
        }
        requestedMode = mode
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> viewModel.startDictation(mode)
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
