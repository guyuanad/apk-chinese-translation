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

import android.util.Log
import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.proto.McpAuth
import com.google.ai.edge.gallery.proto.McpServer
import com.google.ai.edge.gallery.proto.McpServers
import com.google.ai.edge.gallery.proto.McpTool
import com.google.ai.edge.gallery.proto.UserData
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "AGMcpService"

data class McpServerStateService(
  val mcpServer: McpServer,
  val client: Client?,
  val error: String? = null,
)

/**
 * Singleton service that manages MCP server data and connections.
 * Replaces McpManagerViewModel usage in AgentTools.
 */
@Singleton
class McpService @Inject constructor(
  private val mcpServersDataStore: DataStore<McpServers>,
  private val userDataDataStore: DataStore<UserData>,
) {
  private val httpClient = HttpClient(Android) { install(SSE) }

  private val _serversState = MutableStateFlow<List<McpServerStateService>>(emptyList())
  val serversState = _serversState.asStateFlow()
  private var serversLoaded = false

  /** Loads MCP servers from DataStore and initializes connections. */
  suspend fun loadMcpServers() {
    if (serversLoaded) return

    Log.d(TAG, "Loading MCP servers...")
    withContext(Dispatchers.IO) {
      try {
        val savedServers = mcpServersDataStore.data.first().mcpServerList
        val loadedStates = savedServers.map { serverProto ->
          try {
            val savedToolsMap = serverProto.toolsList.associate { it.name to it.enabled }
            val savedAlwaysAllowMap = serverProto.toolsList.associate { it.name to it.alwaysAllow }
            val (client, mcpTools) =
              initializeClientAndLoadTools(serverProto.url, savedToolsMap, savedAlwaysAllowMap)
            val serverVersion = client.serverVersion
            val updatedServerProto =
              serverProto.toBuilder()
                .clearTools()
                .addAllTools(mcpTools)
                .setEnabled(serverProto.enabled)
                .apply {
                  serverVersion?.name?.let { setName(it) }
                  serverVersion?.version?.let { setVersion(it) }
                  val desc = mcpTools.joinToString(", ") { it.name }
                  if (desc.isNotEmpty()) {
                    setDescription("Tools: $desc")
                  }
                }
                .build()
            McpServerStateService(mcpServer = updatedServerProto, client = client, error = null)
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error loading MCP server: ${serverProto.url}", e)
            McpServerStateService(
              mcpServer = serverProto.toBuilder().setEnabled(false).build(),
              client = null,
              error = e.message ?: "Failed to connect",
            )
          }
        }
        _serversState.value = loadedStates
        serversLoaded = true
        Log.d(TAG, "Loaded ${loadedStates.size} MCP servers")
      } catch (e: Exception) {
        Log.e(TAG, "Error reading saved MCP servers", e)
      }
    }
  }

  /** Generates a textual prompt listing all enabled tools from all enabled MCP servers. */
  fun getToolsPrompt(): String {
    return _serversState.value
      .filter { it.mcpServer.enabled }
      .flatMap { it.mcpServer.toolsList }
      .filter { it.enabled }
      .joinToString("\n\n") { tool ->
        "MCP tool name: \"${tool.name}\"\n- Description: ${tool.description}\n- Input schema: ${tool.inputSchema}"
      }
  }

  /** Initializes a streaming transport client and fetches its supported tools. */
  private suspend fun initializeClientAndLoadTools(
    url: String,
    savedToolsMap: Map<String, Boolean>? = null,
    savedAlwaysAllowMap: Map<String, Boolean>? = null,
    mcpAuth: McpAuth? = null,
  ): Pair<Client, List<McpTool>> {
    Log.d(TAG, "Initializing MCP for $url...")
    val client =
      Client(
        clientInfo =
          Implementation(name = "google-ai-edge-gallery", version = BuildConfig.VERSION_NAME)
      )
    val resolvedAuth = mcpAuth ?: userDataDataStore.data.first().mcpAuthsMap[url]
    val transport =
      if (
        resolvedAuth != null && resolvedAuth.authMethodCase == McpAuth.AuthMethodCase.REQUEST_HEADER
      ) {
        val reqHeader = resolvedAuth.requestHeader
        StreamableHttpClientTransport(
          client = httpClient,
          url = url,
          requestBuilder = { headers.append(reqHeader.headerName, reqHeader.headerValue) },
        )
      } else {
        StreamableHttpClientTransport(client = httpClient, url = url)
      }
    client.connect(transport)
    val toolsResponse = client.listTools()
    val mcpTools =
      toolsResponse?.tools.orEmpty().map { tool ->
        val isEnabled = savedToolsMap?.get(tool.name) ?: true
        val isAlwaysAllow = savedAlwaysAllowMap?.get(tool.name) ?: false
        val propertiesJson = tool.inputSchema.properties.toString()
        val requiredJson =
          tool.inputSchema.required?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } ?: "[]"
        val schemaJson =
          """{"type":"object","properties":$propertiesJson,"required":$requiredJson}"""
        McpTool.newBuilder()
          .setName(tool.name)
          .setDescription(tool.description ?: "")
          .setInputSchema(schemaJson)
          .setEnabled(isEnabled)
          .setAlwaysAllow(isAlwaysAllow)
          .build()
      }
    Log.d(TAG, "Loaded ${mcpTools.size} tools from $url: ${mcpTools.joinToString { it.name }}")
    return Pair(client, mcpTools)
  }
}
