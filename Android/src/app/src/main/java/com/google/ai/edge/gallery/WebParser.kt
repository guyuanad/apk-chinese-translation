package com.google.ai.edge.gallery

object WebParser {
  fun extractTitle(html: String): String {
    val titleRegex = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
    return titleRegex.find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
  }

  fun extractText(html: String): String {
    var text = html
    text = text.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
    text = text.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
    text = text.replace(Regex("<[^>]+>"), " ")
    text = text.replace(Regex("&nbsp;"), " ")
    text = text.replace(Regex("&lt;"), "<")
    text = text.replace(Regex("&gt;"), ">")
    text = text.replace(Regex("&amp;"), "&")
    text = text.replace(Regex("&quot;"), "\"")
    text = text.replace(Regex("\\s+"), " ")
    return text.trim()
  }

  fun extractLinks(html: String): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    val linkRegex = Regex("<a[^>]*href\\s*=\\s*[\"']([^\"]+)[\"'][^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
    for (match in linkRegex.findAll(html)) {
      val href = match.groupValues.getOrNull(1) ?: continue
      val linkText = extractText(match.groupValues.getOrNull(2) ?: "").trim()
      if (href.isNotEmpty()) {
        results.add(href to linkText)
      }
    }
    return results
  }

  fun extractImages(html: String): List<String> {
    val results = mutableListOf<String>()
    val imgRegex = Regex("<img[^>]*src\\s*=\\s*[\"']([^\"]+)[\"']", RegexOption.IGNORE_CASE)
    for (match in imgRegex.findAll(html)) {
      val src = match.groupValues.getOrNull(1) ?: continue
      if (src.isNotEmpty()) {
        results.add(src)
      }
    }
    return results
  }

  fun summarizeContent(html: String, maxLength: Int = 500): String {
    val text = extractText(html)
    return if (text.length <= maxLength) text else text.take(maxLength) + "..."
  }
}
