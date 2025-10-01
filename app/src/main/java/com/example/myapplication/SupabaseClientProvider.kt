package com.example.myapplication

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.UUID

object SupabaseClientProvider {
    private val httpClient = OkHttpClient()
    private const val BUCKET = "inspection-images"

    fun uploadPng(byteArray: ByteArray, pathPrefix: String = ""): String {
        val fileName = (if (pathPrefix.isNotEmpty()) "$pathPrefix/" else "") + UUID.randomUUID().toString() + ".png"
        val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/$BUCKET/$fileName"

        val body = RequestBody.create("image/png".toMediaType(), byteArray)
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("x-upsert", "true")
            .post(body)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = try { resp.body?.string() } catch (_: Throwable) { null }
                throw IllegalStateException("Upload failed: ${resp.code} ${err ?: "(no body)"}")
            }
        }

        // Public URL format
        return BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/$BUCKET/$fileName"
    }
}


