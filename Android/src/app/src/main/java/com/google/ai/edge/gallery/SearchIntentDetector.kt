package com.google.ai.edge.gallery

object SearchIntentDetector {
  private val searchKeywords = listOf(
    "搜索", "查找", "查询", "看看", "今天", "最新", "最近",
    "新闻", "资讯", "热点", "热搜", "排行榜", "事件",
    "search", "find", "look up", "today", "latest", "recent", "news"
  )

  fun detectSearchIntent(input: String): Boolean {
    val lowerInput = input.lowercase()
    return searchKeywords.any { keyword -> lowerInput.contains(keyword.lowercase()) }
  }
}
