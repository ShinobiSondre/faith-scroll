package com.faithscroll.app.data

import com.faithscroll.app.util.tr

import android.content.Context
import com.faithscroll.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.faithscroll.app.util.Lang
import org.json.JSONArray
import java.io.File

data class Verse(
    val ref: String, // stable English key, e.g. "John 3:16" (used for saving and the widget)
    val text: String, // in the app language
    val theme: String,
    val label: String = ref, // reference as shown, e.g. "Johannes 3,16" in Norwegian
    val noBook: Int = -1, val noChapter: Int = 0, val noFrom: Int = 0, val noTo: Int = 0
) {
    /** "John 3:16" -> "John 3" (used to load the whole chapter). */
    val chapterRef: String get() = ref.substringBefore(":")

    /** Chapter as shown: "John 3" / "Johannes 3". */
    val chapterLabel: String get() = if (noBook >= 0) label.substringBefore(",") else chapterRef

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
    @Volatile private var cacheNo = false

    fun all(ctx: Context): List<Verse> {
        if (cache != null && cacheNo == Lang.no) return cache!!
        return synchronized(this) {
            if (cache == null || cacheNo != Lang.no) { cache = load(ctx); cacheNo = Lang.no }
            cache!!
        }
    }

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
        val all = (base + extra).distinctBy { it.ref }
        return if (Lang.no) toNorwegian(ctx, all) else all
    }

    /** Swaps in the Norwegian 1930 text and references. Verses we can't map reliably are left out. */
    private fun toNorwegian(ctx: Context, list: List<Verse>): List<Verse> {
        val mapped = runCatching { org.json.JSONObject(ctx.assets.open("verses_no.json").bufferedReader().use { it.readText() }) }.getOrNull()
        return list.mapNotNull { v ->
            mapped?.optJSONObject(v.ref)?.let { o ->
                val book = o.getInt("book"); val ch = o.getInt("chapter"); val from = o.getInt("from"); val to = o.getInt("to")
                // New Testament: modern bokmål (En Levende Bok) instead of the 1930 wording.
                val modern = if (book >= 39 && BibleNo.isModern(ctx, book)) BibleNo.chapter(ctx, book, ch)
                    ?.let { vs -> (from..to).mapNotNull { vs.getOrNull(it - 1) }.joinToString(" ").trim() }?.takeIf { it.isNotBlank() } else null
                v.copy(text = modern ?: o.getString("text"), label = o.getString("label"),
                    noBook = book, noChapter = ch, noFrom = from, noTo = to)
            } ?: BibleNo.lookup(ctx, v.ref)?.let { (text, label, pos) ->
                v.copy(text = text, label = label, noBook = pos[0], noChapter = pos[1], noFrom = pos[2], noTo = pos[3])
            }
        }.ifEmpty { list }
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
    private val byTheme: Map<String, List<String>> get() = mapOf(
        "hope" to listOf(tr("Hold on to this promise today.", "Hold fast på dette løftet i dag."), tr("Your story isn't finished. God is still writing.", "Historien din er ikke ferdig. Gud skriver fortsatt.")),
        "love" to listOf(tr("You are fully known and fully loved.", "Du er fullt kjent og fullt elsket."), tr("Let His love overflow to someone today.", "La Hans kjærlighet renne over til noen i dag.")),
        "strength" to listOf(tr("Lean on Him when you feel weak.", "Len deg på Ham når du føler deg svak."), tr("His strength meets you right where you are.", "Hans styrke møter deg akkurat der du er.")),
        "comfort" to listOf(tr("Bring Him what you're carrying.", "Gi Ham det du bærer på."), tr("You are not alone in this.", "Du er ikke alene i dette.")),
        "guidance" to listOf(tr("Ask Him to lead your next step.", "Be Ham lede ditt neste steg."), tr("Pause and listen before you decide.", "Stopp opp og lytt før du bestemmer deg.")),
        "peace" to listOf(tr("Breathe. Be still. He is near.", "Pust. Vær stille. Han er nær."), tr("Trade one worry for one prayer.", "Bytt én bekymring mot én bønn.")),
        "courage" to listOf(tr("Take the brave step. He goes with you.", "Ta det modige steget. Han går med deg."), tr("Fear is loud, but He is greater.", "Frykten er høylytt, men Han er større.")),
        "faith" to listOf(tr("Trust Him with what you can't see.", "Stol på Ham med det du ikke kan se."), tr("Faith grows as you read His Word.", "Troen vokser når du leser Hans ord.")),
        "grace" to listOf(tr("You are forgiven. Walk in it.", "Du er tilgitt. Lev i det."), tr("New mercies, every single morning.", "Ny nåde, hver eneste morgen.")),
        "joy" to listOf(tr("Name three things you're thankful for.", "Nevn tre ting du er takknemlig for."), tr("Let joy be your song today.", "La glede være sangen din i dag.")),
        "prayer" to listOf(tr("Turn this verse into a prayer.", "Gjør dette verset til en bønn."), tr("Talk to Him. He's listening.", "Snakk med Ham. Han lytter."))
    )
    private val general: List<String> get() = listOf(
        tr("Read it slowly. Let it sink in.", "Les det sakte. La det synke inn."),
        tr("Open the chapter and read the whole story.", "Åpne kapittelet og les hele fortellingen."),
        tr("Say it out loud. Carry it with you today.", "Si det høyt. Bær det med deg i dag."),
        tr("Share it with someone who needs it.", "Del det med noen som trenger det.")
    )
    fun forVerse(v: Verse): String {
        val pool = (byTheme[v.theme] ?: emptyList()) + general
        return pool[Math.floorMod(v.ref.hashCode(), pool.size)]
    }
}
