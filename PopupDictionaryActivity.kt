package com.saurav.popupdictionary

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Launched by the system when the user selects text anywhere and taps
 * "Saurav Popup Dictionary" in the selection menu (ACTION_PROCESS_TEXT).
 *
 * Also handles ACTION_SEND so the app appears in the Share sheet as a backup
 * for apps that use a custom (non-standard) selection toolbar.
 */
class PopupDictionaryActivity : AppCompatActivity() {

    private lateinit var loadingBox: LinearLayout
    private lateinit var contentBox: LinearLayout
    private lateinit var tvError: TextView
    private lateinit var tvWord: TextView
    private lateinit var tvPhonetic: TextView
    private lateinit var tvHindi: TextView
    private lateinit var meaningsContainer: LinearLayout
    private lateinit var tvSynonyms: TextView
    private lateinit var tvAntonyms: TextView
    private lateinit var btnAudio: Button
    private lateinit var btnClose: ImageButton
    private lateinit var badgeOffline: TextView

    private var audioUrl: String? = null
    private var currentWord: String = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_popup)

        loadingBox = findViewById(R.id.loadingBox)
        contentBox = findViewById(R.id.contentBox)
        tvError = findViewById(R.id.tvError)
        tvWord = findViewById(R.id.tvWord)
        tvPhonetic = findViewById(R.id.tvPhonetic)
        tvHindi = findViewById(R.id.tvHindi)
        meaningsContainer = findViewById(R.id.meaningsContainer)
        tvSynonyms = findViewById(R.id.tvSynonyms)
        tvAntonyms = findViewById(R.id.tvAntonyms)
        btnAudio = findViewById(R.id.btnAudio)
        btnClose = findViewById(R.id.btnClose)
        badgeOffline = findViewById(R.id.badgeOffline)

        btnClose.setOnClickListener { finish() }
        findViewById<View>(R.id.root).setOnClickListener { finish() }   // tap outside = close
        findViewById<View>(R.id.card).setOnClickListener { /* swallow */ }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }

        btnAudio.setOnClickListener { speak() }

        val raw = readSelectedText()
        val word = clean(raw)

        if (word.isBlank()) {
            showError("No word selected.")
            return
        }

        currentWord = word
        tvWord.text = word
        lookup(word)
    }

    /** Works for both PROCESS_TEXT (selection menu) and SEND (share sheet). */
    private fun readSelectedText(): String {
        intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.let { return it.toString() }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { return it }
        return ""
    }

    /** Keep only the first word; strip punctuation. "Courage," -> "courage" */
    private fun clean(raw: String): String =
        raw.trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z\\-']"), "")

    private fun speak() {
        val url = audioUrl
        if (!url.isNullOrBlank()) {
            try {
                MediaPlayer().apply {
                    setOnPreparedListener { start() }
                    setOnCompletionListener { release() }
                    setDataSource(url)
                    prepareAsync()
                }
                return
            } catch (_: Exception) { /* fall through to TTS */ }
        }
        if (ttsReady) {
            tts?.speak(currentWord, TextToSpeech.QUEUE_FLUSH, null, "saurav-dict")
        } else {
            Toast.makeText(this, "Pronunciation not available", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ lookup

    private fun lookup(word: String) {
        loadingBox.visibility = View.VISIBLE
        contentBox.visibility = View.GONE
        tvError.visibility = View.GONE

        // 1. Built-in words work with zero network.
        Builtin.get(word)?.let { showResult(it, offline = true); return }

        // 2. Previously looked-up words are cached on device.
        DictCache.get(this, word)?.let { showResult(it, offline = true); return }

        // 3. Network.
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchDictionary(word) }
            if (result != null) {
                DictCache.put(this@PopupDictionaryActivity, word, result)
                showResult(result, offline = false)
            } else {
                showError("No definition found for \u201C$word\u201D.\nCheck your connection and try again.")
            }
        }
    }

    private fun fetchDictionary(word: String): DictResult? {
        val encoded = URLEncoder.encode(word, "UTF-8")

        // Primary: freedictionaryapi.com
        try {
            httpGet("https://freedictionaryapi.com/api/v1/entries/en/$encoded", 9000)?.let {
                val r = parseFreedictionaryAPI(JSONObject(it), word)
                if (r.meanings.isNotEmpty()) return r.copy(hindi = fetchHindi(word))
            }
        } catch (_: Exception) { }

        // Fallback: api.suvankar.cc
        try {
            httpGet("https://api.suvankar.cc/dictionaryapi/v1/definitions/en/$encoded", 8000)?.let {
                val r = parseSuvankar(JSONObject(it), word)
                if (r.meanings.isNotEmpty()) return r.copy(hindi = fetchHindi(word))
            }
        } catch (_: Exception) { }

        return null
    }

    private fun httpGet(urlStr: String, timeout: Int): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeout
            readTimeout = timeout
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SauravPopupDictionary/1.0")
        }
        return try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseFreedictionaryAPI(obj: JSONObject, word: String): DictResult {
        var phonetic = ""
        var audio: String? = null
        val meanings = mutableListOf<Meaning>()
        val syn = linkedSetOf<String>()
        val ant = linkedSetOf<String>()

        val entries = obj.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val pos = entry.optString("partOfSpeech", "unknown")

            entry.optJSONArray("pronunciations")?.let { prons ->
                for (p in 0 until prons.length()) {
                    val pr = prons.getJSONObject(p)
                    if (phonetic.isEmpty()) phonetic = pr.optString("text", "")
                    if (audio.isNullOrEmpty()) {
                        val a = pr.optString("audio", "")
                        if (a.isNotBlank()) audio = if (a.startsWith("//")) "https:$a" else a
                    }
                }
            }

            val senses = entry.optJSONArray("senses") ?: JSONArray()
            val defs = mutableListOf<Definition>()
            for (j in 0 until senses.length()) {
                val s = senses.getJSONObject(j)
                val def = s.optString("definition", "")
                    .ifBlank { s.optJSONArray("glosses")?.optString(0) ?: "" }
                val ex = s.optJSONArray("examples")?.optString(0) ?: ""
                if (def.isNotBlank()) defs.add(Definition(def, ex))
                s.optJSONArray("synonyms")?.let { for (k in 0 until it.length()) syn.add(it.getString(k)) }
                s.optJSONArray("antonyms")?.let { for (k in 0 until it.length()) ant.add(it.getString(k)) }
            }
            entry.optJSONArray("synonyms")?.let { for (k in 0 until it.length()) syn.add(it.getString(k)) }
            entry.optJSONArray("antonyms")?.let { for (k in 0 until it.length()) ant.add(it.getString(k)) }

            if (defs.isNotEmpty()) meanings.add(Meaning(pos, defs))
        }

        return DictResult(word, phonetic, audio, "\u2014", meanings, syn.toList(), ant.toList())
    }

    private fun parseSuvankar(obj: JSONObject, word: String): DictResult {
        val phonetic = obj.optString("ipa", "")
        val audio = obj.optString("audioUrl", "").ifBlank { null }
        val meanings = mutableListOf<Meaning>()
        val syn = linkedSetOf<String>()
        val ant = linkedSetOf<String>()

        val mArr = obj.optJSONArray("meanings") ?: JSONArray()
        for (i in 0 until mArr.length()) {
            val m = mArr.getJSONObject(i)
            val pos = m.optString("partOfSpeech", "unknown")
            val senses = m.optJSONArray("senses") ?: JSONArray()
            val defs = mutableListOf<Definition>()
            for (j in 0 until senses.length()) {
                val s = senses.getJSONObject(j)
                val gloss = s.optJSONArray("glosses")?.optString(0) ?: ""
                val ex = s.optJSONArray("examples")?.optString(0) ?: ""
                if (gloss.isNotBlank()) defs.add(Definition(gloss, ex))
                s.optJSONArray("synonyms")?.let { for (k in 0 until it.length()) syn.add(it.getString(k)) }
                s.optJSONArray("antonyms")?.let { for (k in 0 until it.length()) ant.add(it.getString(k)) }
            }
            if (defs.isNotEmpty()) meanings.add(Meaning(pos, defs))
        }

        return DictResult(word, phonetic, audio, "\u2014", meanings, syn.toList(), ant.toList())
    }

    private fun fetchHindi(word: String): String = try {
        val json = httpGet(
            "https://api.mymemory.translated.net/get?q=${URLEncoder.encode(word, "UTF-8")}&langpair=en|hi",
            6000
        )
        val t = json?.let { JSONObject(it).optJSONObject("responseData")?.optString("translatedText") } ?: ""
        if (t.isNotBlank() && !t.contains("MYMEMORY WARNING") && !t.contains("QUERY LENGTH")) t else "\u2014"
    } catch (_: Exception) {
        "\u2014"
    }

    // ------------------------------------------------------------------ render

    private fun showResult(r: DictResult, offline: Boolean) {
        loadingBox.visibility = View.GONE
        contentBox.visibility = View.VISIBLE
        tvError.visibility = View.GONE

        tvWord.text = r.word
        tvPhonetic.text = r.phonetic
        tvPhonetic.visibility = if (r.phonetic.isBlank()) View.GONE else View.VISIBLE
        tvHindi.text = r.hindi
        badgeOffline.visibility = if (offline) View.VISIBLE else View.GONE
        audioUrl = r.audio

        meaningsContainer.removeAllViews()
        r.meanings.take(4).forEach { m ->
            meaningsContainer.addView(TextView(this).apply {
                text = m.partOfSpeech.uppercase(Locale.US)
                setTextColor(0xFF9EC5FF.toInt())
                textSize = 11f
                letterSpacing = 0.08f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(6))
            })
            m.definitions.take(4).forEachIndexed { idx, d ->
                meaningsContainer.addView(TextView(this).apply {
                    val body = "${idx + 1}. ${d.definition}"
                    text = if (d.example.isBlank()) body else "$body\n\u201C${d.example}\u201D"
                    setTextColor(0xFFE8EEF8.toInt())
                    textSize = 14.5f
                    setLineSpacing(0f, 1.2f)
                    setBackgroundResource(R.drawable.bg_definition)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )).also { lp -> lp.bottomMargin = dp(8); layoutParams = lp }
                })
            }
        }

        tvSynonyms.text = if (r.synonyms.isEmpty()) "\u2014" else r.synonyms.take(12).joinToString("  \u2022  ")
        tvAntonyms.text = if (r.antonyms.isEmpty()) "\u2014" else r.antonyms.take(8).joinToString("  \u2022  ")
    }

    private fun showError(msg: String) {
        loadingBox.visibility = View.GONE
        contentBox.visibility = View.GONE
        tvError.visibility = View.VISIBLE
        tvError.text = msg
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

// ---------------------------------------------------------------- data + cache

data class Definition(val definition: String, val example: String = "")
data class Meaning(val partOfSpeech: String, val definitions: List<Definition>)
data class DictResult(
    val word: String,
    val phonetic: String,
    val audio: String?,
    val hindi: String,
    val meanings: List<Meaning>,
    val synonyms: List<String>,
    val antonyms: List<String>
)

/** Small on-device cache so repeated words work offline. */
object DictCache {
    private const val PREFS = "saurav_dict_cache_v1"
    private const val MAX = 200

    fun get(ctx: Context, word: String): DictResult? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(word, null)?.let { fromJson(JSONObject(it)) }
    } catch (_: Exception) { null }

    fun put(ctx: Context, word: String, r: DictResult) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.all.size > MAX) prefs.edit().clear().apply()
            prefs.edit().putString(word, toJson(r).toString()).apply()
        } catch (_: Exception) { }
    }

    private fun toJson(r: DictResult) = JSONObject().apply {
        put("word", r.word); put("phonetic", r.phonetic)
        put("audio", r.audio ?: ""); put("hindi", r.hindi)
        put("meanings", JSONArray().also { arr ->
            r.meanings.forEach { m ->
                arr.put(JSONObject().apply {
                    put("pos", m.partOfSpeech)
                    put("defs", JSONArray().also { d ->
                        m.definitions.forEach { def ->
                            d.put(JSONObject().put("d", def.definition).put("e", def.example))
                        }
                    })
                })
            }
        })
        put("syn", JSONArray(r.synonyms))
        put("ant", JSONArray(r.antonyms))
    }

    private fun fromJson(o: JSONObject): DictResult {
        val meanings = mutableListOf<Meaning>()
        val mArr = o.optJSONArray("meanings") ?: JSONArray()
        for (i in 0 until mArr.length()) {
            val m = mArr.getJSONObject(i)
            val dArr = m.optJSONArray("defs") ?: JSONArray()
            val defs = (0 until dArr.length()).map {
                val d = dArr.getJSONObject(it)
                Definition(d.optString("d"), d.optString("e"))
            }
            meanings.add(Meaning(m.optString("pos"), defs))
        }
        fun list(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.getString(it) }
        }
        return DictResult(
            o.optString("word"), o.optString("phonetic"),
            o.optString("audio").ifBlank { null }, o.optString("hindi"),
            meanings, list("syn"), list("ant")
        )
    }
}

/** Always-available words, ported from the HTML page. */
object Builtin {
    private fun d(def: String, ex: String = "") = Definition(def, ex)

    private val words: Map<String, DictResult> = mapOf(
        entry("hello", "/h\u0259\u02C8l\u0259\u028A/", "\u0928\u092E\u0938\u094D\u0924\u0947",
            Meaning("interjection", listOf(
                d("A greeting said when meeting someone.", "Hello, everyone."),
                d("A greeting used when answering the telephone.", "Hello? How may I help you?"))),
            Meaning("verb", listOf(d("To greet with \u201Chello\u201D.")))),
        entry("speech", "/spi\u02D0t\u0283/", "\u092D\u093E\u0937\u0923 / \u0935\u093E\u0923\u0940",
            Meaning("noun", listOf(
                d("The expression of thoughts and feelings by articulate sounds."),
                d("A formal address delivered to an audience.")))),
        entry("pronunciation", "/pr\u0259\u02CCn\u028Cnsi\u02C8e\u026A\u0283n/", "\u0909\u091A\u094D\u091A\u093E\u0930\u0923",
            Meaning("noun", listOf(d("The way in which a word is pronounced.")))),
        entry("beautiful", "/\u02C8bju\u02D0t\u026Afl/", "\u0938\u0941\u0902\u0926\u0930",
            Meaning("adjective", listOf(
                d("Pleasing the senses or mind aesthetically."),
                d("Of a very high standard; excellent.")))),
        entry("courage", "/\u02C8k\u028Cr\u026Ad\u0292/", "\u0938\u093E\u0939\u0938",
            Meaning("noun", listOf(d("The ability to do something that frightens one; bravery.")))),
        entry("knowledge", "/\u02C8n\u0252l\u026Ad\u0292/", "\u091C\u094D\u091E\u093E\u0928",
            Meaning("noun", listOf(d("Facts, information and skills acquired through experience or education.")))),
        entry("success", "/s\u0259k\u02C8ses/", "\u0938\u092B\u0932\u0924\u093E",
            Meaning("noun", listOf(d("The accomplishment of an aim or purpose.")))),
        entry("dictionary", "/\u02C8d\u026Ak\u0283\u0259nri/", "\u0936\u092C\u094D\u0926\u0915\u094B\u0936",
            Meaning("noun", listOf(d("A resource that lists the words of a language and gives their meaning."))))
    )

    private fun entry(word: String, ipa: String, hindi: String, vararg m: Meaning) =
        word to DictResult(word, ipa, null, hindi, m.toList(), emptyList(), emptyList())

    fun get(word: String): DictResult? = words[word]
}
