package com.faithscroll.app.data

import android.content.Context
import com.faithscroll.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

data class Verse(val ref: String, val text: String, val theme: String) {
    /** "John 3:16" -> "John 3" (used to load the whole chapter). */
    val chapterRef: String get() = ref.substringBefore(":")

    /** Verse numbers this card covers, e.g. "4-7" -> 4..7. */
    val verseRange: IntRange
        get() {
            val part = ref.substringAfter(":", "")
            val nums = part.split("-").mapNotNull { it.trim().toIntOrNull() }
            return when (nums.size) {
                0 -> IntRange.EMPTY
                1 -> nums[0]..nums[0]
                else -> nums[0]..nums[1]
            }
        }
}

object VerseRepository {
    @Volatile private var cache: List<Verse>? = null

    fun all(ctx: Context): List<Verse> =
        cache ?: synchronized(this) { cache ?: load(ctx).also { cache = it } }

    fun byRef(ctx: Context, ref: String?): Verse? = ref?.let { r -> all(ctx).firstOrNull { it.ref == r } }

    fun random(ctx: Context, notRef: String? = null): Verse {
        val list = all(ctx)
        if (list.size < 2) return list.first()
        var v: Verse
        do { v = list.random() } while (v.ref == notRef)
        return v
    }

    private fun load(ctx: Context): List<Verse> {
        val base = parse(ctx.assets.open("verses.json").bufferedReader().use { it.readText() })
        val extraFile = File(ctx.filesDir, "remote_verses.json")
        val extra = if (extraFile.exists()) runCatching { parse(extraFile.readText()) }.getOrDefault(emptyList()) else emptyList()
        return (base + extra).distinctBy { it.ref }
    }

    private fun parse(json: String): List<Verse> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ref = o.optString("ref"); val text = o.optString("text")
            if (ref.isBlank() || text.isBlank()) null else Verse(ref, text, o.optString("theme", "faith"))
        }
    }

    /** Pulls extra verses from content/verses.json in the GitHub repo, so new verses can be added without a rebuild. */
    suspend fun refreshRemote(ctx: Context) = withContext(Dispatchers.IO) {
        val repo = BuildConfig.CONTENT_REPO
        if (repo.isBlank()) return@withContext
        val body = Http.get("https://raw.githubusercontent.com/$repo/main/content/verses.json") ?: return@withContext
        if (runCatching { parse(body) }.getOrNull().isNullOrEmpty()) return@withContext
        File(ctx.filesDir, "remote_verses.json").writeText(body)
        cache = null
    }
}

object Encouragement {
    private val byTheme = mapOf(
        "hope" to listOf("Hold on to this promise today.", "Your story isn't finished. God is still writing."),
        "love" to listOf("You are fully known and fully loved.", "Let His love overflow to someone today."),
        "strength" to listOf("Lean on Him when you feel weak.", "His strength meets you right where you are."),
        "comfort" to listOf("Bring Him what you're carrying.", "You are not alone in this."),
        "guidance" to listOf("Ask Him to lead your next step.", "Pause and listen before you decide."),
        "peace" to listOf("Breathe. Be still. He is near.", "Trade one worry for one prayer."),
        "courage" to listOf("Take the brave step. He goes with you.", "Fear is loud, but He is greater."),
        "faith" to listOf("Trust Him with what you can't see.", "Faith grows as you read His Word."),
        "grace" to listOf("You are forgiven. Walk in it.", "New mercies, every single morning."),
        "joy" to listOf("Name three things you're thankful for.", "Let joy be your song today."),
        "prayer" to listOf("Turn this verse into a prayer.", "Talk to Him. He's listening.")
    )
    private val general = listOf(
        "Read it slowly. Let it sink in.",
        "Open the chapter and read the whole story.",
        "Say it out loud. Carry it with you today.",
        "Share it with someone who needs it."
    )
    fun forVerse(v: Verse): String {
        val pool = (byTheme[v.theme] ?: emptyList()) + general
        return pool[Math.floorMod(v.ref.hashCode(), pool.size)]
    }
}
