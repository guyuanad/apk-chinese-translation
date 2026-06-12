package com.google.ai.edge.gallery

object SearchResultFormatter {
  private const val TAG = "SearchResultFormatter"

  fun formatSearchResults(results: List<SearchResult>): String {
    if (results.isEmpty()) {
      return ""
    }

    val sb = StringBuilder()
    sb.append("\n\n--- 搜索结果 ---\n\n")
    
    for ((index, result) in results.withIndex()) {
      sb.append("${index + 1}. ${result.title}\n")
      if (result.description.isNotEmpty()) {
        sb.append("   ${result.description}\n")
      }
      sb.append("   ${result.url}\n\n")
    }
    
    sb.append("--- 搜索结果结束 ---\n")
    return sb.toString()
  }

  fun buildEnhancedPrompt(originalInput: String, searchResults: List<SearchResult>): String {
    GlobalLogger.log(TAG, "FORMAT", "Building enhanced prompt")
    val formattedResults = formatSearchResults(searchResults)
    
    return if (formattedResults.isNotEmpty()) {
      """$originalInput
      
      以下是网络搜索到的相关信息，请基于这些信息回答问题：
      $formattedResults
      """.trimIndent()
    } else {
      originalInput
    }
  }
}
