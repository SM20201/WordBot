package me.evil.wordbot

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.text.Text

enum class LevelType(val code: String, val label: String, val color: Int) {
    REGULAR("R", "REGULAR", 0xFF00E5FF.toInt()),     // Cyan
    FUN_FACT("F", "FUN FACT", 0xFFFFD700.toInt()),   // Gold
    SYNONYMS("S", "SYNONYMS", 0xFF00FF66.toInt()),   // Green
    ANTONYMS("A", "ANTONYMS", 0xFFFF0055.toInt()),    // Pink/Red
    RHYMING("RM", "RHYMING", 0xFFFF8800.toInt()),    // Orange
    SEQUENCE("SQ", "SEQUENCE", 0xFFB055FF.toInt())   // Purple
}

data class TopicDetectionResult(
    val rect: Rect,
    val wordBoxRect: Rect?,
    val gridBoxRect: Rect?,
    val rawTopicText: String,
    val cleanedTopicText: String,
    val levelType: LevelType
)

class LevelOverlayEngine {

    fun detectLevelTopic(
        visionText: Text,
        bitmap: Bitmap? = null,
        topicTopY: Float,
        topicBottomY: Float,
        wordsTopY: Float,
        wordsBottomY: Float,
        gridTopY: Float,
        gridBottomY: Float,
        screenWidth: Int,
        screenHeight: Int
    ): TopicDetectionResult {
        val topicLines = mutableListOf<Text.Line>()
        var fullTopicText = ""

        // Pass 1: Scan upper 35% of screen for title text lines
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val centerYRatio = box.centerY().toFloat() / screenHeight

                if (centerYRatio in 0.01f..0.35f) {
                    topicLines.add(line)
                    fullTopicText += " " + line.text.uppercase()
                }
            }
        }

        val rawCleaned = fullTopicText.trim().filter { it.isLetter() || it.isWhitespace() }
        val upperText = rawCleaned.uppercase()

        // Classify Level Type
        val levelType = when {
            upperText.contains("FUN FACT") || upperText.contains("FUNFACT") || upperText.contains("DID YOU KNOW") || upperText.contains("FACT") -> LevelType.FUN_FACT
            upperText.contains("SEQUENCE") || upperText.contains("SERIES") || upperText.contains("PATTERN") || upperText.contains("ORDER") -> LevelType.SEQUENCE
            upperText.contains("RHYME") || upperText.contains("RHYMING") || upperText.contains("RHYMES") || upperText.contains("SOUND ALIKE") -> LevelType.RHYMING
            upperText.contains("SYNONYM") || upperText.contains("SIMILAR") || upperText.contains("SAME MEANING") -> LevelType.SYNONYMS
            upperText.contains("ANTONYM") || upperText.contains("OPPOSITE") || upperText.contains("CONTRAST") -> LevelType.ANTONYMS
            else -> LevelType.REGULAR
        }

        // Filter topic matching lines
        val matchingLines = topicLines.filter { line ->
            val box = line.boundingBox ?: return@filter false
            val centerYRatio = box.centerY().toFloat() / screenHeight
            val lineUpper = line.text.uppercase()

            if (levelType != LevelType.REGULAR) {
                lineUpper.contains("FUN FACT") || lineUpper.contains("FUNFACT") || lineUpper.contains("FACT") ||
                lineUpper.contains("SEQUENCE") || lineUpper.contains("SERIES") || lineUpper.contains("PATTERN") ||
                lineUpper.contains("RHYME") || lineUpper.contains("RHYMING") ||
                lineUpper.contains("SYNONYM") || lineUpper.contains("SIMILAR") ||
                lineUpper.contains("ANTONYM") || lineUpper.contains("OPPOSITE") ||
                centerYRatio in topicTopY..topicBottomY
            } else {
                centerYRatio in topicTopY..topicBottomY
            }
        }

        // Calculate Title Bounding Box
        var minLeft = Int.MAX_VALUE; var minTop = Int.MAX_VALUE
        var maxRight = 0; var maxBottom = 0
        val linesToTopicBox = if (matchingLines.isNotEmpty()) matchingLines else topicLines

        if (linesToTopicBox.isNotEmpty()) {
            for (line in linesToTopicBox) {
                val box = line.boundingBox ?: continue
                if (box.left < minLeft) minLeft = box.left
                if (box.top < minTop) minTop = box.top
                if (box.right > maxRight) maxRight = box.right
                if (box.bottom > maxBottom) maxBottom = box.bottom
            }
        }

        val topicBoundingBox = if (minLeft < maxRight && minTop < maxBottom) {
            Rect(minLeft - 12, minTop - 8, maxRight + 12, maxBottom + 8)
        } else {
            val topPx = (topicTopY * screenHeight).toInt()
            val bottomPx = (topicBottomY * screenHeight).toInt()
            val marginX = (screenWidth * 0.10f).toInt()
            Rect(marginX, topPx, screenWidth - marginX, bottomPx)
        }

        // Pass 2: PRIORITY #1 - Detect Letter Grid Container (Green Box)
        // Scan starting from 0.22f for ALL level types (including Fun Fact) so Row 1 is NEVER missed
        val minGridScanCenterY = 0.22f
        val maxGridBottomRatio = gridBottomY.coerceAtMost(0.88f)
        val maxGridBottomPx = (screenHeight * maxGridBottomRatio).toInt()

        val candidateLetters = mutableListOf<Rect>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val elemBox = element.boundingBox ?: continue
                    val elemCenterY = elemBox.centerY().toFloat() / screenHeight
                    val cleanText = element.text.trim()
                    val aspectRatio = if (elemBox.height() > 0) elemBox.width().toFloat() / elemBox.height() else 0f
                    if (cleanText.length <= 2 && aspectRatio in 0.4f..1.8f && elemCenterY in minGridScanCenterY..maxGridBottomRatio) {
                        candidateLetters.add(elemBox)
                    }
                }
            }
        }

        val rowGroups = mutableListOf<MutableList<Rect>>()
        val sortedCandidates = candidateLetters.sortedBy { it.centerY() }

        for (box in sortedCandidates) {
            val matchingGroup = rowGroups.find { group ->
                val avgY = group.map { it.centerY() }.average()
                Math.abs(box.centerY() - avgY) < 26.0
            }
            if (matchingGroup != null) {
                matchingGroup.add(box)
            } else {
                rowGroups.add(mutableListOf(box))
            }
        }

        // Filter valid grid rows: >= 2 single-letter tiles spanning > 15% screen width
        val validGridRows = rowGroups.filter { group ->
            if (group.size < 2) return@filter false
            val minX = group.minOf { it.left }
            val maxX = group.maxOf { it.right }
            (maxX - minX) > screenWidth * 0.15f
        }.sortedBy { row -> row.minOf { it.top } }

        val gridContainerRect = if (validGridRows.isNotEmpty()) {
            val gMinTop = validGridRows.first().minOf { it.top }
            val gMaxBottom = validGridRows.last().maxOf { it.bottom }
            val gMinLeft = validGridRows.minOf { row -> row.minOf { it.left } }
            val gMaxRight = validGridRows.maxOf { row -> row.maxOf { it.right } }
            Rect(
                (gMinLeft - 18).coerceAtLeast((screenWidth * 0.04f).toInt()),
                (gMinTop - 14).coerceAtLeast((screenHeight * 0.20f).toInt()),
                (gMaxRight + 18).coerceAtMost((screenWidth * 0.96f).toInt()),
                (gMaxBottom + 18).coerceAtMost(maxGridBottomPx)
            )
        } else {
            val defaultMinY = if (levelType == LevelType.FUN_FACT) 0.46f else 0.36f
            Rect(
                (screenWidth * 0.04f).toInt(),
                (defaultMinY * screenHeight).toInt(),
                (screenWidth * 0.96f).toInt(),
                maxGridBottomPx
            )
        }

        // Pass 3: PRIORITY #2 - Word Box Container (Red Box) strictly ABOVE Green Box
        val maxRedBoxBottomPx = (gridContainerRect.top - 14).coerceAtLeast(0)
        val buttonKeywords = listOf("SHUFFLE", "HINT", "BULLSEYE", "SHOP", "COINS", "MENU", "NEXT", "LEVEL", "CLUE", "FREE", "TARGET")

        val wordBoxLines = mutableListOf<Text.Line>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val lineCenterY = box.centerY().toFloat() / screenHeight
                val textUpper = line.text.uppercase().trim()

                // Include text lines below title box and strictly ABOVE grid container top
                if (lineCenterY > 0.12f && box.centerY() < maxRedBoxBottomPx && !buttonKeywords.any { textUpper.contains(it) }) {
                    wordBoxLines.add(line)
                }
            }
        }

        var wMinLeft = Int.MAX_VALUE; var wMinTop = Int.MAX_VALUE
        var wMaxRight = 0; var wMaxBottom = 0
        if (wordBoxLines.isNotEmpty()) {
            for (line in wordBoxLines) {
                val box = line.boundingBox ?: continue
                if (box.left < wMinLeft) wMinLeft = box.left
                if (box.top < wMinTop) wMinTop = box.top
                if (box.right > wMaxRight) wMaxRight = box.right
                if (box.bottom > wMaxBottom) wMaxBottom = box.bottom
            }
        }

        val wordBoxContainerRect = if (wMinLeft < wMaxRight && wMinTop < wMaxBottom) {
            val calculatedBottom = (wMaxBottom + 14).coerceAtMost(maxRedBoxBottomPx)
            val finalBottom = if (levelType == LevelType.SEQUENCE) {
                val min2RowBottom = wMinTop + (screenHeight * 0.16f).toInt()
                calculatedBottom.coerceAtLeast(min2RowBottom).coerceAtMost(maxRedBoxBottomPx)
            } else {
                calculatedBottom
            }
            Rect(
                (screenWidth * 0.05f).toInt(),
                (wMinTop - 14).coerceAtLeast(topicBoundingBox.bottom + 6),
                (screenWidth * 0.95f).toInt(),
                finalBottom
            )
        } else {
            val defaultMaxY = if (levelType == LevelType.FUN_FACT) 0.44f else 0.34f
            val fallbackBottom = (defaultMaxY * screenHeight).toInt().coerceAtMost(maxRedBoxBottomPx)
            Rect(
                (screenWidth * 0.05f).toInt(),
                (topicBoundingBox.bottom + 6).coerceAtLeast((wordsTopY * screenHeight).toInt()),
                (screenWidth * 0.95f).toInt(),
                fallbackBottom
            )
        }

        val cleanedTopic = rawCleaned
            .replace("FUN FACT", "", ignoreCase = true)
            .replace("FUNFACT", "", ignoreCase = true)
            .replace("SOLVE THE SEQUENCE", "", ignoreCase = true)
            .replace("SEQUENCE", "", ignoreCase = true)
            .replace("FIND THE RHYMING WORDS", "", ignoreCase = true)
            .replace("RHYMING", "", ignoreCase = true)
            .replace("RHYMES", "", ignoreCase = true)
            .replace("SYNONYMS", "", ignoreCase = true)
            .replace("SYNONYM", "", ignoreCase = true)
            .replace("ANTONYMS", "", ignoreCase = true)
            .replace("ANTONYM", "", ignoreCase = true)
            .trim()

        return TopicDetectionResult(
            rect = topicBoundingBox,
            wordBoxRect = wordBoxContainerRect,
            gridBoxRect = gridContainerRect,
            rawTopicText = rawCleaned,
            cleanedTopicText = if (cleanedTopic.isNotEmpty()) cleanedTopic else levelType.label,
            levelType = levelType
        )
    }
}
