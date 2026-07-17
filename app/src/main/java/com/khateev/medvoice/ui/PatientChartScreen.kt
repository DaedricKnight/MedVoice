/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.khateev.medvoice.ChartUiState
import com.khateev.medvoice.ChartViewModel
import com.khateev.medvoice.DictationMode
import com.khateev.medvoice.HistoryPoint
import com.khateev.medvoice.LogEntry
import com.khateev.medvoice.LogKind
import com.khateev.medvoice.News2
import com.khateev.medvoice.NoteSection
import com.khateev.medvoice.VitalType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatientChartScreen(
    viewModel: ChartViewModel,
    onDictateClick: (DictationMode) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val isPortrait =
        LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    // With the keyboard open the screen shrinks to ~40%: hide the grid and the
    // note so the command log and the input field stay visible while typing.
    val imeVisible = WindowInsets.isImeVisible

    if (isPortrait) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(12.dp)
        ) {
            PatientHeader(state, compact = true, onClick = { viewModel.openPatientPicker() })
            Spacer(Modifier.height(10.dp))
            if (!imeVisible) {
                News2Card(state.news2)
                Spacer(Modifier.height(10.dp))
                VitalsGrid(state, columns = 2, onTileClick = { viewModel.openHistory(it) }, modifier = Modifier.weight(0.55f))
                Spacer(Modifier.height(10.dp))
            }
            CommandPanel(
                state, viewModel, onDictateClick,
                showNote = !imeVisible,
                modifier = Modifier.weight(if (imeVisible) 1f else 0.45f)
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(0.62f)) {
                PatientHeader(state, compact = false, onClick = { viewModel.openPatientPicker() })
                Spacer(Modifier.height(12.dp))
                News2Card(state.news2)
                Spacer(Modifier.height(12.dp))
                VitalsGrid(state, columns = 3, onTileClick = { viewModel.openHistory(it) }, modifier = Modifier.fillMaxSize())
            }
            CommandPanel(
                state, viewModel, onDictateClick,
                showNote = !imeVisible,
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
            )
        }
    }

    if (state.showPatientPicker) {
        PatientPickerDialog(state, viewModel)
    }

    if (state.showAddPatient) {
        AddPatientDialog(viewModel)
    }

    state.historyType?.let { type ->
        HistorySheet(type, state.historyPoints, onDismiss = { viewModel.closeHistory() })
    }

    state.pendingAction?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingAction() },
            title = { Text("Confirm action") },
            text = {
                Column {
                    Text(pending.description, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Heard: \"${pending.rawText}\"", color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "🎤 say \"yes, confirm\" or \"negative\"",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingAction() }) {
                    Text("CONFIRM", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingAction() }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PatientHeader(state: ChartUiState, compact: Boolean, onClick: () -> Unit) {
    val patient = state.currentPatient
    val initials = patient?.name
        ?.split(" ")
        ?.mapNotNull { it.firstOrNull()?.uppercase() }
        ?.take(2)
        ?.joinToString("") ?: "…"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!compact) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    patient?.name ?: "Loading…",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 17.sp else 20.sp
                )
                Text(
                    if (patient != null) "MRN ${patient.mrn}  ·  ${patient.ward}  ·  tap to switch"
                    else "",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = if (compact) 12.sp else 14.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (state.modelReady) "● Edge AI ready" else "○ loading model…",
                    color = if (state.modelReady) Color(0xFF7CFC9B) else Color.White.copy(alpha = 0.6f),
                    fontSize = if (compact) 12.sp else 13.sp
                )
                if (state.lastInferenceMs > 0) {
                    Text(
                        "last inference: ${state.lastInferenceMs} ms",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = if (compact) 11.sp else 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PatientPickerDialog(state: ChartUiState, viewModel: ChartViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissPatientPicker() },
        title = { Text("Select patient") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.patients) { patient ->
                    val selected = patient.id == state.currentPatient?.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectPatient(patient.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected)
                                MaterialTheme.colorScheme.secondaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    patient.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected) {
                                    Text("✓", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                "MRN ${patient.mrn}  ·  ${patient.ward}",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.dismissPatientPicker() }) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.openAddPatient() }) {
                Text("+ Add patient", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun AddPatientDialog(viewModel: ChartViewModel) {
    var name by remember { mutableStateOf("") }
    var mrn by remember { mutableStateOf("") }
    var ward by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { viewModel.dismissAddPatient() },
        title = { Text("New patient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mrn,
                    onValueChange = { mrn = it },
                    label = { Text("MRN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ward,
                    onValueChange = { ward = it },
                    label = { Text("Ward / Room") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.addPatient(name, mrn, ward) },
                enabled = name.isNotBlank()
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissAddPatient() }) { Text("Cancel") }
        }
    )
}

@Composable
private fun VitalsGrid(
    state: ChartUiState,
    columns: Int,
    onTileClick: (VitalType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            "Vitals & Symptoms",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(VitalType.entries) { type ->
                val vital = state.vitals[type]
                val critical = vital?.critical == true
                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.clickable { onTileClick(type) },
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            critical -> MaterialTheme.colorScheme.errorContainer
                            vital != null -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            if (critical) "${type.display} ⚠" else type.display,
                            fontSize = 13.sp,
                            color = if (critical) MaterialTheme.colorScheme.error else Color.Gray
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            vital?.value ?: "—",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                critical -> MaterialTheme.colorScheme.error
                                vital != null -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> Color.LightGray
                            }
                        )
                        Text(
                            if (vital != null) "${type.unit} · ${vital.time}" else type.unit,
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandPanel(
    state: ChartUiState,
    viewModel: ChartViewModel,
    onDictateClick: (DictationMode) -> Unit,
    showNote: Boolean = true,
    modifier: Modifier = Modifier
) {
    var typed by remember { mutableStateOf("") }
    val noteActive = state.listening && state.dictationMode == DictationMode.NOTE
    val commandActive = state.listening && state.dictationMode == DictationMode.COMMAND

    Card(modifier = modifier) {
        Column(modifier = Modifier
            .padding(12.dp)
            .fillMaxSize()) {

            if (showNote) {
                NoteCard(
                    state, noteActive,
                    onClear = { viewModel.clearNote() },
                    onSectionClick = { viewModel.setNoteSection(it) }
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Voice Commands",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.toggleAccuracyMode() }, enabled = !state.switchingModel) {
                    Text(
                        when {
                            state.switchingModel -> "…"
                            state.accuracyMode -> "🎯"
                            else -> "⚡"
                        },
                        fontSize = 16.sp
                    )
                }
                TextButton(onClick = { viewModel.toggleTts() }) {
                    Text(if (state.ttsMuted) "🔇" else "🔊", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.actions) { entry -> LogRow(entry) }
            }

            if (state.partialText.isNotBlank()) {
                Text(
                    "“${state.partialText}”",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a command", fontSize = 14.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        viewModel.onCommand(typed)
                        typed = ""
                    })
                )
                Spacer(Modifier.width(8.dp))
                DictateButton(
                    active = noteActive,
                    idleColor = MaterialTheme.colorScheme.primary,
                    label = "📝",
                    onClick = { onDictateClick(DictationMode.NOTE) }
                )
                Spacer(Modifier.width(8.dp))
                DictateButton(
                    active = commandActive,
                    idleColor = MaterialTheme.colorScheme.secondary,
                    label = "🎤",
                    onClick = { onDictateClick(DictationMode.COMMAND) }
                )
            }
        }
    }
}

@Composable
private fun DictateButton(
    active: Boolean,
    idleColor: Color,
    label: String,
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (active) MaterialTheme.colorScheme.error else idleColor
    ) {
        if (active) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text(label, fontSize = 20.sp)
        }
    }
}

@Composable
private fun NoteCard(
    state: ChartUiState,
    dictating: Boolean,
    onClear: () -> Unit,
    onSectionClick: (NoteSection) -> Unit
) {
    val text = state.notes[state.activeSection] ?: ""
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (dictating)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (dictating) "Note · ${state.activeSection.display} — dictating…"
                    else "Note · ${state.activeSection.display}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                if (text.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear", fontSize = 12.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NoteSection.entries.forEach { section ->
                    val active = section == state.activeSection
                    val filled = !state.notes[section].isNullOrEmpty()
                    Box(
                        modifier = Modifier
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSectionClick(section) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            // Dot marks sections that already have content
                            if (filled && !active) "${section.display.first()}·" else "${section.display.first()}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "🎤 “go to plan”",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text.ifEmpty { "Tap 📝 and dictate — free text goes to the ${state.activeSection.display} section." },
                fontSize = 14.sp,
                color = if (text.isEmpty()) Color.Gray
                else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .heightIn(min = 36.dp, max = 120.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun News2Card(assessment: News2.Assessment?) {
    val (container, content, label) = when (assessment?.risk) {
        null -> Triple(MaterialTheme.colorScheme.surface, Color.Gray, "no vitals yet")
        News2.Risk.LOW -> Triple(MaterialTheme.colorScheme.secondaryContainer, Color(0xFF1B998B), "low risk")
        News2.Risk.LOW_MEDIUM -> Triple(Color(0xFFFFF3D6), Color(0xFFB07A00), "single red score — review")
        News2.Risk.MEDIUM -> Triple(Color(0xFFFFE3C2), Color(0xFFB35B00), "urgent review")
        News2.Risk.HIGH -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "emergency response")
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NEWS2", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = content)
            Spacer(Modifier.width(12.dp))
            Text(
                assessment?.score?.toString() ?: "—",
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = content
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = content)
                Text(
                    if (assessment != null)
                        "early warning score · ${assessment.components}/5 vitals on chart"
                    else "early warning score",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(type: VitalType, points: List<HistoryPoint>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                "${type.display} — history",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${points.size} measurement(s), ${type.unit}",
                fontSize = 13.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(12.dp))

            if (points.count { it.primary != null } >= 2) {
                TrendChart(points)
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    "Not enough data for a trend — record at least two values.",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.height(12.dp))
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(points.asReversed()) { point ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (point.critical) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (point.critical) "${point.display} ⚠" else point.display,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (point.critical) MaterialTheme.colorScheme.error else Color.Unspecified,
                            modifier = Modifier.weight(1f)
                        )
                        Text(point.time, fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TrendChart(points: List<HistoryPoint>) {
    val primaryColor = MaterialTheme.colorScheme.secondary
    val secondaryColor = MaterialTheme.colorScheme.primary
    val gridColor = Color.LightGray.copy(alpha = 0.4f)

    val primarySeries = points.mapNotNull { it.primary }
    val secondarySeries = points.mapNotNull { it.secondary }
    val allValues = primarySeries + secondarySeries
    val min = allValues.min()
    val max = allValues.max()
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0

    Column {
        Text(
            "max ${trim(max)} · min ${trim(min)}",
            fontSize = 12.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val w = size.width
            val h = size.height
            val padY = h * 0.12f

            for (frac in listOf(0f, 0.5f, 1f)) {
                val y = padY + (h - 2 * padY) * frac
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 2f)
            }

            fun drawSeries(series: List<Double?>, color: Color) {
                val pts = series.mapIndexedNotNull { i, v ->
                    v ?: return@mapIndexedNotNull null
                    val x = if (series.size == 1) w / 2 else w * i / (series.size - 1).toFloat()
                    val y = padY + ((max - v) / span * (h - 2 * padY)).toFloat()
                    Offset(x, y)
                }
                for (i in 1 until pts.size) {
                    drawLine(color, pts[i - 1], pts[i], strokeWidth = 5f, cap = StrokeCap.Round)
                }
                pts.forEach { drawCircle(color, radius = 8f, center = it) }
            }

            drawSeries(points.map { it.primary }, primaryColor)
            if (secondarySeries.isNotEmpty()) {
                drawSeries(points.map { it.secondary }, secondaryColor)
            }
        }
    }
}

private fun trim(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

@Composable
private fun LogRow(entry: LogEntry) {
    val (badge, color) = when (entry.kind) {
        LogKind.OK -> "✓" to Color(0xFF1B998B)
        LogKind.WARN -> "?" to Color(0xFFDB8A00)
        LogKind.ERROR -> "✕" to MaterialTheme.colorScheme.error
        LogKind.INFO -> "·" to Color.Gray
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(badge, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text(entry.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        Row {
            Spacer(Modifier.width(23.dp))
            Text(
                buildString {
                    append(entry.time)
                    entry.intent?.let { append("  ·  $it") }
                    entry.confidence?.let { append("  ·  ${"%.0f".format(it * 100)}%") }
                },
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}
