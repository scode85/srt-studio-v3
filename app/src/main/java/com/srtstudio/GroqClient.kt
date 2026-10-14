package com.srtstudio

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object GroqClient {
    private const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    fun transcribe(apiKey: String, wav: File, language: String?): List<Segment> {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        b.addFormDataPart("file", wav.name,
            wav.asRequestBody("audio/wav".toMediaType()))
        b.addFormDataPart("model", "whisper-large-v3-turbo")
        b.addFormDataPart("response_format", "verbose_json")
        b.addFormDataPart("temperature", "0")
        if (!language.isNullOrBlank() && language != "auto")
            b.addFormDataPart("language", language)

        val req = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(b.build())
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful)
                throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
            val json = JSONObject(body)
            val arr = json.optJSONArray("segments") ?: return emptyList()
            val out = ArrayList<Segment>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                out.add(Segment(
                    (s.optDouble("start", 0.0) * 1000).toLong(),
                    (s.optDouble("end", 0.0) * 1000).toLong(),
                    s.optString("text", "").trim()))
            }
            return out
        }
    }
}
