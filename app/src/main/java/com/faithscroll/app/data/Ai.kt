package com.faithscroll.app.data

import android.content.Context
import com.faithscroll.app.util.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "What do they mean here?". Free AI with no account or key:
 *  1. OVHcloud AI Endpoints (anonymous tier, large open models),
 *  2. Pollinations.ai,
 *  3. or a small model running on the phone (offline, optional download).
 */
object Ai {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    data class Turn(val user: Boolean, val text: String)
    class RateLimited(val seconds: Int) : Exception("Rate limited")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ai", Context.MODE_PRIVATE)
    fun preferOffline(ctx: Context) = prefs(ctx).getBoolean("offline", false)
    fun setPreferOffline(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("offline", v).apply()

    fun system(): String {
        val language = if (Lang.no) "Norwegian (Bokmål)" else "English"
        return """You are a warm, knowledgeable Bible teacher inside FaithScroll, a Christian app.
The user paused a Christian video and wants to understand what the speaker means at that moment.
You get the video title, channel, description, and the transcript around the paused moment (automatic transcripts may contain errors).
- Explain plainly what is being said and the idea behind it, in 2–4 short paragraphs.
- When it helps, point to the Bible passages it relates to (book chapter:verse) and briefly say how.
- If the transcript is unclear or missing, say so honestly and explain what you reasonably can from the title and description.
- Be respectful of different Christian traditions; don't invent quotes.
- Answer in $language. Keep it under 220 words unless asked for more. Plain text, no markdown headings."""
    }

    private fun messages(context: String, turns: List<Turn>): JSONArray {
        val m = JSONArray().put(JSONObject().put("role", "system").put("content", system()))
        turns.forEachIndexed { i, t ->
            m.put(JSONObject().put("role", if (t.user) "user" else "assistant").put("content", if (i == 0) "$context\n\nQuestion: ${t.text}" else t.text))
        }
        return m
    }

    private fun clean(s: String) = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    /** OpenAI-style chat endpoint without a key. */
    private fun openAiStyle(url: String, model: String?, context: String, turns: List<Turn>): String {
        val body = JSONObject().put("messages", messages(context, turns)).put("max_tokens", 700).put("temperature", 0.4)
        if (model != null) body.put("model", model)
        val req = Request.Builder().url(url).header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 429 || r.code == 402) throw RateLimited(r.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 120) ?: 30)
            if (!r.isSuccessful) error("HTTP ${r.code} ${text.take(120)}")
            val answer = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            return clean(answer).ifBlank { error("Empty answer") }
        }
    }

    private fun pollinations(context: String, turns: List<Turn>): String {
        val body = JSONObject().put("messages", messages(context, turns)).put("private", true)
        val req = Request.Builder().url("https://text.pollinations.ai/").header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty().trim()
            if (r.code == 402 || r.code == 429) throw RateLimited(r.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 120) ?: 30)
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return clean(text).ifBlank { error("Empty answer") }
        }
    }

    private const val OVH = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions"

    /** Uses only what's chosen in Me → AI: the online services, or the phone's own model. Returns (answer, source). */
    suspend fun ask(ctx: Context, context: String, turns: List<Turn>): Pair<String, String> = withContext(Dispatchers.IO) {
        if (preferOffline(ctx)) {
            if (!OfflineAi.ready(ctx)) throw IllegalStateException(com.faithscroll.app.util.tr(
                "The phone model isn't downloaded yet – download it in Me → AI, or choose Online.",
                "Telefonmodellen er ikke lastet ned ennå – last den ned under Meg → AI, eller velg På nett."))
            return@withContext OfflineAi.ask(ctx, context, turns) to "offline"
        }
        val providers: List<Pair<String, () -> String>> = listOf(
            "Llama 3.3 70B (OVHcloud)" to { openAiStyle(OVH, "meta-llama-3_3-70b-instruct", context, turns) },
            "Mistral Small (OVHcloud)" to { openAiStyle(OVH, "mistral-small-3-2-24b-instruct", context, turns) },
            "Pollinations" to { pollinations(context, turns) }
        )
        var limited: RateLimited? = null
        var last: Throwable? = null
        for ((name, call) in providers) {
            val r = runCatching { call() }
            r.onSuccess { return@withContext it to name }
            val e = r.exceptionOrNull()!!
            Diag.err("ai", "$name failed", e)
            if (e is RateLimited) limited = if (limited == null || e.seconds < limited!!.seconds) e else limited
            last = e
        }
        throw limited ?: last ?: IllegalStateException("No AI available")
    }
}

/** One question/answer thread about a moment in a video. Runs in the background, so closing the sheet doesn't stop it. */
class AskSession(val videoId: String, val atMs: Long) {
    val turns = MutableStateFlow<List<Ai.Turn>>(emptyList())
    val busy = MutableStateFlow(false)
    val wait = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val source = MutableStateFlow<String?>(null)
    var context: String? = null
    var job: Job? = null
}

object AskSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = HashMap<String, AskSession>()

    @Synchronized fun get(videoId: String, atMs: Long): AskSession =
        sessions.getOrPut("$videoId@${atMs / 5000}") { AskSession(videoId, atMs) }

    fun ask(ctx: Context, s: AskSession, video: Video, question: String) {
        if (s.busy.value) return
        val app = ctx.applicationContext
        s.busy.value = true; s.error.value = null
        s.turns.value = s.turns.value + Ai.Turn(true, question)
        s.job = scope.launch {
            val context = s.context ?: buildContext(video, s.atMs).also { s.context = it }
            while (true) {
                val r = runCatching { Ai.ask(app, context, s.turns.value) }
                val e = r.exceptionOrNull()
                if (e is Ai.RateLimited) {
                    s.wait.value = e.seconds
                    while (s.wait.value > 0) { delay(1000); s.wait.value -= 1 }
                    continue
                }
                r.onSuccess { (answer, from) -> s.turns.value = s.turns.value + Ai.Turn(false, answer); s.source.value = from }
                r.onFailure { s.error.value = it.message; s.turns.value = s.turns.value.dropLast(1) }
                break
            }
            s.busy.value = false
        }
    }

    private suspend fun buildContext(video: Video, atMs: Long): String {
        val t0 = System.currentTimeMillis()
        // YouTube captions are usually cached already (fetched ahead); otherwise this takes ~1–2 s.
        val words = runCatching { kotlinx.coroutines.withTimeoutOrNull(10_000) { Transcripts.textAround(video.id, atMs) } }.getOrNull().orEmpty()
        Diag.log("ai", "${video.id}: transcript ready in ${(System.currentTimeMillis() - t0) / 1000.0} s")
        val desc = StreamResolver.cached(video.id)?.description.orEmpty().take(700)
        Diag.log("ai", "${video.id}: asking with ${words.length} chars of transcript, ${desc.length} of description")
        val mm = atMs / 60000; val ss = (atMs / 1000) % 60
        return buildString {
            append("Video: \"${video.title}\" by ${video.channel}\n")
            if (desc.isNotBlank()) append("Description: $desc\n")
            append("Paused at %d:%02d\n".format(mm, ss))
            append(if (words.isNotBlank()) "Transcript around this moment:\n\"$words\"" else "No transcript is available for this moment.")
        }
    }
}

/**
 * A small AI model that runs on the phone (Qwen 2.5, 1.5B, via Google's MediaPipe), for
 * answers without internet. Optional one-time download (~1.6 GB).
 */
object OfflineAi {
    private const val FILE = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    private const val URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/$FILE"
    val progress = MutableStateFlow(-1f) // -1 = not downloading
    @Volatile private var engine: com.google.mediapipe.tasks.genai.llminference.LlmInference? = null
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()

    private fun file(ctx: Context) = File(ctx.filesDir, "llm/$FILE")
    fun ready(ctx: Context) = file(ctx).let { it.exists() && it.length() > 100_000_000 }

    suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        val f = file(ctx); f.parentFile?.mkdirs()
        val part = File(f.parentFile, "$FILE.part")
        progress.value = 0f
        try {
            var have = if (part.exists()) part.length() else 0L
            val req = Request.Builder().url(URL).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                if (r.code != 206) have = 0
                val total = have + (r.body?.contentLength() ?: 0L)
                java.io.FileOutputStream(part, have > 0).use { out ->
                    r.body!!.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); have += n
                            progress.value = have.toFloat() / total.coerceAtLeast(1)
                        }
                    }
                }
            }
            part.renameTo(f)
            Diag.log("ai", "offline model downloaded (${f.length() / 1_000_000} MB)")
        } finally { progress.value = -1f }
    }

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts (or resumes) the download in the background; keeps going if you leave the screen. */
    fun start(ctx: Context) {
        if (progress.value >= 0f) return
        val app = ctx.applicationContext
        bg.launch { runCatching { download(app) }.onFailure { Diag.err("ai", "offline model download", it) } }
    }

    fun delete(ctx: Context) { engine?.close(); engine = null; file(ctx).delete() }

    @Synchronized private fun engine(ctx: Context): com.google.mediapipe.tasks.genai.llminference.LlmInference =
        engine ?: com.google.mediapipe.tasks.genai.llminference.LlmInference.createFromOptions(
            ctx.applicationContext,
            com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file(ctx).absolutePath)
                .setMaxTokens(2048)
                .build()
        ).also { engine = it }

    suspend fun ask(ctx: Context, context: String, turns: List<Ai.Turn>): String = withContext(Dispatchers.Default) {
        // Qwen's chat format. Keep it short: a small model has a small window.
        val sb = StringBuilder("<|im_start|>system\n${Ai.system()}<|im_end|>\n")
        turns.forEachIndexed { i, t ->
            val content = if (i == 0) "${context.take(2500)}\n\nQuestion: ${t.text}" else t.text
            sb.append("<|im_start|>${if (t.user) "user" else "assistant"}\n$content<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        engine(ctx).generateResponse(sb.toString()).replace("<|im_end|>", "").trim()
    }
}
