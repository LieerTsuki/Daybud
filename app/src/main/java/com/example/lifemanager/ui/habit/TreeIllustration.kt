package com.example.lifemanager.ui.habit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.lifemanager.domain.model.TreeSpecies

internal fun growthLabel(waterings: Int): String = when {
    waterings >= 20 -> "成树"
    waterings >= 10 -> "小树"
    waterings >= 5 -> "幼树"
    waterings >= 1 -> "萌芽"
    else -> "种子"
}

/** Native, density-independent artwork using the same ink, mint and coral as the launcher. */
@Composable
internal fun TreeIllustration(species: TreeSpecies, waterings: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "${species.label}，${growthLabel(waterings)}，已浇水${waterings}次" }) {
        val side = minOf(size.width, size.height)
        translate((size.width - side) / 2, (size.height - side) / 2) {
            scale(side / 100f, side / 100f, Offset.Zero) { drawTree(species, waterings) }
        }
    }
}

private val ForestInk = Color(0xFF246956)
private val ForestMint = Color(0xFFBDE9D4)
private val ForestLeaf = Color(0xFF80C59D)
private val ForestCoral = Color(0xFFFFB69F)
private val ForestCream = Color(0xFFFFF8ED)

private fun DrawScope.drawTree(species: TreeSpecies, waterings: Int) {
    drawCircle(ForestCream, 46f, Offset(50f, 50f))
    drawOval(ForestMint, Offset(17f, 83f), Size(66f, 8f))
    drawLine(ForestInk.copy(alpha = .45f), Offset(28f, 87f), Offset(72f, 87f), 1.3f, StrokeCap.Round)
    if (waterings <= 0) {
        drawSeed(species)
        drawCircle(ForestLeaf, 2f, Offset(33f, 79f))
        return
    }
    if (waterings < 5) {
        drawLine(ForestInk, Offset(50f, 85f), Offset(50f, 57f), 2.8f, StrokeCap.Round)
        when (species) {
            TreeSpecies.PINE -> {
                listOf(55f, 63f, 71f).forEach { y ->
                    drawLine(ForestInk, Offset(50f, y + 5f), Offset(37f, y - 4f), 2.4f, StrokeCap.Round)
                    drawLine(ForestInk, Offset(50f, y + 5f), Offset(63f, y - 4f), 2.4f, StrokeCap.Round)
                }
            }
            TreeSpecies.GINKGO -> {
                canopy(Path().apply {
                    moveTo(50f, 65f); cubicTo(19f, 56f, 27f, 35f, 46f, 43f)
                    lineTo(50f, 49f); lineTo(54f, 43f)
                    cubicTo(73f, 35f, 81f, 56f, 50f, 65f); close()
                }, Color(0xFFF6D878))
            }
            TreeSpecies.CHERRY -> {
                leaf(50f, 68f, -17f, -15f, ForestCoral)
                leaf(50f, 60f, 17f, -16f, ForestCoral)
                drawCircle(ForestCream, 3f, Offset(64f, 48f))
            }
            TreeSpecies.MAPLE -> {
                canopy(path(50f to 42f, 55f to 51f, 65f to 48f, 60f to 58f, 69f to 61f,
                    54f to 68f, 50f to 74f, 46f to 68f, 31f to 61f, 40f to 58f, 35f to 48f, 45f to 51f), ForestCoral)
            }
            TreeSpecies.OAK -> {
                canopy(Path().apply {
                    moveTo(50f, 67f); cubicTo(22f, 69f, 26f, 56f, 33f, 54f)
                    cubicTo(25f, 43f, 41f, 39f, 50f, 54f)
                    cubicTo(61f, 34f, 78f, 46f, 66f, 56f)
                    cubicTo(78f, 66f, 60f, 70f, 50f, 67f); close()
                }, ForestLeaf)
            }
            TreeSpecies.BIRCH -> {
                leaf(50f, 68f, -11f, -24f, ForestMint)
                leaf(50f, 63f, 11f, -26f, ForestLeaf)
                drawLine(ForestCream, Offset(50f, 75f), Offset(50f, 65f), 1.4f)
            }
        }
        return
    }
    val factor = when { waterings >= 20 -> 1f; waterings >= 10 -> .82f; else -> .63f }
    scale(factor, factor, Offset(50f, 86f)) {
        // The slight fork and rounded trunk tie each silhouette to the same illustration family.
        drawLine(ForestInk, Offset(50f, 86f), Offset(50f, 37f), 5f, StrokeCap.Round)
        when (species) {
            TreeSpecies.PINE -> {
                canopy(path(50f to 13f, 30f to 44f, 39f to 44f, 22f to 63f, 34f to 63f, 17f to 77f, 83f to 77f, 66f to 63f, 78f to 63f, 61f to 44f, 70f to 44f), ForestLeaf)
                drawLine(ForestInk.copy(alpha = .5f), Offset(39f, 58f), Offset(61f, 58f), 1.3f, StrokeCap.Round)
            }
            TreeSpecies.GINKGO -> {
                listOf(Offset(50f, 45f), Offset(30f, 57f), Offset(70f, 57f)).forEach { fan ->
                    val fanPath = Path().apply {
                        moveTo(fan.x, fan.y + 12f)
                        cubicTo(fan.x - 36f, fan.y - 10f, fan.x - 25f, fan.y - 36f, fan.x - 3f, fan.y - 28f)
                        lineTo(fan.x, fan.y - 21f); lineTo(fan.x + 3f, fan.y - 28f)
                        cubicTo(fan.x + 25f, fan.y - 36f, fan.x + 36f, fan.y - 10f, fan.x, fan.y + 12f)
                        close()
                    }
                    canopy(fanPath, Color(0xFFF6D878))
                    drawLine(ForestInk.copy(alpha = .5f), Offset(fan.x, fan.y + 10), Offset(fan.x - 12, fan.y - 15), 1f)
                }
            }
            TreeSpecies.CHERRY -> {
                cloudCanopy(ForestCoral, 18f)
                listOf(Offset(34f, 42f), Offset(59f, 31f), Offset(67f, 57f), Offset(43f, 61f)).forEach { bloom ->
                    drawCircle(ForestCream, 4f, bloom)
                    drawCircle(ForestInk.copy(alpha = .6f), 1.1f, bloom)
                }
            }
            TreeSpecies.MAPLE -> {
                canopy(path(50f to 15f, 59f to 32f, 73f to 24f, 70f to 43f, 85f to 43f, 75f to 57f, 83f to 65f, 55f to 73f, 50f to 83f, 45f to 73f, 17f to 65f, 25f to 57f, 15f to 43f, 30f to 43f, 27f to 24f, 41f to 32f), ForestCoral)
                drawLine(ForestInk.copy(alpha = .6f), Offset(50f, 75f), Offset(50f, 37f), 1.4f)
                drawLine(ForestInk.copy(alpha = .6f), Offset(50f, 61f), Offset(32f, 47f), 1.4f)
                drawLine(ForestInk.copy(alpha = .6f), Offset(50f, 61f), Offset(68f, 47f), 1.4f)
            }
            TreeSpecies.OAK -> {
                cloudCanopy(ForestLeaf, 21f)
                drawLine(ForestInk, Offset(50f, 85f), Offset(50f, 57f), 5f, StrokeCap.Round)
                drawLine(ForestInk, Offset(50f, 70f), Offset(37f, 59f), 2.4f, StrokeCap.Round)
                drawCircle(ForestMint, 5f, Offset(65f, 56f))
            }
            TreeSpecies.BIRCH -> {
                drawOval(ForestMint, Offset(28f, 13f), Size(44f, 61f))
                drawOval(ForestInk, Offset(28f, 13f), Size(44f, 61f), style = Stroke(1.7f))
                drawLine(ForestCream, Offset(50f, 83f), Offset(50f, 35f), 7f, StrokeCap.Round)
                drawLine(ForestInk, Offset(47f, 84f), Offset(47f, 40f), 1.2f)
                drawLine(ForestInk, Offset(53f, 84f), Offset(53f, 40f), 1.2f)
                listOf(48f, 59f, 72f).forEach { y -> drawLine(ForestInk, Offset(48f, y), Offset(52f, y + 1f), 1.7f, StrokeCap.Round) }
                leaf(48f, 49f, -15f, -10f, ForestLeaf)
                leaf(52f, 40f, 13f, -9f, ForestLeaf)
            }
        }
        if (waterings >= 20) {
            drawCircle(ForestCoral, 3f, Offset(24f, 83f))
            drawCircle(ForestLeaf, 2f, Offset(75f, 80f))
        }
    }
}

private fun DrawScope.drawSeed(species: TreeSpecies) {
    when (species) {
        TreeSpecies.PINE -> {
            canopy(Path().apply {
                moveTo(50f, 53f); cubicTo(31f, 64f, 36f, 84f, 50f, 85f)
                cubicTo(64f, 84f, 69f, 64f, 50f, 53f); close()
            }, ForestLeaf)
            listOf(66f, 73f, 80f).forEach { y ->
                drawLine(ForestInk, Offset(43f, y), Offset(50f, y + 3f), 1.4f)
                drawLine(ForestInk, Offset(50f, y + 3f), Offset(57f, y), 1.4f)
            }
        }
        TreeSpecies.GINKGO -> {
            drawCircle(Color(0xFFF6D878), 12f, Offset(50f, 73f))
            drawCircle(ForestInk, 12f, Offset(50f, 73f), style = Stroke(1.8f))
            drawLine(ForestInk, Offset(50f, 61f), Offset(54f, 56f), 2f, StrokeCap.Round)
            drawCircle(ForestCream, 3f, Offset(46f, 69f))
        }
        TreeSpecies.CHERRY -> {
            drawLine(ForestInk, Offset(42f, 73f), Offset(53f, 53f), 1.8f, StrokeCap.Round)
            drawLine(ForestInk, Offset(61f, 75f), Offset(53f, 53f), 1.8f, StrokeCap.Round)
            listOf(Offset(42f, 76f), Offset(61f, 77f)).forEach {
                drawCircle(ForestCoral, 9f, it); drawCircle(ForestInk, 9f, it, style = Stroke(1.6f))
            }
            leaf(53f, 55f, 12f, -3f, ForestLeaf)
        }
        TreeSpecies.MAPLE -> {
            canopy(Path().apply {
                moveTo(50f, 79f); cubicTo(23f, 85f, 20f, 51f, 30f, 50f)
                cubicTo(40f, 51f, 39f, 68f, 50f, 79f)
                cubicTo(61f, 68f, 60f, 51f, 70f, 50f)
                cubicTo(80f, 51f, 77f, 85f, 50f, 79f); close()
            }, ForestCoral)
            drawLine(ForestInk, Offset(50f, 79f), Offset(30f, 57f), 1.2f)
            drawLine(ForestInk, Offset(50f, 79f), Offset(70f, 57f), 1.2f)
        }
        TreeSpecies.OAK -> {
            canopy(Path().apply {
                moveTo(39f, 66f); lineTo(61f, 66f)
                cubicTo(62f, 78f, 58f, 83f, 50f, 86f)
                cubicTo(42f, 83f, 38f, 78f, 39f, 66f); close()
            }, ForestCoral)
            drawOval(ForestInk, Offset(37f, 60f), Size(26f, 12f))
            drawLine(ForestInk, Offset(50f, 62f), Offset(53f, 55f), 2.2f, StrokeCap.Round)
        }
        TreeSpecies.BIRCH -> {
            canopy(Path().apply {
                moveTo(49f, 54f); cubicTo(37f, 61f, 38f, 78f, 49f, 85f)
                cubicTo(60f, 78f, 61f, 61f, 49f, 54f); close()
            }, ForestMint)
            listOf(62f, 68f, 74f, 80f).forEach { y ->
                drawLine(ForestInk, Offset(44f, y), Offset(53f, y - 2f), 1.2f)
            }
            drawLine(ForestInk, Offset(49f, 55f), Offset(49f, 49f), 1.7f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.canopy(shape: Path, fill: Color) {
    drawPath(shape, fill)
    drawPath(shape, ForestInk, style = Stroke(1.8f))
}

private fun DrawScope.cloudCanopy(fill: Color, radius: Float) {
    // A single outline avoids dark seams where the rounded lobes overlap.
    val cloud = Path().apply {
        moveTo(27f, 69f)
        cubicTo(5f, 67f, 9f, 43f, 25f, 40f)
        cubicTo(17f, 18f, 39f, 10f, 50f, 22f)
        cubicTo(65f, 7f, 85f, 23f, 77f, 40f)
        cubicTo(96f, 47f, 89f, 70f, 72f, 70f)
        cubicTo(63f, 81f, 39f, 80f, 27f, 69f)
        close()
    }
    canopy(cloud, fill)
    drawCircle(ForestCream.copy(alpha = .3f), radius * .45f, Offset(35f, 35f))
}

private fun DrawScope.leaf(x: Float, y: Float, dx: Float, dy: Float, fill: Color) {
    val shape = Path().apply {
        moveTo(x, y)
        cubicTo(x + dx, y + 3f, x + dx * 1.2f, y + dy * .7f, x + dx, y + dy)
        cubicTo(x + dx * .25f, y + dy * 1.15f, x + 2f, y + dy * .7f, x, y)
        close()
    }
    canopy(shape, fill)
}

private fun path(vararg points: Pair<Float, Float>) = Path().apply {
    points.forEachIndexed { index, (x, y) -> if (index == 0) moveTo(x, y) else lineTo(x, y) }
    close()
}
