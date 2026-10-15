package com.pixelstandby.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Client for the Pixel Standby backend.
 *
 * The client is deliberately small: one endpoint, bearer-token auth, JSON in and out.
 * It uses java.net.HttpURLConnection so no additional networking dependency is needed.
 * It does not log the token, and it never builds request URLs from untrusted input.
 * JSON is decoded with kotlinx.serialization directly from the response body text.
 */
class QuotesClient(
    private val baseUrl: String,
    private val bearerToken: String,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun openConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        return url.openConnection() as HttpURLConnection
    }

    /**
     * Fetch quotes for the given symbols.
     *
     * Returns [kotlin.Result.success] with the parsed DTOs on HTTP 200, or
     * [kotlin.Result.failure] on network errors, non-200 responses, or parse failures.
     */
    suspend fun fetchQuotes(symbols: List<String>): Result<List<QuoteResponseDto>> {
        return try {
            val query = symbols.joinToString(",")
            val urlString = "$baseUrl/api/quotes?symbols=$query"
            val connection = openConnection(urlString).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PixelStandby/1.0")
            }

            val status = connection.responseCode

            when (status) {
                200 -> {
                    val body = connection.inputStream.bufferedReader().readText()
                    Result.success(json.decodeFromString(body))
                }
                401 ->
                    Result.failure(
                        QuotesClientException("Unauthorized: check bearer token")
                    )
                else ->
                    Result.failure(
                        QuotesClientException("HTTP $status")
                    )
            }
        } catch (e: Exception) {
            Result.failure(QuotesClientException("Request failed: ${e.message}", e))
        }
    }
}

/** DTO matching the backend's QuoteResponse shape. */
@Serializable
data class QuoteResponseDto(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val currency: String,
    val asOf: String,
    val source: String,
    val stale: Boolean,
)

class QuotesClientException(message: String, cause: Throwable? = null) :
    Exception(message, cause)