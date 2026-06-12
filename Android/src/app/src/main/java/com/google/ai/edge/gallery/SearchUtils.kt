package com.google.ai.edge.gallery

import java.net.URLEncoder

data class SearchResult(
  val title: String,
  val description: String,
  val url: String
)

object SearchUtils {
  private const val TAG = "SearchUtils"

  fun buildSearchUrl(query: String): String {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    return "https://duckduckgo.com/html/?q=$encodedQuery"
  }

  suspend fun webSearch(query: String): List<SearchResult> {
    GlobalLogger.log(TAG, "SEARCH", "Searching for: $query")
    val results = mutableListOf<SearchResult>()
    
    try {
      val searchUrl = buildSearchUrl(query)
      val html = NetworkUtils.fetchWebPage(searchUrl) ?: return emptyList()
      
      // Parse DuckDuckGo HTML results (simplified)
      val resultRegex = Regex(
        "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
        RegexOption.DOT_MATCHES_ALL
      )
      
      for (match in resultRegex.findAll(html).take(5)) {
        var url = match.groupValues.getOrNull(1) ?: continue
        val title = WebParser.extractText(match.groupValues.getOrNull(2) ?: "").trim()
        val description = WebParser.extractText(match.groupValues.getOrNull(3) ?: "").trim()
        
        if (url.startsWith("/l/?uddg=")) {
          url = url.substringAfter("/l/?uddg=").substringBefore("&")
        }
        
        if (title.isNotEmpty() && url.isNotEmpty()) {
          results.add(SearchResult(title, description, url))
        }
      }
      
      GlobalLogger.log(TAG, "SEARCH", "Found ${results.size} results")
    } catch (e: Exception) {
      GlobalLogger.log(TAG, "ERR", "Search failed: ${e.message}")
    }
    
    return results
  }
}
