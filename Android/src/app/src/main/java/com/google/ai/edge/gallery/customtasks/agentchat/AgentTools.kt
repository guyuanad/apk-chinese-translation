/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.withTimeoutOrNull

import android.content.Context
import android.os.Environment
import android.os.Bundle
import android.util.Log
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.common.AgentAction
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.AskMcpToolCallPermissionAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.CallJsSkillResult
import com.google.ai.edge.gallery.common.CallJsSkillResultImage
import com.google.ai.edge.gallery.common.CallJsSkillResultWebview
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.PermissionResult
import com.google.ai.edge.gallery.common.RequestPermissionAgentAction
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.common.convertStringToJsonObject
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

private const val TAG = "DIAG"

/**
 * Tracks tool call statistics for debugging and analytics.
 * Thread-safe using ConcurrentHashMap and AtomicInteger.
 */
object ToolCallStats {
  data class Stats(val success: AtomicInteger, val failure: AtomicInteger, val totalTime: AtomicLong)

  private val stats = ConcurrentHashMap<String, Stats>()

  /** Records a tool call. */
  fun record(toolName: String, success: Boolean, elapsedMs: Long) {
    val s = stats.getOrPut(toolName) { Stats(AtomicInteger(0), AtomicInteger(0), AtomicLong(0)) }
    if (success) s.success.incrementAndGet() else s.failure.incrementAndGet()
    s.totalTime.addAndGet(elapsedMs)
  }

  /** Returns all stats as a readable summary string. */
  fun getSummary(): String {
    if (stats.isEmpty()) return "No tool calls recorded yet."
    return stats.entries.joinToString("\n") { (name, s) ->
      val total = s.success.get() + s.failure.get()
      val avgTime = if (total > 0) s.totalTime.get() / total.toLong() else 0
      "$name: $total calls (${s.success.get()} ok, ${s.failure.get()} fail), avg ${avgTime}ms"
    }
  }

  /** Clears all stats. */
  fun reset() {
    stats.clear()
  }

  /** Returns stats for a specific tool. */
  fun getToolStats(toolName: String): Map<String, Any>? {
    val s = stats[toolName] ?: return null
    val total = s.success.get() + s.failure.get()
    val avgTime = if (total > 0) s.totalTime.get() / total.toLong() else 0L
    return mapOf(
      "tool_name" to toolName,
      "total_calls" to total,
      "success" to s.success.get(),
      "failure" to s.failure.get(),
      "avg_time_ms" to avgTime,
    )
  }
}

open class AgentTools(
  val skillService: SkillService,
  val mcpService: McpService,
) : ToolSet {
  lateinit var context: Context
  lateinit var taskId: String

  // --- File logging support ---
  private var logFile: File? = null
  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

  private fun initLogFile() {
    if (logFile == null) {
      val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      val dir = File(publicDownload, "agent_logs")
      val created = dir.mkdirs()
      logFile = File(dir, "tool_calls.txt")
      if (!logFile!!.exists()) {
        logFile!!.writeText("=== Tool Call Log (started ${dateFormat.format(Date())} ) ===\n")
      }
      Log.d(TAG, "[DIAG] initLogFile: ${logFile!!.absolutePath}")
    }
  }

  private fun writeLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
    val timestamp = dateFormat.format(Date())
    val line = "$timestamp $level/$tag: $message"
    when (level) {
      "D" -> Log.d(tag, message)
      "E" -> Log.e(tag, message, throwable)
      "W" -> Log.w(tag, message)
      else -> Log.d(tag, message)
    }
    try {
      initLogFile()
      if (logFile != null) {
        logFile!!.appendText(line + "\n")
        if (throwable != null) {
          logFile!!.appendText(throwable.stackTraceToString() + "\n")
        }
        Log.d(TAG, "[DIAG] Log written to file: ${logFile!!.absolutePath}")
      } else {
        Log.e(TAG, "[DIAG] writeLog FAILED: logFile is null after initLogFile()")
      }
    } catch (e: Exception) {
      Log.e(TAG, "[DIAG] writeLog FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
    }
  }

  private val callCounts = ConcurrentHashMap<String, AtomicInteger>()
  private val maxCallsPerTool = 10
  private var lastCaptureScreenTime: Long = 0  // Track when captureScreen was last called
  private var pendingAppOpen: Boolean = false   // Track if an app was just opened and needs captureScreen
  private var lastOpenedAppName: String? = null // Track last opened app to prevent re-opening

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  val actionChannel: ReceiveChannel<AgentAction> = _actionChannel
  var resultImageToShow: CallJsSkillResultImage? = null
  var resultWebviewToShow: CallJsSkillResultWebview? = null

  private fun checkCallLimit(toolName: String): Boolean {
    val count = callCounts.computeIfAbsent(toolName) { AtomicInteger(0) }.incrementAndGet()
    writeLog("D", TAG, "=== Tool call count: $toolName = $count (max: $maxCallsPerTool) ===")
    if (count > maxCallsPerTool) {
      writeLog("E", TAG, "=== TOOL CALL LIMIT EXCEEDED: $toolName called $count times! Blocking. ===")
      return false
    }
    return true
  }

  fun resetCallCounts() {
    callCounts.clear()
    lastCaptureScreenTime = 0
    pendingAppOpen = false
    lastOpenedAppName = null
    writeLog("D", TAG, "Tool call counts and state reset.")
  }

  private suspend fun <T> withToolLogging(
    toolName: String,
    block: suspend () -> T
  ): T {
    val startTime = System.currentTimeMillis()
    writeLog("D", TAG, ">>> TOOL START: $toolName")
    try {
      val result = block()
      val elapsed = System.currentTimeMillis() - startTime
      val resultSummary = when (result) {
        is Map<*, *> -> {
          val keys = result.keys.joinToString(", ")
          val sizeStr = result.entries.joinToString(", ") { "${it.key}=${
            when (it.value) {
              is String -> "\"${(it.value as String).take(100)}\""
              else -> it.value?.toString()?.take(50)
            }
          }" }
          "Map($keys) [$sizeStr]"
        }
        else -> result?.toString()?.take(100)
      }
      writeLog("D", TAG, "<<< TOOL OK: $toolName (${elapsed}ms) result=$resultSummary")
      ToolCallStats.record(toolName, success = true, elapsedMs = elapsed)
      return result
    } catch (e: Exception) {
      val elapsed = System.currentTimeMillis() - startTime
      writeLog("E", TAG, "!!! TOOL FAIL: $toolName (${elapsed}ms) error=${e.message}", e)
      ToolCallStats.record(toolName, success = false, elapsedMs = elapsed)
      throw e
    }
  }

  @Tool(description = "Loads a skill.")
  suspend fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    if (!checkCallLimit("loadSkill")) {
      return mapOf("error" to "Too many calls to loadSkill", "status" to "blocked")
    }
    if (pendingAppOpen) {
      return mapOf("status" to "error", "message" to "You just opened an app. You MUST call captureScreen() first to see the app's screen before doing anything else.")
    }
    return withToolLogging("loadSkill") {
      loadSkillInternal(skillName)
    }
  }

  private suspend fun loadSkillInternal(skillName: String): Map<String, String> {
    val skills = skillService.getSelectedSkills()
    val skill = skills.find { it.name == skillName.trim() }
    val skillContent =
      if (skill != null) {
        "---\nname: ${skill.name}\ndescription: ${skill.description}\n---\n\n${skill.instructions}"
      } else {
        "Skill not found"
      }
    writeLog("D", TAG, "load skill. Skill content:\n$skillContent")
    if (skill != null) {
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Loading skill \"$skillName\"",
          inProgress = true,
          addItemTitle = "Load \"${skill.name}\"",
          addItemDescription = "Description: ${skill.description}",
          customData = skill,
        )
      )
    } else {
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Failed to load skill \"$skillName\"",
          inProgress = false,
        )
      )
    }
    return mapOf("skill_name" to skillName, "skill_instructions" to skillContent)
  }

  @Tool(description = "Run a MCP tool")
  fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.IO) {
      if (!checkCallLimit("runMcpTool")) {
        return@runBlocking mapOf("error" to "Too many calls to runMcpTool", "status" to "blocked")
      }
      withToolLogging("runMcpTool") {
        runMcpToolInternal(toolName, input)
      }
    }
  }

  private suspend fun runMcpToolInternal(toolName: String, input: String): Map<String, String> {
    writeLog("D", TAG, "Run MCP tool:\n- name: $toolName\n- input: $input")

    val serverState =
      mcpService.serversState.value.find { serverState ->
        serverState.mcpServer.toolsList.any { it.name == toolName }
      }

    if (serverState == null) {
      writeLog("W", TAG, "MCP server or tool not found for: $toolName")
      logMcpExecution(success = false, errorType = "tool_not_found")
      return guardMissingEntityWithSkillFallback(name = toolName, type = "Tool")
    }

    val client = serverState.client
    if (client == null) {
      logMcpExecution(success = false, errorType = "client_not_initialized")
      return mapOf("error" to "Client not initialized", "status" to "failed")
    }

    val mcpTool = serverState.mcpServer.toolsList.find { it.name == toolName }
    val isAlwaysAllow = mcpTool?.alwaysAllow ?: false

    if (!isAlwaysAllow) {
      val permissionAction = AskMcpToolCallPermissionAction(toolName = toolName, argument = input)
      _actionChannel.send(permissionAction)
      val permissionResult = permissionAction.result.await()
      if (permissionResult == PermissionResult.DENY) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Permission denied for MCP tool \"$toolName\"",
            inProgress = false,
          )
        )
        logMcpExecution(success = false, errorType = "permission_denied")
        return mapOf("error" to "Permission denied by user", "status" to "failed")
      }
    }

    try {
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Calling MCP tool \"$toolName\"",
          inProgress = true,
          addItemTitle = "Call MCP tool: \"$toolName\"",
          addItemDescription = "- Input: $input",
        )
      )
      val result =
      client.callTool(
        request = CallToolRequest(
          CallToolRequestParams(
            name = toolName,
            arguments = kotlinx.serialization.json.Json.parseToJsonElement(input).jsonObject
          )
        )
      )

      if (result == null) {
        writeLog("D", TAG, "Tool execution returned null result")
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to call MCP tool \"$toolName\"",
            inProgress = false,
          )
        )
        logMcpExecution(success = false, errorType = "null_result")
        return mapOf("error" to "Null result", "status" to "failed")
      }

      if (result.isError == true) {
        val errorText =
          result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
        writeLog("E", TAG, "MCP tool \"$toolName\" failed: $errorText")
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to call MCP tool \"$toolName\"",
            addItemTitle = "Call MCP tool \"$toolName\" failed",
            addItemDescription = errorText,
            inProgress = false,
          )
        )
        logMcpExecution(success = false, errorType = "tool_error")
        return mapOf("error" to errorText, "status" to "failed")
      } else {
        val successText =
          result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
        writeLog("D", TAG, "MCP tool \"$toolName\" succeeded:\n$successText")
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Succeeded calling MCP tool \"$toolName\"",
            inProgress = true,
            addItemTitle = "Call MCP tool \"$toolName\" succeeded",
            addItemDescription = successText,
          )
        )
        logMcpExecution(success = true, errorType = "")
        return mapOf("result" to successText, "status" to "succeeded")
      }
    } catch (e: Exception) {
      writeLog("E", TAG, "Error calling MCP tool", e)
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Error calling MCP tool \"$toolName\"",
          inProgress = false,
          addItemTitle = "Call MCP tool \"$toolName\" failed",
          addItemDescription = e.message ?: "Unknown error",
        )
      )
      logMcpExecution(success = false, errorType = "exception")
      return mapOf("error" to (e.message ?: "Unknown error"), "status" to "failed")
    }
  }

  @Tool(description = "Runs JS script")
  fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(
      description = "The data to pass to the script. Use empty string if not provided by user"
    )
    data: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("runJs")) {
        return@runBlocking mapOf("error" to "Too many calls to runJs", "status" to "blocked")
      }
      withToolLogging("runJs") {
        runJsInternal(skillName, scriptName, data)
      }
    }
  }

  private suspend fun runJsInternal(
    skillName: String,
    scriptName: String,
    data: String,
  ): Map<String, Any> {
    writeLog(
      "D",
      TAG,
      "runJs tool called with:" +
        "\n- skillName: ${skillName}\n- scriptName: ${scriptName}\n- data: ${data}\n",
    )

    val skills = skillService.getSelectedSkills()
    val skill = skills.find { it.name == skillName.trim() }

    if (skill == null) {
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Failed to call skill \"$scriptName\"",
          inProgress = false,
        )
      )
      return mapOf(
        "error" to "Skill \"${scriptName}\" not found",
        "status" to "failed",
      )
    }

    var secret = ""
    if (skill.requireSecret) {
      val savedSecret =
        skillService.readSecret(
          key = getSkillSecretKey(skillName = skillName)
        )
      if (savedSecret == null || savedSecret.isEmpty()) {
        val action =
          AskInfoAgentAction(
            dialogTitle = "Enter secret",
            fieldLabel =
              skill.requireSecretDescription.ifEmpty {
                "The JS script needs a secret (API key / token) to proceed:"
              },
          )
        _actionChannel.send(action)
        secret = action.result.await()
        if (secret.isNotEmpty()) {
          skillService.saveSecret(
            key = getSkillSecretKey(skillName = skillName),
            value = secret,
          )
          writeLog("D", TAG, "Got Secret from ask info dialog: ${secret.substring(0, 3)}")
        } else {
          writeLog("D", TAG, "The ask info dialog got cancelled. No secret.")
        }
      } else {
        secret = savedSecret
      }
    }

    val url =
      skillService.getJsSkillUrl(skillName = skillName, scriptName = scriptName)
        ?: return mapOf(
          "result" to "JS Skill URL not set properly or skill not found"
        )
    writeLog("D", TAG, "Calling JS script.\n- url: $url\n- data: $data")

    _actionChannel.send(
      SkillProgressAgentAction(
        label = "Calling JS script \"${skillName}/${scriptName}\"",
        inProgress = true,
        addItemTitle = "Call JS script: \"${skillName}/${scriptName}\"",
        addItemDescription = "- URL: ${url.replace(LOCAL_URL_BASE, "")}\n- Data: $data",
        customData = skill,
      )
    )

    val action =
      CallJsAgentAction(url = url, data = data.trim().ifEmpty { "{}" }, secret = secret)
    _actionChannel.send(action)
    writeLog("D", TAG, "Waiting for WebView result... (15s timeout)")
    val result = withTimeoutOrNull(15_000L) {
      action.result.await()
    } ?: run {
      writeLog("E", TAG, "WebView timed out after 15s in runJs!")
      return mapOf("error" to "JS execution timed out (15s). WebView did not return a result.", "status" to "failed")
    }

    val moshi: Moshi = Moshi.Builder().build()
    val jsonAdapter: JsonAdapter<CallJsSkillResult> =
      moshi.adapter(CallJsSkillResult::class.java).failOnUnknown()
    val resultJson = runCatching { jsonAdapter.fromJson(result) }.getOrNull()
    val error = resultJson?.error

    if (
      resultJson == null ||
        (resultJson.result == null && resultJson.webview == null && resultJson.image == null)
    ) {
      return mapOf("result" to result, "status" to "succeeded")
    } else if (error != null) {
      return mapOf("error" to error, "status" to "failed")
    } else {
      val image = resultJson.image
      val webview = resultJson.webview
      if (image != null) {
        writeLog("D", TAG, "Got an image response.")
        resultImageToShow = image
      }
      if (webview != null) {
        writeLog("D", TAG, "Got an webview response.")
        val webviewUrl =
          skillService.getJsSkillWebviewUrl(
            skillName = skillName,
            url = webview.url ?: "",
          )
        writeLog("D", TAG, "Webview url: $webviewUrl")
        resultWebviewToShow = webview.copy(url = webviewUrl)
      }
      writeLog("D", TAG, "Result: ${resultJson.result}")
      return mapOf("result" to (resultJson.result ?: ""), "status" to "succeeded")
    }
  }

  @Tool(
    description =
      "Convenience tool: learn about any topic in one step. Automatically queries Wikipedia " +
        "and returns the result. Use this instead of manually calling loadSkill + runJs for " +
        "the learn-something-new skill."
  )
  fun learnAbout(
    @ToolParam(description = "The topic to learn about (a concrete entity name, e.g., 'Black Hole', 'Agent').")
    topic: String,
    @ToolParam(
      description =
        "2-letter language code (e.g., 'en', 'zh'). Defaults to 'en' if not provided."
    )
    language: String = "en",
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("learnAbout")) {
        return@runBlocking mapOf("error" to "Too many calls to learnAbout", "status" to "blocked")
      }
      if (pendingAppOpen) {
        return@runBlocking mapOf("status" to "error", "message" to "You just opened an app. You MUST call captureScreen() first to see the app's screen before doing anything else.")
      }
      withToolLogging("learnAbout") {
        learnAboutInternal(topic, language)
      }
    }
  }

  private suspend fun learnAboutInternal(topic: String, language: String): Map<String, Any> {
    writeLog("D", TAG, "learnAbout tool called. topic=$topic, lang=$language")

    val skills = skillService.getSelectedSkills()
    val skill = skills.find { it.name == "learn-something-new" }
    if (skill == null) {
      return mapOf(
        "error" to "learn-something-new skill not available",
        "status" to "failed",
      )
    }

    var secret = ""
    if (skill.requireSecret) {
      val savedSecret = skillService.readSecret(key = getSkillSecretKey(skillName = "learn-something-new"))
      if (savedSecret != null && savedSecret.isNotEmpty()) {
        secret = savedSecret
      }
    }

    val queryData = """{"topic":"$topic","lang":"$language"}"""
    val queryUrl = skillService.getJsSkillUrl(skillName = "learn-something-new", scriptName = "query.html")

    if (queryUrl == null) {
      return mapOf(
        "error" to "Failed to resolve query.html URL for learn-something-new skill",
        "status" to "failed",
      )
    }

    _actionChannel.send(
      SkillProgressAgentAction(
        label = "Learning about \"$topic\"",
        inProgress = true,
        addItemTitle = "Query Wikipedia: \"$topic\"",
        addItemDescription = "- Topic: $topic\n- Language: $language",
        customData = skill,
      )
    )

    val queryAction = CallJsAgentAction(url = queryUrl, data = queryData.trim(), secret = secret)
    _actionChannel.send(queryAction)
    writeLog("D", TAG, "Waiting for WebView result... (15s timeout)")
    val queryResult = withTimeoutOrNull(15_000L) {
      queryAction.result.await()
    } ?: run {
      writeLog("E", TAG, "WebView timed out after 15s in learnAbout!")
      return mapOf("error" to "Wikipedia query timed out (15s). WebView did not return a result.", "status" to "failed")
    }

    val queryJson = runCatching {
      Json.parseToJsonElement(queryResult).jsonObject
    }.getOrNull()

    val error = queryJson?.get("error")?.toString()
    if (error != null) {
      return mapOf("error" to error, "status" to "failed")
    }

    val rawResult = queryJson?.get("result")?.toString()?.replace("\"", "") ?: ""
    writeLog("D", TAG, "Wikipedia query result: $rawResult")

    if (rawResult == "Not found" || rawResult.isEmpty()) {
      return mapOf(
        "result" to "Not found. No Wikipedia entry for '$topic'.",
        "status" to "succeeded",
      )
    }

    return mapOf("result" to rawResult, "status" to "succeeded")
  }

  @Tool(
    description =
      "Convenience tool: search the web for real-time information. " +
        "Automatically queries the web (via DuckDuckGo) and returns results. " +
        "Use this for current news, live data, weather, stock prices, or any " +
        "information that might not be in your training data."
  )
  fun searchWeb(
    @ToolParam(description = "The search query. Be concise and specific, e.g., 'AI news today', 'weather Beijing'.")
    query: String,
    @ToolParam(
      description =
        "Number of results to return (default 5). Use 3-10."
    )
    numResults: Int = 5,
  ): Map<String, Any> {
    return try {
      runBlocking(Dispatchers.Default) {
        if (!checkCallLimit("searchWeb")) {
          return@runBlocking mapOf("error" to "Too many calls to searchWeb", "status" to "blocked")
        }
        // Block searchWeb if an app was just opened and captureScreen hasn't been called yet
        if (pendingAppOpen) {
          return@runBlocking mapOf(
            "status" to "error",
            "message" to "You just opened an app. You MUST call captureScreen() first to see the app's screen before doing anything else. Do NOT use searchWeb now."
          )
        }
        withToolLogging("searchWeb") {
          searchWebInternal(query, numResults)
        }
      }
    } catch (e: Exception) {
      writeLog("E", TAG, "searchWeb crashed: ${e.message}", e)
      mapOf("error" to "searchWeb crashed: ${e.message ?: "unknown error"}", "status" to "failed")
    }
  }

  private suspend fun searchWebInternal(query: String, numResults: Int): Map<String, Any> {
    writeLog("D", TAG, "searchWeb tool called. query=$query, numResults=$numResults")

    val skills = skillService.getSelectedSkills()
    val skill = skills.find { it.name == "search-web" }
    if (skill == null) {
      return mapOf(
        "error" to "search-web skill not available",
        "status" to "failed",
      )
    }

    var secret = ""
    if (skill.requireSecret) {
      val savedSecret = skillService.readSecret(key = getSkillSecretKey(skillName = "search-web"))
      if (savedSecret != null && savedSecret.isNotEmpty()) {
        secret = savedSecret
      }
    }

    val searchData = """{"query":"${query.replace("\"", "\\\"")}","num_results":$numResults}"""
    val searchUrl = skillService.getJsSkillUrl(skillName = "search-web", scriptName = "index.html")

    if (searchUrl == null) {
      return mapOf(
        "error" to "Failed to resolve index.html URL for search-web skill",
        "status" to "failed",
      )
    }

    _actionChannel.send(
      SkillProgressAgentAction(
        label = "Searching web for \"$query\"",
        inProgress = true,
        addItemTitle = "Web Search: \"$query\"",
        addItemDescription = "- Query: $query\n- Results: $numResults",
        customData = skill,
      )
    )

    val searchAction = CallJsAgentAction(url = searchUrl, data = searchData.trim(), secret = secret)
    _actionChannel.send(searchAction)
    writeLog("D", TAG, "Waiting for WebView result... (15s timeout)")
    val searchResult = withTimeoutOrNull(15_000L) {
      searchAction.result.await()
    }
    if (searchResult == null) {
      writeLog("E", TAG, "WebView timed out after 15s! No result from search-web skill.")
      return mapOf("error" to "Search timed out. WebView did not return a result within 15 seconds.", "status" to "failed")
    }
    writeLog("D", TAG, "WebView returned: ${searchResult.take(200)}")

    val searchJson = runCatching {
      Json.parseToJsonElement(searchResult).jsonObject
    }.getOrNull()

    val error = searchJson?.get("error")?.toString()
    if (error != null) {
      return mapOf("error" to error, "status" to "failed")
    }

    val rawResult = searchJson?.get("result")?.toString()?.replace("\"", "") ?: ""
    writeLog("D", TAG, "Web search result: $rawResult")

    if (rawResult.isEmpty()) {
      return mapOf(
        "result" to "No results found for '$query'.",
        "status" to "succeeded",
      )
    }

    return mapOf("result" to rawResult, "status" to "succeeded")
  }

  // --- UI Automation Tools ---

  @Tool(description = "Capture the current screen. Returns screenshot path, foreground app package name, and a list of interactive UI elements with their bounds, text, content description, and class name.")
  fun captureScreen(): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("captureScreen")) {
        return@runBlocking mapOf("error" to "Too many calls to captureScreen", "status" to "blocked")
      }
      withToolLogging("captureScreen") {
        val result = UiAutomationTools.captureScreen(context)
        if (result["status"] == "success") {
          lastCaptureScreenTime = System.currentTimeMillis()
          pendingAppOpen = false
        }
        result
      }
    }
  }

  @Tool(
    description =
      "Perform UI automation actions on the current screen. " +
        "Actions: tap (tap by coordinates), tap_element (tap element by index), " +
        "type_text (type text into input), swipe (swipe between coordinates), " +
        "keyevent (send key event), back (navigate back), home (go to home), " +
        "scroll (scroll in direction: up/down/left/right), wait (wait for milliseconds). " +
        "Call captureScreen first to see current screen elements."
  )
  fun uiAutomation(
    @ToolParam(description = "The action to perform: tap, tap_element, type_text, swipe, keyevent, back, home, scroll, or wait.")
    action: String,
    @ToolParam(description = "JSON string of parameters. tap: {\"x\": int, \"y\": int}, type_text: {\"text\": \"string\"}, swipe: {\"x1\": int, \"y1\": int, \"x2\": int, \"y2\": int, \"duration\": int}, keyevent: {\"keycode\": \"string\"}, back/home: {}, scroll: {\"direction\": \"string\"}, wait: {\"timeout_ms\": int}")
    parameters: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("uiAutomation")) {
        return@runBlocking mapOf("error" to "Too many calls to uiAutomation", "status" to "blocked")
      }
      // Force captureScreen before uiAutomation
      if (lastCaptureScreenTime == 0L) {
        return@runBlocking mapOf(
          "status" to "error",
          "action" to action,
          "message" to "You MUST call captureScreen() first before using uiAutomation. Call captureScreen() now to see the screen elements."
        )
      }
      withToolLogging("uiAutomation") {
        val result = UiAutomationTools.executeUiAction(context, action, parameters)
        // Guide the model to capture screen after UI action
        if (result["status"] == "success") {
          // Invalidate captureScreen time so model must call it again before next uiAutomation
          lastCaptureScreenTime = 0L

          // Context-aware hint based on what action was just performed
          val contextHint = when (action) {
            "tap_element" -> {
              val details = result["details"] as? String ?: ""
              if (details.contains("Clicked element")) {
                "Element tapped. The screen may have changed. Call captureScreen() NOW to see the new screen and get the next action hint."
              } else {
                "Action completed. Call captureScreen() to see the updated screen."
              }
            }
            "type_text" -> {
              "Text typed. Call captureScreen() NOW to verify the text was entered and get the next action hint (e.g., how to submit)."
            }
            "keyevent" -> {
              "Key pressed. Call captureScreen() NOW to see if the screen changed (e.g., search results appeared)."
            }
            "scroll" -> {
              "Scrolled. Call captureScreen() NOW to see the new content on screen."
            }
            "back" -> {
              "Back pressed. Call captureScreen() NOW to see the previous screen."
            }
            "home" -> {
              "Home pressed. Call captureScreen() NOW to see the home screen."
            }
            else -> {
              "Action completed. Call captureScreen() to see the updated screen and get the next action hint."
            }
          }
          result + ("hint" to contextHint)
        } else {
          // On error, suggest capturing screen to reassess
          result + ("hint" to "Action failed. Call captureScreen() to see the current screen state, then try a different approach.")
        }
      }
    }
  }

  @Tool(
    description =
      "Run Android Intent operations. " +
        "Actions: open_app (open an app), send_email, send_sms, " +
        "create_calendar_event, get_current_date_and_time, etc. " +
        "For open_app, pass the app's DISPLAY NAME (e.g. '抖音', '小红书', 'DeepSeek', '番茄免费小说') " +
        "OR package_name (e.g. 'com.ss.android.ugc.aweme'). " +
        "If you don't know the package name, use the display name."
  )
  fun runIntent(
    @ToolParam(description = "The action to run: open_app, send_email, send_sms, create_calendar_event, get_current_date_and_time, etc.") intent: String,
    @ToolParam(description = "JSON string of parameters. For open_app: {\"package_name\": \"app display name or package name\"}")
    parameters: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("runIntent")) {
        return@runBlocking mapOf("error" to "Too many calls to runIntent", "status" to "blocked")
      }
      // Block repeated open_app if app was just opened and captureScreen hasn't been called
      if (pendingAppOpen && intent == "open_app") {
        return@runBlocking mapOf(
          "status" to "error",
          "action" to intent,
          "message" to "App already opened. You MUST call captureScreen() first to see the screen before doing anything else. Do NOT call runIntent again."
        )
      }
      // Block open_app if the same app was already opened in this session
      if (intent == "open_app") {
        val targetApp = extractPackageNameFromParams(parameters)
        if (targetApp != null && targetApp == lastOpenedAppName) {
          return@runBlocking mapOf(
            "status" to "error",
            "action" to intent,
            "message" to "You already opened $targetApp. Do NOT open it again. Use captureScreen() to see the current screen, or uiAutomation() to interact with elements."
          )
        }
      }
      withToolLogging("runIntent") {
        runIntentInternal(intent, parameters)
      }
    }
  }

  private suspend fun runIntentInternal(intent: String, parameters: String): Map<String, String> {
    if (IntentAction.from(intent) == null) {
      writeLog("W", TAG, "Intent not found: '$intent'")
      return guardMissingEntityWithSkillFallback(name = intent, type = "Intent")
    }
    writeLog("D", TAG, "Run intent. Intent: '$intent', parameters: '$parameters'")
    _actionChannel.send(
      SkillProgressAgentAction(
        label = "Executing intent \"$intent\"",
        inProgress = true,
        addItemTitle = "Execute intent \"$intent\"",
        addItemDescription = "Parameters: $parameters",
      )
    )
    val res =
      IntentHandler.handleAction(context, intent, parameters) { permission ->
        val permissionAction = RequestPermissionAgentAction(permission = permission)
        _actionChannel.send(permissionAction)
        permissionAction.result.await()
      }
    val result = mapOf("action" to intent, "parameters" to parameters, "result" to res)
    // Set pending flag and guide the model to continue with captureScreen after opening an app
    if (intent == "open_app" && res == "succeeded") {
      pendingAppOpen = true
      lastCaptureScreenTime = 0L
      // Track the opened app name to prevent re-opening
      lastOpenedAppName = extractPackageNameFromParams(parameters)
      return result + ("hint" to "App opened successfully. You MUST now call captureScreen() to see the app's UI. Do NOT call runIntent again. Do NOT use searchWeb. Only captureScreen() is allowed next.")
    }
    return result
  }

  @Tool(
    description =
      "Open an app and search for something. Use this when the user wants to search in an app. " +
        "This handles the ENTIRE flow automatically: open app → tap search button → type query → press enter. " +
        "Example: searchInApp(\"抖音\", \"科技视频\"), searchInApp(\"小红书\", \"美食\"). " +
        "ALWAYS prefer this over runIntent+captureScreen+uiAutomation for search tasks."
  )
  fun searchInApp(
    @ToolParam(description = "The app name to search in, e.g., '抖音', '小红书', '淘宝'")
    appName: String,
    @ToolParam(description = "The search query, e.g., '科技视频', '美食', '手机'")
    query: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("searchInApp")) {
        return@runBlocking mapOf("error" to "Too many calls to searchInApp", "status" to "blocked")
      }
      withToolLogging("searchInApp") {
        val escapedAppName = appName.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedQuery = query.replace("\\", "\\\\").replace("\"", "\\\"")

        // === PHASE 1: Open the app ===
        _actionChannel.send(SkillProgressAgentAction(
          label = "Opening $appName...", inProgress = true,
          addItemTitle = "Open $appName", addItemDescription = "Opening app: $appName"
        ))
        val openResult = runIntentInternal("open_app", "{\"package_name\": \"$escapedAppName\"}")
        writeLog("D", TAG, "searchInApp: Open app result: ${openResult["result"]}")
        if (openResult["result"] != "succeeded") {
          return@withToolLogging mapOf("status" to "error", "step" to "open_app",
            "message" to "Failed to open $appName. Error: ${openResult["result"]}")
        }
        delay(3000)

        // === PHASE 2: Find and tap search button (with retry) ===
        var searchTapped = false
        for (attempt in 1..3) {
          writeLog("D", TAG, "searchInApp: Finding search button, attempt $attempt")
          val screen = UiAutomationTools.captureScreen(context)
          val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()
          val searchIdx = UiAutomationTools.findSearchElementIndex(elements)

          if (searchIdx != null) {
            writeLog("D", TAG, "searchInApp: Found search element at index $searchIdx, tapping")
            val tapResult = UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $searchIdx}")
            if (tapResult["status"] == "success") {
              searchTapped = true
              break
            }
          }
          writeLog("D", TAG, "searchInApp: Search button not found or tap failed, retrying in 1s")
          delay(1000)
        }
        if (!searchTapped) {
          pendingAppOpen = false; lastCaptureScreenTime = System.currentTimeMillis()
          return@withToolLogging mapOf("status" to "error", "step" to "find_search",
            "message" to "Could not find search element in $appName after 3 attempts.")
        }
        delay(2000)

        // === PHASE 3: Find and focus input field (with retry) ===
        var inputFocused = false
        for (attempt in 1..5) {
          writeLog("D", TAG, "searchInApp: Finding input field, attempt $attempt")
          val screen = UiAutomationTools.captureScreen(context)
          val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

          // Check if we're actually on the search page (look for search-related elements)
          val onSearchPage = elements.any { el ->
            val text = el["text"] as? String ?: ""
            val desc = el["content_description"] as? String ?: ""
            val cls = el["class"] as? String ?: ""
            (el["is_editable"] as? Boolean ?: false) ||
            cls.contains("EditText") || cls.contains("SearchView") ||
            text.contains("搜索") || desc.contains("搜索") ||
            text.contains("猜你想搜") || text.contains("搜索历史")
          }

          if (!onSearchPage) {
            writeLog("D", TAG, "searchInApp: Not on search page yet, waiting 1s")
            delay(1000)
            continue
          }

          // Look for editable field
          val inputIdx = elements.indexOfFirst { el ->
            (el["is_editable"] as? Boolean ?: false) ||
            (el["class"] as? String ?: "").let { it.contains("EditText") || it.contains("SearchView") }
          }
          if (inputIdx >= 0) {
            val idx = elements[inputIdx]["index"] as? Int ?: continue
            writeLog("D", TAG, "searchInApp: Found input field at index $idx, tapping")
            UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
            inputFocused = true
            break
          }
          writeLog("D", TAG, "searchInApp: On search page but no input field found, retrying in 1s")
          delay(1000)
        }
        if (!inputFocused) {
          writeLog("D", TAG, "searchInApp: No input field found, will try direct accessibility input")
        }
        delay(500)

        // === PHASE 4: Type the query (with retry and verification) ===
        _actionChannel.send(SkillProgressAgentAction(
          label = "Typing: $query", inProgress = true,
          addItemTitle = "Type query", addItemDescription = "Typing: $query"
        ))
        var textTyped = false
        for (attempt in 1..5) {
          writeLog("D", TAG, "searchInApp: Typing query, attempt $attempt")

          // Try direct accessibility type first
          val result = UiAutomationTools.typeTextViaAccessibility(query)
          writeLog("D", TAG, "searchInApp: typeTextViaAccessibility result=$result")

          if (!result) {
            // Fallback to type_text
            val fallback = UiAutomationTools.executeUiAction(context, "type_text", "{\"text\": \"$escapedQuery\"}")
            writeLog("D", TAG, "searchInApp: type_text fallback result: ${fallback["status"]}")
          }

          delay(500)

          // VERIFY: Check if the text actually appears in an input field
          val verifyScreen = UiAutomationTools.captureScreen(context)
          val verifyElements = verifyScreen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()
          val textInInput = verifyElements.any { el ->
            val editable = el["is_editable"] as? Boolean ?: false
            val text = el["text"] as? String ?: ""
            editable && text.contains(query)
          }

          if (textInInput) {
            writeLog("D", TAG, "searchInApp: Text verified in input field")
            textTyped = true
            break
          } else {
            writeLog("D", TAG, "searchInApp: Text not found in input field, retrying (attempt $attempt)")
            // Try tapping the input field again before retrying
            val inputIdx = verifyElements.indexOfFirst { el ->
              (el["is_editable"] as? Boolean ?: false) ||
              (el["class"] as? String ?: "").let { it.contains("EditText") || it.contains("SearchView") }
            }
            if (inputIdx >= 0) {
              val idx = verifyElements[inputIdx]["index"] as? Int ?: continue
              writeLog("D", TAG, "searchInApp: Re-tapping input field at index $idx before retry")
              UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
              delay(300)
            }
          }
        }
        if (!textTyped) {
          pendingAppOpen = false; lastCaptureScreenTime = System.currentTimeMillis()
          return@withToolLogging mapOf("status" to "error", "step" to "type_text",
            "message" to "Failed to type query after 5 attempts. Text was not found in input field.")
        }

        // === PHASE 5: Submit search (observe-act loop with multiple strategies) ===
        _actionChannel.send(SkillProgressAgentAction(
          label = "Submitting search...", inProgress = true,
          addItemTitle = "Submit search", addItemDescription = "Searching for: $query"
        ))
        delay(800)

        val submitStrategies = listOf<Pair<String, suspend () -> Boolean>>(
          "findAndClickSubmitButton" to { UiAutomationTools.findAndClickSubmitButton(query) },
          "tapScreenCalculatedPosition" to { UiAutomationTools.tapSubmitButtonByScreenPosition() },
          "pressEnter" to {
            UiAutomationTools.executeUiAction(context, "keyevent", "{\"keycode\": \"KEYCODE_ENTER\"}")
            true
          },
          "pressSearchKey" to {
            UiAutomationTools.executeUiAction(context, "keyevent", "{\"keycode\": \"KEYCODE_SEARCH\"}")
            true
          },
          "tapElementListSubmit" to {
            val screen = UiAutomationTools.captureScreen(context)
            val els = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()
            val submitKeywords = listOf("搜索", "搜素", "搜索一下", "search", "Search", "确定", "完成", "发送")
            for (el in els) {
              val text = el["text"] as? String ?: ""
              val desc = el["content_description"] as? String ?: ""
              val clickable = el["is_clickable"] as? Boolean ?: false
              val editable = el["is_editable"] as? Boolean ?: false
              val idx = el["index"] as? Int ?: continue
              if (!editable && clickable && submitKeywords.any { text == it || desc == it }) {
                writeLog("D", TAG, "searchInApp: Found submit button '$text' at index $idx")
                UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                return@to true
              }
            }
            false
          },
        )

        var searchSubmitted = false
        for ((strategyName, strategy) in submitStrategies) {
          writeLog("D", TAG, "searchInApp: Trying submit strategy: $strategyName")
          try {
            strategy()
          } catch (e: Exception) {
            writeLog("D", TAG, "searchInApp: Strategy $strategyName threw: ${e.message}")
          }
          delay(2000)

          // OBSERVE: Check if search was actually submitted
          // Since we verified text was in the input field, we just need to check
          // if the input field still contains our query text
          val checkScreen = UiAutomationTools.captureScreen(context)
          val checkElements = checkScreen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()
          val stillHasQueryInInput = checkElements.any { el ->
            val editable = el["is_editable"] as? Boolean ?: false
            val text = el["text"] as? String ?: ""
            editable && text.contains(query)
          }

          if (stillHasQueryInInput) {
            writeLog("D", TAG, "searchInApp: Still on input page after $strategyName, trying next strategy")
          } else {
            writeLog("D", TAG, "searchInApp: Search submitted successfully via $strategyName")
            searchSubmitted = true
            break
          }
        }

        if (!searchSubmitted) {
          writeLog("D", TAG, "searchInApp: All submit strategies failed, search may not have been submitted")
        }

        // Clean up state
        pendingAppOpen = false
        lastCaptureScreenTime = System.currentTimeMillis()

        _actionChannel.send(SkillProgressAgentAction(
          label = "Search completed!", inProgress = false,
          addItemTitle = "Search completed", addItemDescription = "Searched for '$query' in $appName"
        ))

        writeLog("D", TAG, "searchInApp completed for '$query' in $appName, submitted=$searchSubmitted")

        mapOf(
          "status" to if (searchSubmitted) "success" else "partial",
          "message" to if (searchSubmitted) "Successfully searched for '$query' in $appName."
            else "Typed '$query' in $appName but could not submit the search. Try calling captureScreen() and uiAutomation() to submit manually.",
          "app" to appName,
          "query" to query,
          "search_completed" to searchSubmitted,
          "hint" to if (searchSubmitted) "Search completed. Call captureScreen() if you need to see the results."
            else "Search not submitted. Call captureScreen() to see the current screen, then find and tap the search/submit button."
        )
      }
    }
  }

  /** Extract the package_name value from a JSON parameters string. */
  private fun extractPackageNameFromParams(params: String): String? {
    return try {
      val json = kotlinx.serialization.json.Json.parseToJsonElement(params).jsonObject
      json["package_name"]?.toString()?.trim('"')
    } catch (_: Exception) { null }
  }

  private fun guardMissingEntityWithSkillFallback(name: String, type: String): Map<String, String> {
    val skills = skillService.getSelectedSkills()
    val isSkill = skills.any { it.name == name.trim() }
    val error = if (isSkill) "$type not found. Try to run it as a skill" else "Tool not found"
    return mapOf("error" to error, "status" to "failed")
  }

  fun sendAgentAction(action: AgentAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }

  private fun logMcpExecution(success: Boolean, errorType: String) {
    writeLog("D", TAG, "Analytics: mcp_execution, capability_name=$taskId, success=$success, error_type=$errorType")
    firebaseAnalytics?.logEvent(
      GalleryEvent.MCP_EXECUTION.id,
      Bundle().apply {
        putString("capability_name", taskId)
        putBoolean("success", success)
        if (errorType.isNotEmpty()) {
          putString("error_type", errorType)
        }
      },
    )
  }
}

fun getSkillSecretKey(skillName: String): String {
  return "skill___${skillName}"
}
