package com.mohnishraj.aether.core.paint

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaintLimitsFuzzTest {
    @Test fun commandLimitIsEnforced() {
        val markup = "<main>${(1..100).joinToString("") { "<p>item $it</p>" }}</main>"
        val list = paintFixture(markup, "p{background:red;border:1px solid blue}", limits=PaintLimits(maxCommands=10)).displayList
        assertTrue(list.commandCount <= 10)
        assertTrue(list.issues.any { it.code == "paint-command-limit" })
    }
    @Test fun textCharacterLimitTruncatesSafely() {
        val list = paintFixture("<p>abcdefghijklmnopqrstuvwxyz</p>", limits=PaintLimits(maxTextCharacters=8)).displayList
        assertTrue(list.commands.filterIsInstance<PaintCommand.DrawText>().sumOf { it.text.length } <= 8)
        assertTrue(list.issues.any { it.code == "paint-text-limit" })
    }
    @Test fun imageSourceLimitTruncatesSafely() {
        val source = "x".repeat(100)
        val image = paintFixture("<img src='$source' width='10' height='10'>", "img{display:block}", limits=PaintLimits(maxImageSourceChars=12)).displayList.commands.filterIsInstance<PaintCommand.DrawImage>().single()
        assertEquals(12, image.source.length)
    }
    @Test fun shadowCountLimitIsEnforced() {
        val shadows = (1..10).joinToString(",") { "${it}px ${it}px 1px black" }
        val list = paintFixture("<div id='x'></div>", "#x{width:10px;height:10px;box-shadow:$shadows}", limits=PaintLimits(maxShadowsPerBox=3)).displayList
        assertEquals(3, list.commands.count { it is PaintCommand.DrawShadow })
    }
    @Test fun randomCssPaintValuesNeverCrash() {
        val random = Random(20260802)
        repeat(1_000) {
            val color = when (random.nextInt(6)) {
                0 -> "#%06x".format(random.nextInt(0x1000000))
                1 -> "rgb(${random.nextInt(-100,400)},${random.nextInt(-100,400)},${random.nextInt(-100,400)})"
                2 -> "rgba(1,2,3,${random.nextDouble(-2.0,3.0)})"
                3 -> "transparent"
                4 -> "currentColor"
                else -> "garbage-${random.nextInt()}"
            }
            val radius = "${random.nextInt(-20,200)}px"
            val shadow = "${random.nextInt(-50,50)}px ${random.nextInt(-50,50)}px ${random.nextInt(0,30)}px $color"
            val list = paintFixture("<div id='x'>fuzz $it</div>", "#x{width:80px;height:20px;color:teal;background-color:$color;border-radius:$radius;box-shadow:$shadow}").displayList
            assertTrue(list.commandCount >= 0)
        }
    }
    @Test fun transparentBackgroundDoesNotEmitFill() {
        val list = paintFixture("<div id='x'></div>", "#x{width:10px;height:10px;background-color:transparent}").displayList
        assertFalse(list.commands.any { it.nodeId != null && (it is PaintCommand.FillRect || it is PaintCommand.FillRoundedRect) })
    }
}
