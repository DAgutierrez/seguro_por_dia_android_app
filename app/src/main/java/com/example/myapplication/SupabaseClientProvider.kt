package com.example.myapplication

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.UUID

object SupabaseClientProvider {
    private val httpClient = OkHttpClient()
    private const val BUCKET = "inspection-images"
    private val json = Json { ignoreUnknownKeys = true }

    fun uploadPng(byteArray: ByteArray, pathPrefix: String = ""): String {
        val fileName = (if (pathPrefix.isNotEmpty()) "$pathPrefix/" else "") + UUID.randomUUID().toString() + ".png"
        val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/$BUCKET/$fileName"

        android.util.Log.d("SupabaseUpload", "Starting upload for file: $fileName")
        android.util.Log.d("SupabaseUpload", "File size: ${byteArray.size} bytes")
        android.util.Log.d("SupabaseUpload", "Upload URL: $url")
        android.util.Log.d("SupabaseUpload", "Supabase URL: ${BuildConfig.SUPABASE_URL}")
        android.util.Log.d("SupabaseUpload", "API Key (first 20 chars): ${BuildConfig.SUPABASE_ANON_KEY.take(20)}...")
        android.util.Log.d("SupabaseUpload", "Bucket: $BUCKET")

        val body = RequestBody.create("image/png".toMediaType(), byteArray)
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("x-upsert", "true")
            .post(body)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            android.util.Log.d("SupabaseUpload", "Response received: ${resp.code} ${resp.message}")
            android.util.Log.d("SupabaseUpload", "Response headers: ${resp.headers}")
            
            if (!resp.isSuccessful) {
                val err = try { resp.body?.string() } catch (_: Throwable) { null }
                val errorMessage = "Upload failed: ${resp.code} ${err ?: "(no body)"}"
                android.util.Log.e("SupabaseUpload", "Error details: $errorMessage")
                android.util.Log.e("SupabaseUpload", "Response headers: ${resp.headers}")
                android.util.Log.e("SupabaseUpload", "Request URL: $url")
                throw IllegalStateException(errorMessage)
            }
            
            android.util.Log.d("SupabaseUpload", "Upload successful!")
        }

        // Public URL format
        val publicUrl = BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/$BUCKET/$fileName"
        android.util.Log.d("SupabaseUpload", "Public URL: $publicUrl")
        return publicUrl
    }

    fun getInspectionViews(): List<InspectionView> {
        val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/inspection-view"
        
        android.util.Log.d("SupabaseQuery", "Fetching inspection views from: $url")
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            android.util.Log.d("SupabaseQuery", "Response received: ${response.code} ${response.message}")
            
            if (!response.isSuccessful) {
                val errorBody = try { response.body?.string() } catch (_: Throwable) { null }
                val errorMessage = "Query failed: ${response.code} ${errorBody ?: "(no body)"}"
                android.util.Log.e("SupabaseQuery", "Error details: $errorMessage")
                throw IllegalStateException(errorMessage)
            }
            
            val responseBody = response.body?.string() ?: "[]"
            android.util.Log.d("SupabaseQuery", "Response body: $responseBody")
            
            return try {
                val jsonElement = json.parseToJsonElement(responseBody)
                json.decodeFromJsonElement<List<InspectionView>>(jsonElement)
            } catch (e: Exception) {
                android.util.Log.e("SupabaseQuery", "Failed to parse response: ${e.message}")
                emptyList()
            }
        }
    }
}


