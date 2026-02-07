package org.fcitx.fcitx5.android.plugin.clipboard_sync.network

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object SyncClient {
    private const val TAG = "FcitxClipboardSync"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    @Throws(IOException::class)
    fun getClipboard(serverUrl: String, username: String, pass: String): String {
        Log.d(TAG, "[Pull] Fetching from $serverUrl")
        
        val credential = Credentials.basic(username, pass)
        val request = Request.Builder()
            .url(serverUrl)
            .header("Authorization", credential)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "[Pull] Failed: ${response.code} ${response.message}")
                throw IOException("Unexpected code $response")
            }

            val bodyString = response.body?.string() ?: ""
            // Log.d(TAG, "[Pull] Response: $bodyString") 
            // Don't log full body if it's too long or sensitive, maybe log length
            
            return try {
                val data = json.decodeFromString<ClipboardData>(bodyString)
                Log.d(TAG, "[Pull] Parsed content length: ${data.content.length}")
                data.content
            } catch (e: Exception) {
                Log.e(TAG, "[Pull] JSON parse error", e)
                throw IOException("Invalid JSON format", e)
            }
        }
    }

    fun putClipboard(serverUrl: String, username: String, pass: String, content: String) {
        Log.d(TAG, "[Push] Uploading content length: ${content.length} to $serverUrl")
        
        val data = ClipboardData(content = content)
        val jsonString = json.encodeToString(data)
        val body = jsonString.toRequestBody(JSON_MEDIA_TYPE)
        val credential = Credentials.basic(username, pass)

        val request = Request.Builder()
            .url(serverUrl)
            .header("Authorization", credential)
            .put(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "[Push] Failed: ${response.code} ${response.message}")
                } else {
                    Log.d(TAG, "[Push] Success")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "[Push] Network error", e)
        }
    }

    fun testConnection(serverUrl: String, username: String, pass: String): Result<String> {
        Log.d(TAG, "[Test] Testing connection to $serverUrl")
        return try {
            val credential = Credentials.basic(username, pass)
            val request = Request.Builder()
                .url(serverUrl)
                .header("Authorization", credential)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "[Test] Success")
                    Result.success("Connection Successful: ${response.code}")
                } else {
                    Log.e(TAG, "[Test] Failed: ${response.code}")
                    Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Test] Error", e)
            Result.failure(e)
        }
    }
}
