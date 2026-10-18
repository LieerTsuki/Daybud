package com.example.lifemanager.ui.habit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lifemanager.domain.maintenance.DataGeneration
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.roundToInt

private const val WorldWidth = 480f

/** Placement depends on stable tree identity order, never growth, selection or the calendar filter. */
private fun treeLocation(index: Int, rowStride: Float): Offset {
    val row = index / 3
    val column = index % 3
    return Offset(88f + column * 152f + if (row % 2 == 0) -5f else 5f,
        224f + row * rowStride + listOf(8f, -8f, 3f)[column])
}

private fun boundedOffset(offset: Offset, width: Float, height: Float, worldHeight: Float, scale: Float): Offset {
    fun axis(value: Float, viewport: Float, content: Float) =
        if (content <= viewport) (viewport - content) / 2f else value.coerceIn(viewport - content, 0f)
    return Offset(axis(offset.x, width, WorldWidth * scale), axis(offset.y, height, worldHeight * scale))
}

@Composable
internal fun ForestScene(entries: List<ForestPlanting>, selectedId: Long?, focusRequest: Pair<Long, Int>?, enabled: Boolean,
    generation: DataGeneration?, onSelect: (Long) -> Unit) {
    val density = LocalDensity.current.density
    val fontScale = LocalDensity.current.fontScale
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current.copy(fontSize = 11.sp, lineHeight = 14.sp)
    val names = remember(entries) { entries.map { it.habitName }.distinct() }
    val labelHeight = remember(names, density, fontScale, labelStyle) {
        (names.maxOfOrNull { textMeasurer.measure(it, labelStyle, maxLines = 1).size.height } ?: 16) / density + 4f
    }
    val dark = MaterialTheme.colorScheme.background.red < .5f
    var zoom by remember(generation) { mutableFloatStateOf(1f) }
    var pan by remember(generation) { mutableStateOf(Offset.Zero) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(410.dp).clip(RoundedCornerShape(28.dp))
            .background(if (dark) Color(0xFF213C37) else Color(0xFFF1F7EA)).testTag("shared_forest")
            .semantics { contentDescription = "所有任务共同生长的森林"; stateDescription = "${entries.size}棵树，缩放${(zoom * 100).roundToInt()}%" }) {
            val width = maxWidth.value
            val height = maxHeight.value
            val baseScale = width / WorldWidth
            val rowStride = 112f + maxOf(26f, (labelHeight + 8f) / baseScale)
            val worldHeight = maxOf(560f, 160f + ceil(entries.size / 3f) * rowStride)
            val sceneScale = baseScale * zoom
            val offset = boundedOffset(pan, width, height, worldHeight, sceneScale)
            fun changeZoom(newZoom: Float, center: Offset = Offset(width / 2f, height / 2f), delta: Offset = Offset.Zero) {
                val next = newZoom.coerceIn(1f, 2.6f)
                val ratio = next / zoom
                pan = boundedOffset(center - (center - offset) * ratio + delta, width, height, worldHeight, baseScale * next)
                zoom = next
            }
            LaunchedEffect(focusRequest, width, height, worldHeight) {
                val index = entries.indexOfFirst { it.tree.id == focusRequest?.first }
                if (index >= 0) {
                    val target = treeLocation(index, rowStride)
                    pan = boundedOffset(Offset(width / 2f, height / 2f) - target * sceneScale,
                        width, height, worldHeight, sceneScale)
                }
            }
            Box(Modifier.fillMaxSize().pointerInput(generation, enabled, density, fontScale, width, height, worldHeight) {
                if (enabled) awaitEachGesture {
                    // Initial pass lets a pinch starting over two clickable trees take precedence.
                    // A single tap stays unconsumed and retains native click/accessibility behavior.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var totalZoom = 1f
                    var totalPan = Offset.Zero
                    var pastSlop = false
                    var multiplePointers = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val factor = event.calculateZoom()
                        val movement = event.calculatePan()
                        totalZoom *= factor
                        totalPan += movement
                        multiplePointers = multiplePointers || event.changes.count { it.pressed } > 1
                        pastSlop = pastSlop || totalPan.getDistance() > viewConfiguration.touchSlop ||
                            abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false) > viewConfiguration.touchSlop
                        // Do not use an unspecified centroid on the last pointer-up event.
                        if (pastSlop && (factor != 1f || movement != Offset.Zero)) {
                            val currentOffset = boundedOffset(pan, width, height, worldHeight, baseScale * zoom)
                            val next = (zoom * factor).coerceIn(1f, 2.6f)
                            val pivot = event.calculateCentroid(useCurrent = false) / density
                            pan = boundedOffset(pivot - (pivot - currentOffset) * (next / zoom) + movement / density,
                                width, height, worldHeight, baseScale * next)
                            zoom = next
                        }
                        if (pastSlop || multiplePointers) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }) {
                Canvas(Modifier.fillMaxSize()) {
                    translate(offset.x * density, offset.y * density) {
                        scale(sceneScale * density, sceneScale * density, Offset.Zero) { drawForestLandscape(worldHeight, dark) }
                    }
                }
                entries.forEachIndexed { index, entry ->
                    val point = treeLocation(index, rowStride) * sceneScale + offset
                    val left = point.x - 64f * sceneScale
                    val top = point.y - 96f * sceneScale
                    val side = 128f * sceneScale
                    val treeHeight = 112f * sceneScale + labelHeight
                    // Compose only visible trees; every tree remains reachable through the locator.
                    if (left + side >= 0 && left <= width && top + treeHeight >= 0 && top <= height) {
                        key(entry.tree.id) {
                            val growth by animateFloatAsState(if (entry.tree.isMature) 1.04f else 1f, tween(650), label = "forest_growth")
                            val selected = entry.tree.id == selectedId
                            Column(Modifier.offset(left.dp, top.dp).size(side.dp, treeHeight.dp)
                                .clickable(enabled = enabled, role = Role.Button) { onSelect(entry.tree.id) }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "选择树木：${entry.identity}"
                                    stateDescription = "${entry.tree.species.label}，${growthLabel(entry.tree.wateringCount)}，${entry.tree.wateringCount}/20"
                                }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size((112f * sceneScale).dp), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawOval(if (selected) Color(0xFF6BB99A) else Color(0xFF316D4E).copy(alpha = .14f),
                                            Offset(size.width * .17f, size.height * .80f), Size(size.width * .66f, size.height * .12f),
                                            style = if (selected) Stroke(2.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill)
                                    }
                                    TreeIllustration(entry.tree.species, entry.tree.wateringCount,
                                        Modifier.size((112f * sceneScale * growth).dp), inLandscape = true)
                                }
                                Text(entry.habitName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = labelStyle,
                                    color = if (dark) Color(0xFFF4F0DA) else Color(0xFF285B48),
                                    modifier = Modifier.background(if (dark) Color(0xAA294D40) else Color(0xBFF9F9E8), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 7.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
                if (entries.isEmpty()) {
                    Text("你的森林，从一颗种子开始", style = MaterialTheme.typography.titleMedium,
                        color = if (dark) Color(0xFFDAEBDD) else Color(0xFF38735A),
                        modifier = Modifier.align(Alignment.Center).padding(20.dp))
                }
            }
            Surface(Modifier.align(Alignment.TopStart).padding(16.dp), shape = RoundedCornerShape(16.dp),
                color = if (dark) Color(0xDD294D40) else Color(0xDFFFFBED)) {
                Text("日子芽森林", Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CameraButton("−", "缩小森林", enabled && zoom > 1f) { changeZoom(zoom / 1.3f) }
                CameraButton("＋", "放大森林", enabled && zoom < 2.6f) { changeZoom(zoom * 1.3f) }
                CameraButton("复位", "复位森林视角", enabled) { zoom = 1f; pan = Offset.Zero }
            }
        }
    }
}

@Composable
private fun CameraButton(label: String, description: String, enabled: Boolean, action: () -> Unit) {
    FilledTonalButton(onClick = action, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { contentDescription = description }) {
        Text(label)
    }
}

/** Finite, commit-driven animation; never displays success just because a button was pressed. */
@Composable
internal fun WateringSparkles(amount: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (amount <= 0f) return@Canvas
        repeat(5) { index ->
            val x = size.width * (.18f + index * .16f)
            val y = size.height * (.1f + (1f - amount) * .65f + (index % 2) * .06f)
            drawCircle(Color(0xFF69BFC9).copy(alpha = amount), 3.dp.toPx(), Offset(x, y))
            drawLine(Color(0xFFD8F7ED).copy(alpha = amount), Offset(x, y - 7.dp.toPx()), Offset(x, y), 2.dp.toPx())
        }
    }
}
