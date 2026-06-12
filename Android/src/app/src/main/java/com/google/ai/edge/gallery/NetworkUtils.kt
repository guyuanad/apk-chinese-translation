package com.google.ai.edge.gallery

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NetworkUtils {
  private const val TAG = "NetworkUtils"
  private val client = HttpClient(Android) {
    engine {
      connectTimeout = 30_000
      socketTimeout = 30_000
    }
  }

  suspend fun getRequest(url: String): String? = withContext(Dispatchers.IO) {
    GlobalLogger.log(TAG, "NET", "GET Request: $url")
    
    try {
      val response: HttpResponse = client.get(url)
      
      if (response.status.isSuccess()) {
        val body = response.bodyAsText()
        GlobalLogger.log(TAG, "NET", "Response received, ${body.length} bytes")
        return@withContext body
      } else {
        GlobalLogger.log(TAG, "ERR", "Request failed: ${response.status}")
      }
    } catch (e: Exception) {
      GlobalLogger.log(TAG, "ERR", "Network error: ${e.message}")
    }
    
    return@withContext null
  }

  suspend fun fetchWebPage(url: String): String? = getRequest(url)
}
