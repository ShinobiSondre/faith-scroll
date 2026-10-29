package com.faithscroll.app.data

/** Builds the question sent to ChatGPT: the video, the moment, and what was actually said around it. */
object AskPrompt {
    fun time(ms: Long): String {
        val s = (ms / 1000).toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * The transcript from 4 minutes before to 1 minute after [atMs], as "[mm:ss] text" lines.
     * If it's too long for a link, the oldest lines are dropped (the moment itself is always kept).
     */
    fun excerpt(cues: List<Cue>, atMs: Long, maxChars: Int = 4500): String {
        val lines = cues.filter { it.end >= atMs - 240_000 && it.start <= atMs + 60_000 }
            .map { "[${time(it.start)}] ${it.text}" }.toMutableList()
        while (lines.isNotEmpty() && lines.sumOf { it.length + 1 } > maxChars) lines.removeAt(0)
        return lines.joinToString("\n")
    }

    fun build(videoId: String, title: String, channel: String, atMs: Long, cues: List<Cue>?, norwegian: Boolean): String {
        val at = time(atMs)
        val link = "https://www.youtube.com/watch?v=$videoId&t=${atMs / 1000}s"
        val text = cues?.let { excerpt(it, atMs) }.orEmpty()
        return if (norwegian) buildString {
            append("YouTube-video: $link («$title» av $channel). Pauset ved $at.\n")
            if (text.isNotBlank()) append("Det som blir sagt rundt dette øyeblikket (teksting med tidspunkt):\n$text\n\n")
            append("Hva mener de ved $at? Forklar kort på norsk, ut fra sammenhengen.")
        } else buildString {
            append("YouTube video: $link (\"$title\" by $channel). Paused at $at.\n")
            if (text.isNotBlank()) append("What is said around this moment (captions with timestamps):\n$text\n\n")
            append("What do they mean at $at? Explain briefly, using the context.")
        }
    }
}
