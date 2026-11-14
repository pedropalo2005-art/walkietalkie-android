package com.pedropalo.walkietalkie

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

data class RadioChannel(
    val id: String,
    val name: String,
    val description: String
)

data class AgoraAccount(
    val id: Int,
    val name: String,
    val appId: String,
    val appCertificate: String,
    val isActive: Boolean
)

object SupabaseClient {
    private const val SUPABASE_URL = "https://cdqdpnntmiirvhycufcp.supabase.co"
    private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNkcWRwbm50bWlpcnZoeWN1ZmNwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ1ODA2OTAsImV4cCI6MjExMDE1NjY5MH0.TfU_T25peO81chK6rGxjWltkgp2Od7oKwuqYNfL6dXs"

    private val httpClient = OkHttpClient()

    suspend fun getChannels(): List<RadioChannel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/channels?select=*&order=id.asc")
            .addHeader("apikey", ANON_KEY)
            .addHeader("Authorization", "Bearer $ANON_KEY")
            .build()

        val channels = mutableListOf<RadioChannel>()
        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            val jsonArray = JSONArray(body)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                channels.add(
                    RadioChannel(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        channels
    }

    suspend fun getActiveAgoraAccount(): AgoraAccount? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/agora_pool?is_active=eq.true&limit=1")
            .addHeader("apikey", ANON_KEY)
            .addHeader("Authorization", "Bearer $ANON_KEY")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val jsonArray = JSONArray(body)
            if (jsonArray.length() > 0) {
                val obj = jsonArray.getJSONObject(0)
                return@withContext AgoraAccount(
                    id = obj.getInt("id"),
                    name = obj.getString("account_name"),
                    appId = obj.getString("app_id").trim(),
                    appCertificate = obj.optString("app_certificate", "").trim(),
                    isActive = obj.getBoolean("is_active")
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun rotateToNextAccount(currentId: Int): AgoraAccount? = withContext(Dispatchers.IO) {
        try {
            val patchJson = JSONObject().apply {
                put("is_active", false)
                put("is_suspended", true)
            }
            val patchReq = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/agora_pool?id=eq.$currentId")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer $ANON_KEY")
                .patch(patchJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(patchReq).execute()

            val nextReq = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/agora_pool?is_suspended=eq.false&id=neq.$currentId&limit=1")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer $ANON_KEY")
                .build()
            val nextRes = httpClient.newCall(nextReq).execute()
            val body = nextRes.body?.string() ?: return@withContext null
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val nextObj = arr.getJSONObject(0)
                val nextId = nextObj.getInt("id")

                val activateJson = JSONObject().apply {
                    put("is_active", true)
                }
                val actReq = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/agora_pool?id=eq.$nextId")
                    .addHeader("apikey", ANON_KEY)
                    .addHeader("Authorization", "Bearer $ANON_KEY")
                    .patch(activateJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(actReq).execute()

                return@withContext AgoraAccount(
                    id = nextId,
                    name = nextObj.getString("account_name"),
                    appId = nextObj.getString("app_id").trim(),
                    appCertificate = nextObj.optString("app_certificate", "").trim(),
                    isActive = true
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
