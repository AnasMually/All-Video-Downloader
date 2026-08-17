package com.anas_mugally.videodownloader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MyAppAd(
    val id: String,
    val timestamp: Long,
    val nameApp: String,
    val decApp: String,
    val urlApp: String,
    val urlImageApp: String,
    val urlVideoApp: String,
    val btnOpText: String,
)

/**
 * Lightweight implementation of the existing custom Firebase ad system.
 *
 * It intentionally uses the same Firestore collection/schema and Realtime Database
 * click counter as BaseMyAppAdsActivity, but talks to Firebase REST endpoints directly.
 * This keeps the downloader APK small and avoids adding the Firebase Android SDK only
 * for the custom ad card.
 */
class MyAppAdsRepository(private val context: Context) {
    companion object {
        private const val TAG = "MyAppAdsRepository"
        private const val FIREBASE_PROJECT_ID = "adsmyapp-30a32"
        private const val FIREBASE_API_KEY = "AIzaSyDyCHcHflyQrD01LwNKF-9bGtiwnUJ6Dzw"
        private const val FIREBASE_DATABASE_URL =
            "https://adsmyapp-30a32-default-rtdb.firebaseio.com"
        private const val ADS_COLLECTION = "ads"
        private const val ADS_EXPIRATION_MS = 24L * 60L * 60L * 1000L

        private val supportedLanguages = setOf(
            "ar", "bn", "ca", "cs", "da", "de", "en", "es", "fr", "hi", "id", "it",
            "ja", "ko", "nl", "pt", "ru", "sk", "sv", "tr", "uk", "ur", "zh",
        )
    }

    private val preferences = context.getSharedPreferences("MyAppAds_SHARED", Context.MODE_PRIVATE)
    private val _currentAd = MutableStateFlow<MyAppAd?>(null)
    val currentAd = _currentAd.asStateFlow()

    fun clearCurrent() {
        _currentAd.value = null
    }

    suspend fun loadNextAd() = withContext(Dispatchers.IO) {
        val language = normalizedLanguage(Locale.getDefault().language)
        val ads = loadAds(language).ifEmpty {
            if (language == "en") emptyList() else loadAds("en")
        }
        if (ads.isEmpty()) {
            _currentAd.value = null
            return@withContext
        }

        val current = preferences.getInt("ad_index", -1)
        val next = if (current < ads.lastIndex) current + 1 else 0
        preferences.edit().putInt("ad_index", next).apply()
        _currentAd.value = ads.getOrNull(next)
    }

    fun recordClick(ad: MyAppAd) {
        Thread {
            runCatching { incrementRealtimeCounter(ad.nameApp) }
                .onFailure { Log.w(TAG, "Ad click counter failed", it) }
        }.start()
    }

    private fun normalizedLanguage(raw: String?): String {
        val language = raw.orEmpty().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return if (language in supportedLanguages) language else "en"
    }

    private fun loadAds(language: String): List<MyAppAd> {
        val cacheKey = "ads_cache_$language"
        val cacheTimeKey = "ads_cache_time_$language"
        val cachedAt = preferences.getLong(cacheTimeKey, 0L)
        val cachedJson = preferences.getString(cacheKey, null)
        if (!cachedJson.isNullOrBlank() && System.currentTimeMillis() - cachedAt < ADS_EXPIRATION_MS) {
            parseCachedAds(cachedJson)?.let { return it }
        }

        return runCatching { fetchFromFirestore(language) }
            .onSuccess { ads ->
                preferences.edit()
                    .putString(cacheKey, encodeAds(ads))
                    .putLong(cacheTimeKey, System.currentTimeMillis())
                    .apply()
            }
            .onFailure { Log.w(TAG, "Could not load Firebase ads for $language", it) }
            .getOrElse { parseCachedAds(cachedJson.orEmpty()).orEmpty() }
    }

    private fun fetchFromFirestore(language: String): List<MyAppAd> {
        val url = URL(
            "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/" +
                "databases/(default)/documents/$ADS_COLLECTION/${Uri.encode(language)}" +
                "?key=${Uri.encode(FIREBASE_API_KEY)}",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code == 404) return emptyList()
            if (code !in 200..299) error("Firestore HTTP $code")
            val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val fields = root.optJSONObject("fields") ?: return emptyList()
            val result = ArrayList<MyAppAd>()
            val keys = fields.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val decoded = decodeFirestoreValue(fields.optJSONObject(id) ?: continue) as? Map<*, *>
                    ?: continue
                if ((decoded["isEnabled"] as? Boolean) == false) continue

                val urlApp = decoded["urlApp"]?.toString().orEmpty().trim()
                if (urlApp.isBlank() || urlApp.contains(context.packageName, ignoreCase = true)) continue

                result += MyAppAd(
                    id = id,
                    timestamp = id.toLongOrNull() ?: 0L,
                    nameApp = decoded["nameApp"]?.toString().orEmpty(),
                    decApp = decoded["decApp"]?.toString().orEmpty(),
                    urlApp = urlApp,
                    urlImageApp = decoded["urlImageApp"]?.toString().orEmpty(),
                    urlVideoApp = decoded["urlVideoApp"]?.toString().orEmpty(),
                    btnOpText = decoded["btnOpText"]?.toString().orEmpty(),
                )
            }
            return result.sortedByDescending(MyAppAd::timestamp)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeFirestoreValue(value: JSONObject): Any? = when {
        value.has("stringValue") -> value.optString("stringValue")
        value.has("integerValue") -> value.optString("integerValue").toLongOrNull() ?: 0L
        value.has("doubleValue") -> value.optDouble("doubleValue")
        value.has("booleanValue") -> value.optBoolean("booleanValue")
        value.has("nullValue") -> null
        value.has("mapValue") -> {
            val fields = value.optJSONObject("mapValue")?.optJSONObject("fields") ?: return emptyMap<String, Any?>()
            buildMap {
                val keys = fields.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, fields.optJSONObject(key)?.let(::decodeFirestoreValue))
                }
            }
        }
        value.has("arrayValue") -> {
            val values = value.optJSONObject("arrayValue")?.optJSONArray("values") ?: JSONArray()
            buildList {
                for (index in 0 until values.length()) {
                    add(values.optJSONObject(index)?.let(::decodeFirestoreValue))
                }
            }
        }
        else -> null
    }

    private fun encodeAds(ads: List<MyAppAd>): String = JSONArray().apply {
        ads.forEach { ad ->
            put(
                JSONObject()
                    .put("id", ad.id)
                    .put("timestamp", ad.timestamp)
                    .put("nameApp", ad.nameApp)
                    .put("decApp", ad.decApp)
                    .put("urlApp", ad.urlApp)
                    .put("urlImageApp", ad.urlImageApp)
                    .put("urlVideoApp", ad.urlVideoApp)
                    .put("btnOpText", ad.btnOpText),
            )
        }
    }.toString()

    private fun parseCachedAds(json: String): List<MyAppAd>? = runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    MyAppAd(
                        id = item.optString("id"),
                        timestamp = item.optLong("timestamp"),
                        nameApp = item.optString("nameApp"),
                        decApp = item.optString("decApp"),
                        urlApp = item.optString("urlApp"),
                        urlImageApp = item.optString("urlImageApp"),
                        urlVideoApp = item.optString("urlVideoApp"),
                        btnOpText = item.optString("btnOpText"),
                    ),
                )
            }
        }
    }.getOrNull()

    private fun incrementRealtimeCounter(adName: String) {
        if (adName.isBlank()) return
        val namespace = context.packageName.substringAfterLast('.').ifBlank { "videodownloader" }
        val path = "${Uri.encode(namespace)}/${Uri.encode(adName)}.json"
        val url = URL("$FIREBASE_DATABASE_URL/$path")

        repeat(3) {
            val read = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("X-Firebase-ETag", "true")
            }
            val etag: String
            val current: Long
            try {
                if (read.responseCode !in 200..299) return
                etag = read.getHeaderField("ETag") ?: return
                val body = read.inputStream.bufferedReader().use { it.readText() }
                val json = runCatching { JSONObject(body) }.getOrNull()
                current = json?.optLong("count", 0L) ?: 0L
            } finally {
                read.disconnect()
            }

            val write = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("If-Match", etag)
            }
            try {
                write.outputStream.use { output ->
                    output.write(JSONObject().put("count", current + 1L).toString().toByteArray())
                }
                when (write.responseCode) {
                    in 200..299 -> return
                    412 -> Unit
                    else -> return
                }
            } finally {
                write.disconnect()
            }
        }
    }
}
