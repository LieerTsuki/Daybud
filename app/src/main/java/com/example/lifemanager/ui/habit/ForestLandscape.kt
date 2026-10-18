package com.example.lifemanager.ui.habit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Continuous world art in logical coordinates, shared by every task's tree. */
internal fun DrawScope.drawForestLandscape(height: Float, dark: Boolean) {
    val ink = if (dark) Color(0xFFB5D9BE) else Color(0xFF497D5D)
    val sky = if (dark) Color(0xFF233D42) else Color(0xFFEAF4E9)
    val grass = if (dark) Color(0xFF365A45) else Color(0xFFDBECC5)
    drawRect(sky, size = Size(480f, height))
    drawCircle(if (dark) Color(0xFFE9DEC0) else Color(0xFFFFDF9A), 28f, Offset(386f, 58f))
    drawCircle(ink, 1.8f, Offset(379f, 56f)); drawCircle(ink, 1.8f, Offset(393f, 56f))
    drawArc(ink, 15f, 150f, false, Offset(380f, 57f), Size(12f, 8f), style = Stroke(1.4f, cap = StrokeCap.Round))
    listOf(Offset(52f, 51f), Offset(233f, 37f), Offset(442f, 114f)).forEach { p ->
        val cloud = if (dark) Color(0xFF6C8A7B) else Color(0xFFFFFDF2)
        drawOval(cloud, p, Size(68f, 17f)); drawCircle(cloud, 15f, p + Offset(24f, 2f))
        drawCircle(cloud, 11f, p + Offset(44f, 1f))
    }
    val farHill = Path().apply {
        moveTo(0f, 145f); cubicTo(80f, 72f, 130f, 105f, 213f, 139f)
        cubicTo(310f, 62f, 376f, 107f, 480f, 142f); lineTo(480f, height); lineTo(0f, height); close()
    }
    drawPath(farHill, if (dark) Color(0xFF486F58) else Color(0xFFB4D9B3))
    val middleHill = Path().apply {
        moveTo(0f, 153f); cubicTo(100f, 179f, 192f, 104f, 295f, 145f)
        cubicTo(376f, 193f, 416f, 128f, 480f, 157f); lineTo(480f, height); lineTo(0f, height); close()
    }
    drawPath(middleHill, if (dark) Color(0xFF3D634D) else Color(0xFFC7E2B9))
    val meadow = Path().apply {
        moveTo(0f, 202f); cubicTo(116f, 159f, 252f, 212f, 480f, 176f)
        lineTo(480f, height); lineTo(0f, height); close()
    }
    drawPath(meadow, Brush.verticalGradient(listOf(grass, if (dark) Color(0xFF294C3E) else Color(0xFFEAF0CE)), 180f, height))
    val trail = Path().apply {
        moveTo(467f, 174f); cubicTo(414f, 232f, 463f, 286f, 426f, 324f)
        cubicTo(371f, 384f, 472f, height - 83f, 379f, height + 20f)
    }
    drawPath(trail, if (dark) Color(0xFF6E775C) else Color(0xFFF2DDB6), style = Stroke(25f, cap = StrokeCap.Round))
    drawPath(trail, if (dark) Color(0xFF889171) else Color(0xFFFFEDCA), style = Stroke(17f, cap = StrokeCap.Round))
    // Small pond and stepping stones sit beside the planting area, never on individual tiles.
    drawOval(if (dark) Color(0xFF203F3D) else Color(0xFFBCD8AD), Offset(12f, height - 91f), Size(158f, 56f))
    drawOval(if (dark) Color(0xFF527F80) else Color(0xFFAADAD4), Offset(19f, height - 86f), Size(144f, 45f))
    drawArc(if (dark) Color(0xFFB0CEBE) else Color(0xFFF5FFF0), 5f, 130f, false,
        Offset(47f, height - 82f), Size(90f, 29f), style = Stroke(2f, cap = StrokeCap.Round))
    listOf(Offset(23f, height - 79f), Offset(154f, height - 60f), Offset(145f, height - 89f)).forEach { p ->
        drawOval(if (dark) Color(0xFF8F9D83) else Color(0xFFDDDCC4), p, Size(20f, 10f))
    }
    drawOval(if (dark) Color(0xFF85AD80) else Color(0xFF75B98D), Offset(48f, height - 68f), Size(23f, 10f))
    drawCircle(Color(0xFFF6B8A6), 4f, Offset(62f, height - 66f))
    val visibleRows = ((height - 170f) / 44f).toInt()
    repeat(visibleRows) { row ->
        repeat(6) { column ->
            val x = 24f + column * 78f + (row % 2) * 13f
            val y = 191f + row * 44f
            if (x < 404f && !(y > height - 100 && x < 180)) {
                drawLine(ink.copy(alpha = .28f), Offset(x, y), Offset(x - 3f, y - 6f), 1.3f, StrokeCap.Round)
                drawLine(ink.copy(alpha = .28f), Offset(x, y), Offset(x + 4f, y - 7f), 1.3f, StrokeCap.Round)
                if ((row + column) % 4 == 0) {
                    val fill = if ((row + column) % 2 == 0) Color(0xFFFFBDAB) else Color(0xFFFFE9AA)
                    listOf(Offset(-3f, 0f), Offset(3f, 0f), Offset(0f, -3f), Offset(0f, 3f)).forEach {
                        drawCircle(fill, 2.8f, Offset(x + 10f, y - 3f) + it)
                    }
                    drawCircle(Color(0xFFFFF8E6), 1.9f, Offset(x + 10f, y - 3f))
                }
            }
        }
    }
    // A tiny wooden sign and bird keep the same rounded storybook language as the app icon.
    drawLine(ink, Offset(36f, 163f), Offset(36f, 193f), 4f, StrokeCap.Round)
    drawRoundRect(if (dark) Color(0xFFAA9E7B) else Color(0xFFE9CFA5), Offset(18f, 147f), Size(37f, 22f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f))
    drawLine(ink, Offset(29f, 158f), Offset(44f, 158f), 2f, StrokeCap.Round)
    drawLine(ink, Offset(39f, 153f), Offset(44f, 158f), 2f, StrokeCap.Round)
    drawLine(ink, Offset(39f, 163f), Offset(44f, 158f), 2f, StrokeCap.Round)
    drawLine(ink.copy(alpha = .6f), Offset(167f, 70f), Offset(173f, 74f), 1.6f, StrokeCap.Round)
    drawLine(ink.copy(alpha = .6f), Offset(173f, 74f), Offset(179f, 70f), 1.6f, StrokeCap.Round)
}
