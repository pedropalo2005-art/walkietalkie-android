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
    val description: String,
    val isPrivate: Boolean = false,
    val pin: String? = null
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

    val DEFAULT_ACCOUNT = AgoraAccount(
        id = 1,
        name = "Cuenta 1 (Pedro Palo)",
        appId = "ba0e5c03715641cabc82aeffb53fef0b",
        appCertificate = "daf388a556924186a409a28b0256d7f0",
        isActive = true
    )

    val DEFAULT_CHANNELS = listOf(
        RadioChannel("canal-1", "Canal 1 - General", "Frecuencia principal de uso general"),
        RadioChannel("canal-2", "Canal 2 - Logistica", "Coordinacion y logistica"),
        RadioChannel("canal-3", "Canal 3 - Emergencias", "Frecuencia prioritaria de emergencia")
    )

    private val httpClient = OkHttpClient()

    suspend fun getChannels(): List<RadioChannel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/channels?select=*&order=created_at.asc")
            .addHeader("apikey", ANON_KEY)
            .addHeader("Authorization", "Bearer $ANON_KEY")
            .build()

        val channels = mutableListOf<RadioChannel>()
        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext DEFAULT_CHANNELS
            val jsonArray = JSONArray(body)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                channels.add(
                    RadioChannel(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        isPrivate = obj.optBoolean("is_private", false),
                        pin = if (obj.has("pin") && !obj.isNull("pin")) obj.getString("pin") else null
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext DEFAULT_CHANNELS
        }
        if (channels.isEmpty()) DEFAULT_CHANNELS else channels
    }

    suspend fun createChannel(name: String, isPrivate: Boolean, pin: String?): Result<RadioChannel> = withContext(Dispatchers.IO) {
        val sanitized = name.lowercase().replace(Regex("[^a-z0-9]"), "-").take(15).trim('-')
        val prefix = if (isPrivate) "priv-" else "pub-"
        val uniqueId = "$prefix$sanitized-${System.currentTimeMillis() % 100000}"

        val json = JSONObject().apply {
            put("id", uniqueId)
            put("name", name.trim())
            put("description", if (isPrivate) "Canal privado protegido con PIN" else "Canal público abierto")
            put("is_private", isPrivate)
            if (isPrivate && !pin.isNullOrBlank()) {
                put("pin", pin.trim())
            }
        }

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/channels")
            .addHeader("apikey", ANON_KEY)
            .addHeader("Authorization", "Bearer $ANON_KEY")
            .addHeader("Prefer", "return=representation")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }
            val array = JSONArray(body)
            if (array.length() > 0) {
                val obj = array.getJSONObject(0)
                val channel = RadioChannel(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    isPrivate = obj.optBoolean("is_private", false),
                    pin = if (obj.has("pin") && !obj.isNull("pin")) obj.getString("pin") else null
                )
                return@withContext Result.success(channel)
            }
            Result.failure(Exception("Respuesta vacía del servidor"))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun getActiveAgoraAccount(): AgoraAccount = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/agora_pool?is_active=eq.true&limit=1")
            .addHeader("apikey", ANON_KEY)
            .addHeader("Authorization", "Bearer $ANON_KEY")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext DEFAULT_ACCOUNT
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
        DEFAULT_ACCOUNT
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

            // Find next available account
            val getNextReq = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/agora_pool?id=neq.$currentId&is_suspended=eq.false&limit=1")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer $ANON_KEY")
                .build()
            val nextResp = httpClient.newCall(getNextReq).execute()
            val nextBody = nextResp.body?.string() ?: return@withContext null
            val nextArr = JSONArray(nextBody)

            if (nextArr.length() > 0) {
                val nextObj = nextArr.getJSONObject(0)
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
