/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Bundle
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.net.URLConnection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.McpService
import com.google.ai.edge.gallery.customtasks.agentchat.SkillService
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.common.BaseGalleryWebViewClient
import com.google.ai.edge.gallery.ui.common.GalleryWebView
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

////////////////////////////////////////////////////////////////////////////////////////////////////
// AI Chat.

class LlmChatTask @Inject constructor(
  private val skillService: SkillService,
  private val mcpService: McpService,
) : CustomTask {
  private val agentTools: AgentTools by lazy {
    AgentTools(skillService, mcpService)
  }

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_CHAT,
      label = "AI Chat",
      labelRes = R.string.task_label_ai_chat,
      category = Category.LLM,
      icon = Icons.Outlined.Forum,
      models = mutableListOf(),
      description = "Chat with on-device large language models",
      descriptionRes = R.string.task_desc_ai_chat,
      shortDescription = "Chat with an on-device LLM",
      shortDescriptionRes = R.string.task_short_ai_chat,
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    Log.d("DIAG", "[AIChat] initializeModelFn called")
    agentTools.context = context
    Log.d("DIAG", "[AIChat] context set, extDir=${context.getExternalFilesDir(null)?.absolutePath}")
    agentTools.taskId = task.id

    // Show diagnostic Toast on main thread with full paths
    val extDir = context.getExternalFilesDir(null)?.absolutePath ?: "null"
    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath
    val msg = "Log: /storage/emulated/0/Download/agent_logs/\nFiles: $extDir"
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    coroutineScope.launch(Dispatchers.Default) {
      val skillsJob = launch { skillService.loadSkills() }
      val mcpJob = launch { mcpService.loadMcpServers() }
      skillsJob.join()
      mcpJob.join()

      val toolsPrompt = mcpService.getToolsPrompt()
      val baseSystemPrompt = systemInstruction?.toString() ?: ""
      val finalSystemInstruction =
        injectSkillsAndMcpTools(
          baseSystemPrompt = baseSystemPrompt,
          skills = skillService.getSelectedSkills(),
          toolsPrompt = toolsPrompt,
        )

      val tools = listOf(tool(agentTools))

      // Need to get the ViewModel to store tools. We'll do this via the model.
      // AI Chat 不需要 constrained decoding（会导致多轮对话卡住）
      // 只有 Agent Chat 需要此功能用于工具调用
      model.runtimeHelper.initialize(
        context = context,
        model = model,
        taskId = task.id,
        supportImage = true,
        supportAudio = true,
        onDone = onDone,
        coroutineScope = coroutineScope,
        systemInstruction = finalSystemInstruction,
        tools = tools,
        enableConversationConstrainedDecoding = false,
      )
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmChatViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    
    // WebView infrastructure for handling CallJsAgentAction from AgentTools
    val webViewRef = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<WebView?>(null) }
    val actionChannel = agentTools.actionChannel
    val currentTask by rememberUpdatedState(task)
    
    // Pending JS action result completer - stored at class level for @JavascriptInterface access
    val pendingCompleter = androidx.compose.runtime.remember { 
      androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.CompletableDeferred<String>?>(null) 
    }
    
    // JavaScript interface bridge - registered once at WebView creation time
    // This is needed because evaluateJavascript cannot properly handle Promise return values
    // Also provides searchProxy to bypass WebView CORS restrictions
    val searchResults = androidx.compose.runtime.remember {
      androidx.compose.runtime.mutableStateMapOf<String, String>()
    }
    val searchResultsLock = Any()
    val searchScope = androidx.compose.runtime.rememberCoroutineScope()
    
    class AiEdgeGallery {
      @JavascriptInterface
      fun onResultReady(result: String) {
        Log.d("DIAG", "[AIChat-WebView] onResultReady: ${result.take(200)}")
        pendingCompleter.value?.complete(result)
      }
      
      @JavascriptInterface
      fun searchProxy(url: String, callbackId: String) {
        searchScope.launch(Dispatchers.IO) {
          try {
            Log.d("DIAG", "[AIChat-Search] Proxy request: $url (callback: $callbackId)")
            val conn = java.net.URL(url).openConnection()
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            
            val inputStream = conn.inputStream
            val html = inputStream.bufferedReader(java.nio.charset.Charset.forName("UTF-8")).use { it.readText() }
            val encodedHtml = java.util.Base64.getEncoder().encodeToString(html.toByteArray(java.nio.charset.Charset.forName("UTF-8")))
            
            synchronized(searchResultsLock) {
              searchResults[callbackId] = encodedHtml
            }
            Log.d("DIAG", "[AIChat-Search] Proxy success for $callbackId, ${html.length} bytes")
          } catch (e: Exception) {
            Log.e("DIAG", "[AIChat-Search] Proxy failed: ${e.message}")
            synchronized(searchResultsLock) {
              searchResults[callbackId] = "__ERROR__:${e.message}"
            }
          }
        }
      }
      
      @JavascriptInterface
      fun getSearchResult(callbackId: String): String {
        // Poll for result with timeout
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 12000) {
          synchronized(searchResultsLock) {
            searchResults[callbackId]?.let { result ->
              searchResults.remove(callbackId)
              return result
            }
          }
          Thread.sleep(100)
        }
        return "__ERROR__:timeout"
      }
    }
    
    LaunchedEffect(actionChannel) {
      for (action in actionChannel) {
        Log.d("DIAG", "[AIChat] Handling action: ${action.javaClass.simpleName}")
        when (action) {
          is CallJsAgentAction -> {
            val skillName =
              if (action.url.contains("/skills/")) {
                action.url.substringAfter("/skills/").substringBefore("/")
              } else if (action.url.startsWith(LOCAL_URL_BASE + "/")) {
                action.url.substringAfter(LOCAL_URL_BASE + "/").substringBefore("/")
              } else {
                action.url
              }
            Log.d("DIAG", "[AIChat-WebView] Loading URL for skill '$skillName': ${action.url}")
            
            try {
              // Set up a safety net timeout so we NEVER hang the chat or tool execution
              launch {
                delay(60000L) // 60 seconds max
                if (!action.result.isCompleted) {
                  Log.e("DIAG", "[AIChat-WebView] JS execution timed out")
                  action.result.complete(
                    "{\"error\": \"Skill execution timed out. Please check network connection.\"}"
                  )
                }
              }
              
              // Set the pending completer BEFORE loading the URL
              pendingCompleter.value = action.result
              
              // Load URL and wait for page to finish
              suspendCancellableCoroutine<Unit> { continuation ->
                val client = object : WebViewClient() {
                  private fun getMimeType(path: String): String {
                    return URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
                  }
                  
                  private fun interceptSkillRequest(url: String): WebResourceResponse? {
                    if (!url.startsWith(LOCAL_URL_BASE)) return null
                    
                    // URL: https://appassets.androidplatform.net/search-web/scripts/index.html
                    // Map to: assets/skills/search-web/scripts/index.html
                    val relativePath = url.substringAfter(LOCAL_URL_BASE + "/")
                    val assetPath = "skills/$relativePath"
                    
                    // Try assets first (for built-in skills)
                    try {
                      val inputStream = webViewRef.value?.context?.assets?.open(assetPath)
                      if (inputStream != null) {
                        Log.d("DIAG", "[AIChat-WebView] Loaded from assets: $assetPath")
                        val mimeType = getMimeType(assetPath)
                        return WebResourceResponse(mimeType, "UTF-8", inputStream)
                      }
                    } catch (e: Exception) {
                      Log.d("DIAG", "[AIChat-WebView] Asset not found: $assetPath")
                    }
                    
                    // Fallback to filesDir (for downloaded/custom skills)
                    val localFile = File(webViewRef.value?.context?.filesDir ?: return null, relativePath)
                    if (localFile.exists()) {
                      Log.d("DIAG", "[AIChat-WebView] Loaded from filesDir: $relativePath")
                      return WebResourceResponse(getMimeType(relativePath), "UTF-8", localFile.inputStream())
                    }
                    
                    return WebResourceResponse("text/plain", "UTF-8", null)
                  }
                  
                  private fun interceptExternalFetch(url: String, request: WebResourceRequest?): WebResourceResponse? {
                    // Only intercept fetch requests from search-web skill (CORS workaround)
                    val isMethodGet = request?.method == "GET" || request?.method == null
                    if (!isMethodGet) return null
                    
                    val targetHosts = listOf("html.duckduckgo.com", "www.bing.com", "www.baidu.com")
                    val isSearchEngine = targetHosts.any { url.contains(it, ignoreCase = true) }
                    if (!isSearchEngine) return null
                    
                    return try {
                      Log.d("DIAG", "[AIChat-WebView] Intercepting external fetch: $url")
                      val conn = URL(url).openConnection()
                      conn.connectTimeout = 8000
                      conn.readTimeout = 8000
                      conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                      conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                      conn.setRequestProperty("Accept-Charset", "UTF-8,*;q=0.5")
                      
                      val contentType = conn.contentType ?: "text/html"
                      val encoding = contentType.substringAfter("charset=", "UTF-8")
                      val mimeType = contentType.substringBefore(";")
                      
                      Log.d("DIAG", "[AIChat-WebView] External fetch response: ${conn.contentLength} bytes, $mimeType")
                      WebResourceResponse(mimeType, encoding, conn.inputStream)
                    } catch (e: Exception) {
                      Log.e("DIAG", "[AIChat-WebView] External fetch failed: ${e.message}")
                      WebResourceResponse("text/plain", "UTF-8", null)
                    }
                  }
                  
                  override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                  ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    // First try to intercept external fetch (CORS workaround)
                    return interceptExternalFetch(url, request)
                      ?: interceptSkillRequest(url)
                      ?: super.shouldInterceptRequest(view, request)
                  }
                  
                  override fun onPageFinished(view: WebView, url: String) {
                    Log.d("DIAG", "[AIChat-WebView] onPageFinished: $url")
                    view.webViewClient = object : WebViewClient() {
                      private fun getMimeType(path: String): String {
                        return URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
                      }
                      
                      private fun interceptSkillRequest(url: String): WebResourceResponse? {
                        if (!url.startsWith(LOCAL_URL_BASE)) return null
                        val relativePath = url.substringAfter(LOCAL_URL_BASE + "/")
                        val assetPath = "skills/$relativePath"
                        try {
                          val inputStream = webViewRef.value?.context?.assets?.open(assetPath)
                          if (inputStream != null) {
                            return WebResourceResponse(getMimeType(assetPath), "UTF-8", inputStream)
                          }
                        } catch (e: Exception) {
                          Log.d("DIAG", "[AIChat-WebView] Asset not found: $assetPath")
                        }
                        val localFile = File(webViewRef.value?.context?.filesDir ?: return null, relativePath)
                        if (localFile.exists()) {
                          return WebResourceResponse(getMimeType(relativePath), "UTF-8", localFile.inputStream())
                        }
                        return WebResourceResponse("text/plain", "UTF-8", null)
                      }
                      
                      private fun interceptExternalFetch(url: String, request: WebResourceRequest?): WebResourceResponse? {
                        val isMethodGet = request?.method == "GET" || request?.method == null
                        if (!isMethodGet) return null
                        val targetHosts = listOf("html.duckduckgo.com", "www.bing.com", "www.baidu.com")
                        val isSearchEngine = targetHosts.any { url.contains(it, ignoreCase = true) }
                        if (!isSearchEngine) return null
                        return try {
                          Log.d("DIAG", "[AIChat-WebView] Intercepting external fetch: $url")
                          val conn = URL(url).openConnection()
                          conn.connectTimeout = 8000
                          conn.readTimeout = 8000
                          conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                          conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                          conn.setRequestProperty("Accept-Charset", "UTF-8,*;q=0.5")
                          val contentType = conn.contentType ?: "text/html"
                          val encoding = contentType.substringAfter("charset=", "UTF-8")
                          val mimeType = contentType.substringBefore(";")
                          WebResourceResponse(mimeType, encoding, conn.inputStream)
                        } catch (e: Exception) {
                          Log.e("DIAG", "[AIChat-WebView] External fetch failed: ${e.message}")
                          WebResourceResponse("text/plain", "UTF-8", null)
                        }
                      }
                      
                      override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                      ): WebResourceResponse? {
                        val u = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                        return interceptExternalFetch(u, request)
                          ?: interceptSkillRequest(u)
                          ?: super.shouldInterceptRequest(view, request)
                      }
                    }
                    continuation.resume(Unit) { }
                  }
                }
                webViewRef.value?.webViewClient = client
                webViewRef.value?.loadUrl(action.url)
              }
              
              Log.d("DIAG", "[AIChat-WebView] Page loaded, preparing JS execution")
              
              // Execute JS - use @JavascriptInterface bridge instead of evaluateJavascript return value
              // because evaluateJavascript cannot handle async/Promise return values properly
              val safeData = org.json.JSONObject.quote(action.data)
              val safeSecret = org.json.JSONObject.quote(action.secret)
              val js = """
                (async function() {
                  try {
                    console.log('[AIChat-WebView] JS IIFE started');
                    var startTs = Date.now();
                    while(true) {
                      if (typeof ai_edge_gallery_get_result === 'function') {
                        console.log('[AIChat-WebView] ai_edge_gallery_get_result found');
                        break;
                      }
                      await new Promise(resolve => { setTimeout(resolve, 100); });
                      if (Date.now() - startTs > 10000) {
                        console.log('[AIChat-WebView] ai_edge_gallery_get_result not found after 10s');
                        break;
                      }
                    }
                    if (typeof window.ai_edge_gallery_get_result !== 'function') {
                      window.AiEdgeGallery.onResultReady(JSON.stringify({error: 'ai_edge_gallery_get_result not defined'}));
                      return;
                    }
                    console.log('[AIChat-WebView] Calling ai_edge_gallery_get_result...');
                    var result = await window.ai_edge_gallery_get_result($safeData, $safeSecret);
                    console.log('[AIChat-WebView] Got result, sending to bridge');
                    // Use @JavascriptInterface bridge to send result back to Kotlin
                    if (typeof window.AiEdgeGallery !== 'undefined') {
                      window.AiEdgeGallery.onResultReady(result);
                    } else {
                      console.error('[AIChat-WebView] AiEdgeGallery bridge not found!');
                    }
                  } catch(e) {
                    console.error('[AIChat-WebView] Error: ' + e.message);
                    if (typeof window.AiEdgeGallery !== 'undefined') {
                      window.AiEdgeGallery.onResultReady(JSON.stringify({error: e.message}));
                    }
                  }
                })()
              """.trimIndent()
              
              Log.d("DIAG", "[AIChat-WebView] Calling evaluateJavascript...")
              webViewRef.value?.evaluateJavascript(js) { jsCallbackResult ->
                Log.d("DIAG", "[AIChat-WebView] evaluateJavascript callback fired: $jsCallbackResult")
                // Result is handled via AiEdgeGallery.onResultReady() callback
              }
              Log.d("DIAG", "[AIChat-WebView] evaluateJavascript called, waiting for result via bridge")
            } catch (e: Exception) {
              Log.e("DIAG", "[AIChat-WebView] Error: ${e.message}")
              action.result.completeExceptionally(e)
            }
          }
          else -> {
            Log.d("DIAG", "[AIChat-WebView] Ignoring action type: ${action.javaClass.simpleName}")
          }
        }
      }
    }
    
    GalleryWebView(
      modifier = Modifier.size(1.dp),
      onWebViewCreated = { webView ->
        webViewRef.value = webView
        // Register JavaScript interface at WebView creation time (must be done before any page loads)
        webView.addJavascriptInterface(AiEdgeGallery(), "AiEdgeGallery")
      },
    )
    
    LlmChatScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      toolsProvider = { listOf(tool(agentTools)) },
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
          tools = listOf(tool(agentTools)),
          enableConversationConstrainedDecoding = false,
        )
      },
      emptyStateComposable = {
        Box(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier =
              Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(stringResource(R.string.aichat_emptystate_title), style = emptyStateTitle)
            Text(
              stringResource(R.string.aichat_emptystate_content),
              style = emptyStateContent,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
        }
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object LlmChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(
    skillService: SkillService,
    mcpService: McpService,
  ): CustomTask {
    return LlmChatTask(skillService, mcpService)
  }
}

internal fun injectSkillsAndMcpTools(
  baseSystemPrompt: String,
  skills: List<Skill>,
  toolsPrompt: String,
): Contents {
  val selectedSkillsNamesAndDescriptions =
    skills
      .filter { it.selected }
      .joinToString("\n\n") { skill ->
        "- Skill name: \"${skill.name}\"\n- Description: ${skill.description}"
      }

  // Build dynamic tool recommendations based on available skills.
  val skillCategories =
    mapOf(
      "Real-time Information" to listOf("search-web", "query-wikipedia", "learn-something-new"),
      "Calendar & Scheduling" to
        listOf(
          "read-calendar-events",
          "create-calendar-event",
          "update-calendar-event",
          "delete-calendar-event",
          "schedule-notification",
        ),
      "Device Interactions" to
        listOf(
          "send-email",
          "read-clipboard",
          "write-clipboard",
        ),
      "Visual Output" to listOf("qr-code", "interactive-map"),
      "Productivity" to listOf("mood-tracker", "kitchen-adventure", "text-spinner", "calculate-hash"),
    )

  val recommendations =
    skills.filter { it.selected }.mapNotNull { skill ->
      skillCategories.entries.find { (_, names) -> names.contains(skill.name) }?.key
    }.toSet()

  val recommendationsSection =
    if (recommendations.isNotEmpty()) {
      val categoryList = recommendations.joinToString(", ") { "($it)" }
      "--- TOOL RECOMMENDATIONS ---\n" +
        "Based on your available skills, you can help with: $categoryList.\n" +
        "Match the user's request to the right category and use the corresponding skill.\n\n"
    } else {
      ""
    }

  val systemPrompt =
    if (selectedSkillsNamesAndDescriptions.isBlank() && toolsPrompt.isBlank()) {
      baseSystemPrompt
    } else {
      val skillsSection =
        if (selectedSkillsNamesAndDescriptions.isNotBlank()) {
          "--- AVAILABLE SKILLS ---\n$selectedSkillsNamesAndDescriptions\n"
        } else {
          ""
        }
      val toolsSection =
        if (toolsPrompt.isNotBlank()) {
          "--- AVAILABLE MCP TOOLS ---\n$toolsPrompt\n"
        } else {
          ""
        }
      val toolInstructions =
        if (skillsSection.isNotEmpty() || toolsSection.isNotEmpty()) {
          """
            CRITICAL: When the user's request can be helped by a skill or tool listed below, you MUST use it. Do NOT answer from your own knowledge if a tool is available.

            $skillsSection
            $toolsSection

            $recommendationsSection
            HOW TO USE TOOLS:
            - To use a skill, call `loadSkill` with the skill name (e.g., `loadSkill("query-wikipedia")`), then follow its instructions.
            - To use an MCP tool, call `runMcpTool` with the tool name and input parameters.
            - After getting the tool result, use it to answer the user.
            - If no skill or tool matches, answer normally.
          """.trimIndent()
        } else {
          ""
        }
      if (baseSystemPrompt.isNotEmpty()) {
        "$baseSystemPrompt\n\n$toolInstructions"
      } else {
        toolInstructions
      }
    }
  return Contents.of(systemPrompt)
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask image.

class LlmAskImageTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_ASK_IMAGE,
      label = "Ask Image",
      labelRes = R.string.task_label_ask_image,
      category = Category.LLM,
      icon = Icons.Outlined.Mms,
      models = mutableListOf(),
      description = "Ask questions about images with on-device large language models",
      descriptionRes = R.string.task_desc_ask_image,
      shortDescription = "Ask questions about images",
      shortDescriptionRes = R.string.task_short_ask_image,
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = true,
      supportAudio = false,
      onDone = onDone,
      coroutineScope = coroutineScope,
      systemInstruction = systemInstruction,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmAskImageViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmAskImageScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmAskImageModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return LlmAskImageTask()
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask audio.

class LlmAskAudioTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_ASK_AUDIO,
      label = "Audio Scribe",
      labelRes = R.string.task_label_audio_scribe,
      category = Category.LLM,
      icon = Icons.Outlined.Mic,
      models = mutableListOf(),
      description =
        "Instantly transcribe and/or translate audio clips using on-device large language models",
      descriptionRes = R.string.task_desc_audio_scribe,
      shortDescription = "Transcribe and translate audio",
      shortDescriptionRes = R.string.task_short_audio_scribe,
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = false,
      supportAudio = true,
      onDone = onDone,
      coroutineScope = coroutineScope,
      systemInstruction = systemInstruction,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmAskAudioViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmAskAudioScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmAskAudioModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return LlmAskAudioTask()
  }
}
