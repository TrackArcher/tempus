package com.cappielloantonio.tempo.elzicy

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.util.Preferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for the [Elzicy](https://github.com/TrackArcher/Elzicy) internet radio metadata API.
 * Uses SSE for live now-playing updates and REST for per-station track history.
 */
class ElzicyClient private constructor() {
    fun interface TrackUpdateListener {
        fun onTrackUpdate(stationName: String, trackTitle: String)
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val nowPlayingLiveData = MutableLiveData<Map<String, String>>(emptyMap())
    private val trackHistoryLiveData = MutableLiveData<Map<String, List<String>>>(emptyMap())

    private val listenerCount = AtomicInteger(0)
    private val listeners = mutableListOf<TrackUpdateListener>()
    private var sseThread: Thread? = null
    @Volatile
    private var running = false

    fun isConfigured(): Boolean {
        val baseUrl = normalizeBaseUrl(Preferences.getElzicyApiBaseUrl())
        val token = Preferences.getElzicyApiToken()
        return baseUrl != null && !token.isNullOrBlank()
    }

    fun getNowPlaying(): LiveData<Map<String, String>> = nowPlayingLiveData

    fun getTrackHistory(): LiveData<Map<String, List<String>>> = trackHistoryLiveData

    fun getNowPlayingForStation(stationName: String?): String? {
        if (stationName.isNullOrBlank()) return null
        return nowPlayingLiveData.value?.get(stationName)
    }

    fun getHistoryForStation(stationName: String?): List<String> {
        if (stationName.isNullOrBlank()) return emptyList()
        return trackHistoryLiveData.value?.get(stationName) ?: emptyList()
    }

    @Synchronized
    fun addTrackUpdateListener(listener: TrackUpdateListener) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeTrackUpdateListener(listener: TrackUpdateListener) {
        listeners.remove(listener)
    }

    fun acquire() {
        if (!isConfigured()) return
        if (listenerCount.incrementAndGet() == 1) {
            startConnection()
        }
    }

    fun release() {
        if (listenerCount.decrementAndGet() <= 0) {
            listenerCount.set(0)
            stopConnection()
        }
    }

    private fun startConnection() {
        if (running) return
        running = true
        fetchHistoryAsync()
        sseThread = Thread({ runSseLoop() }, "ElzicySSE").also { it.start() }
    }

    private fun stopConnection() {
        running = false
        sseThread?.interrupt()
        sseThread = null
    }

    private fun fetchHistoryAsync() {
        Thread({
            try {
                fetchHistory()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to fetch history", t)
            }
        }, "ElzicyHistory").start()
    }

    private fun fetchHistory() {
        val request = buildRequest("/api/history") ?: return
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "History request failed: ${response.code}")
                return
            }
            val body = response.body?.string() ?: return
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            val history: Map<String, List<String>> = gson.fromJson(body, type) ?: emptyMap()
            trackHistoryLiveData.postValue(history)
        }
    }

    private fun runSseLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            val request = buildRequest("/api/stream-history")
            if (request == null) {
                Log.w(TAG, "Stopping Elzicy SSE: invalid or missing API URL")
                running = false
                break
            }
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "SSE request failed: ${response.code}")
                        sleepBeforeRetry()
                        return@use
                    }
                    val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                    var eventType: String? = null
                    val dataBuilder = StringBuilder()

                    while (running && !Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("event:") -> {
                                eventType = line.removePrefix("event:").trim()
                            }
                            line.startsWith("data:") -> {
                                if (dataBuilder.isNotEmpty()) dataBuilder.append('\n')
                                dataBuilder.append(line.removePrefix("data:").trim())
                            }
                            line.isEmpty() -> {
                                if (eventType == "track_update" && dataBuilder.isNotEmpty()) {
                                    handleTrackUpdate(dataBuilder.toString())
                                }
                                eventType = null
                                dataBuilder.clear()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running && !Thread.currentThread().isInterrupted) {
                    Log.w(TAG, "SSE connection error", t)
                }
            }
            sleepBeforeRetry()
        }
    }

    private fun handleTrackUpdate(json: String) {
        val type = object : TypeToken<Map<String, String>>() {}.type
        val snapshot: Map<String, String> = try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse track update", t)
            return
        }
        if (snapshot.isEmpty()) return

        nowPlayingLiveData.postValue(snapshot)

        synchronized(listeners) {
            for ((station, track) in snapshot) {
                for (listener in listeners) {
                    listener.onTrackUpdate(station, track)
                }
            }
        }

        fetchHistoryAsync()
    }

    private fun buildRequest(path: String): Request? {
        val baseUrl = normalizeBaseUrl(Preferences.getElzicyApiBaseUrl()) ?: return null
        val token = Preferences.getElzicyApiToken() ?: return null
        return try {
            Request.Builder()
                .url("$baseUrl$path")
                .header("X-App-Token", token)
                .header("User-Agent", "Tempus/${BuildConfig.VERSION_NAME}")
                .header("Accept", "text/event-stream")
                .build()
        } catch (t: IllegalArgumentException) {
            Log.w(TAG, "Invalid Elzicy API URL: $baseUrl", t)
            null
        }
    }

    private fun normalizeBaseUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var normalized = url.trim().trimEnd('/')
        if (!normalized.contains("://")) {
            val scheme = if (shouldUseHttp(normalized)) "http" else "https"
            normalized = "$scheme://$normalized"
        }
        return normalized
    }

    private fun shouldUseHttp(host: String): Boolean {
        val hostPart = host.substringBefore('/').substringBefore(':').lowercase()
        if (hostPart == "localhost") return true
        return hostPart.matches(Regex("""(\d{1,3}\.){3}\d{1,3}"""))
    }

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(SSE_RETRY_DELAY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "ElzicyClient"
        private const val SSE_RETRY_DELAY_MS = 5000L

        @Volatile
        private var instance: ElzicyClient? = null

        @JvmStatic
        fun getInstance(): ElzicyClient {
            return instance ?: synchronized(this) {
                instance ?: ElzicyClient().also { instance = it }
            }
        }
    }
}
