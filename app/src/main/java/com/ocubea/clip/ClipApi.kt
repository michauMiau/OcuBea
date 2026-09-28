package com.ocubea.clip

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Typed client for the clip API the app's own server exposes.
 *
 * The native screen deliberately reuses the HTTP surface instead of opening
 * files itself: one implementation of list/record/delete means a bug cannot be
 * fixed in the WebUI while still living in the Android screen.
 */
class ClipApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private val form = "application/x-www-form-urlencoded".toMediaType()

    data class Clip(
        val name: String,
        val size: Long,
        val modified: Long,
        val recording: Boolean,
    )

    data class Snapshot(
        val clips: List<Clip>,
        val count: Int,
        val bytes: Long,
        val free: Long,
        val recording: Boolean,
    )

    data class PruneResult(val removed: Int, val freedBytes: Long)

    fun list(): Snapshot {
        val o = get("/clips")
        val arr = o.optJSONArray("clips") ?: JSONArray()
        val out = ArrayList<Clip>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            out.add(
                Clip(
                    name = c.getString("name"),
                    size = c.optLong("size"),
                    modified = c.optLong("modified"),
                    recording = c.optBoolean("recording"),
                )
            )
        }
        // Newest first: the clip the user just recorded is the one they want.
        out.sortByDescending { it.modified }
        return Snapshot(
            clips = out,
            count = o.optInt("count", out.size),
            bytes = o.optLong("bytes"),
            free = o.optLong("free"),
            recording = o.optBoolean("recording"),
        )
    }

    fun isRecording(): Boolean = get("/clips/recording").optBoolean("recording")

    /** Starts an on-demand clip; [seconds] of 0 records until stopped. */
    fun startRecording(seconds: Int): String =
        post("/clips/record", "seconds=$seconds")

    fun stopRecording(): String = post("/clips/record/stop")

    fun deleteOne(name: String): String =
        request(Request.Builder().url("$baseUrl/clips/${encode(name)}").delete().build())

    fun deleteMany(names: Set<String>): String {
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        return post("/clips", JSONObject().put("names", arr).toString(), json)
    }

    fun prune(): PruneResult {
        val o = JSONObject(post("/clips/prune"))
        return PruneResult(o.optInt("removed"), o.optLong("freedBytes"))
    }

    // ── plumbing ───────────────────────────────────────────────

    private fun get(path: String): JSONObject =
        JSONObject(request(Request.Builder().url(baseUrl + path).get().build()))

    private fun post(path: String, body: String = "", type: okhttp3.MediaType? = null): String =
        request(
            Request.Builder().url(baseUrl + path)
                .post((if (body.isEmpty()) "" else body)
                    .toRequestBody(type ?: "text/plain".toMediaType()))
                .build()
        )

    private fun request(req: Request): String {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            return body
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
