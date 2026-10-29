package com.faithscroll.app

import com.faithscroll.app.data.AskPrompt
import com.faithscroll.app.data.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskPromptTest {
    // A 30-minute talk: one caption line every 5 seconds.
    private val cues = (0 until 360).map { i -> Cue(i * 5_000L, i * 5_000L + 4_500, "line $i") }

    @Test fun excerptCoversFourMinutesBeforeAndOneAfter() {
        val at = 18 * 60_000L + 21_000 // 18:21
        val ex = AskPrompt.excerpt(cues, at, maxChars = 100_000).lines()
        assertTrue(ex.first().startsWith("[14:20]") || ex.first().startsWith("[14:21]") || ex.first().startsWith("[14:15]"))
        assertTrue(ex.last().startsWith("[19:20]"))
        assertTrue(ex.any { it.startsWith("[18:20]") })
    }

    @Test fun longExcerptKeepsTheMoment() {
        val at = 18 * 60_000L + 21_000
        val ex = AskPrompt.excerpt(cues, at, maxChars = 300)
        assertTrue(ex.length <= 300)
        assertTrue(ex.contains("[18:20]") || ex.lines().last().startsWith("[19"))
    }

    @Test fun questionHasLinkTimeAndText() {
        val q = AskPrompt.build("abc123def45", "Grace", "Desiring God", 1_101_000, cues, norwegian = true)
        assertTrue(q.contains("https://www.youtube.com/watch?v=abc123def45&t=1101s"))
        assertTrue(q.contains("Hva mener de ved 18:21?"))
        assertTrue(q.contains("[18:20] line 220"))
        assertEquals("1:01:01", AskPrompt.time(3_661_000))
    }

    @Test fun noCaptionsStillAsks() {
        val q = AskPrompt.build("abc123def45", "Grace", "Desiring God", 65_000, null, norwegian = false)
        assertTrue(q.contains("What do they mean at 1:05?"))
        assertFalse(q.contains("captions with timestamps"))
    }
}
