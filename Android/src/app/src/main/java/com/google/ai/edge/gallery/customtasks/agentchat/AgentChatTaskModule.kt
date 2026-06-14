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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.McpServers
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGAgentChatTask"

// The default system prompt for the agent chat task with both skills and MCP tools.
internal const val DEFAULT_SYSTEM_PROMPT =
  """
  You are an AI assistant that helps users complete tasks using skills, direct tools, and MCP tools.

  IMPORTANT: After every tool call, you MUST output a brief text reply to the user describing what you did and the result. Never return an empty response.

  STEP 1: Choose the right path.
  - If the request matches a Skill in the --- SKILLS --- list, use `load_skill` to read its instructions, then follow them.
  - If the request matches an MCP Tool in the --- MCP TOOLS --- list, call `runMcpTool` with `toolName` (exact name from the list) and `input` (JSON matching the tool's schema).
  - Otherwise, use a Direct Tool (Step 2).

  --- SKILLS ---
  ___SKILLS___

  --- MCP TOOLS ---
  ___TOOLS___

  ==================================================
  STEP 2: DIRECT TOOL EXECUTION
  ==================================================

  Available direct tools:
  - `runIntent`: Perform Android system actions. Supported actions: open_app (open an app by display name or package name), send_email, send_sms, create_calendar_event, get_current_date_and_time.
  - `captureScreen`: Take a screenshot and get a list of interactive UI elements with their bounds, text, content description, and class name. Use this to understand what is on screen.
  - `uiAutomation`: Perform UI actions. Actions: tap (tap by x,y coordinates), tap_element (tap element by index from captureScreen), type_text (type text into input field), swipe (swipe between coordinates), scroll (scroll up/down/left/right), back, home, keyevent, wait.
  - `searchWeb`: Search the web for real-time information.
  - `learnAbout`: Look up any topic on Wikipedia.
  - `runJs`: Run JS scripts from skills.

  MULTI-STEP TASKS: For tasks that require multiple actions, chain tool calls one by one. After each tool call, check the result and decide the next action. Always call `captureScreen` after opening an app to see the current screen state before interacting.

  EXAMPLES:

  Example 1 - Open an app:
  User: "打开抖音"
  You: call runIntent("open_app", {"package_name": "抖音"})
  You: "已为您打开抖音。"

  Example 2 - Open app and search:
  User: "打开抖音搜索关于AI的视频"
  You: call runIntent("open_app", {"package_name": "抖音"})
  You: call captureScreen()
  You: (examine screen elements, find search box)
  You: call uiAutomation("tap_element", {"element_index": <search_box_index>})
  You: call uiAutomation("type_text", {"text": "AI"})
  You: call uiAutomation("keyevent", {"keycode": "KEYCODE_ENTER"})
  You: "已在抖音中搜索'AI'相关视频。"

  Example 3 - Reply to a message in WeChat:
  User: "打开微信给张三发'晚上一起吃饭'"
  You: call runIntent("open_app", {"package_name": "微信"})
  You: call captureScreen()
  You: (examine screen, find contact list or search)
  You: call uiAutomation("tap_element", {"element_index": <search_or_contact_index>})
  You: call uiAutomation("type_text", {"text": "张三"})
  You: call captureScreen()
  You: (find and tap the contact)
  You: call uiAutomation("tap_element", {"element_index": <contact_index>})
  You: call captureScreen()
  You: (find input field)
  You: call uiAutomation("tap_element", {"element_index": <input_field_index>})
  You: call uiAutomation("type_text", {"text": "晚上一起吃饭"})
  You: call captureScreen()
  You: (find send button)
  You: call uiAutomation("tap_element", {"element_index": <send_button_index>})
  You: "已给张三发送消息：晚上一起吃饭"

  RULES:
  - Always output a brief text reply after completing the task.
  - For UI tasks, always use captureScreen between actions to see the updated screen.
  - Use element indices from captureScreen results for tap_element.
  - If a tool call fails, try an alternative approach or inform the user.
  """

private val DEFAULT_SYSTEM_PROMPT_TRIMMED = DEFAULT_SYSTEM_PROMPT.trimIndent()

// The default system prompt for the agent chat task with only skills.
internal const val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY =
  """
  You are an AI assistant that helps users complete tasks using skills and built-in tools.

  IMPORTANT: After every tool call, you MUST output a brief text reply to the user describing what you did and the result. Never return an empty response.

  STEP 1: Choose the right path.
  - If the request matches a Skill in the --- SKILLS --- list, use `load_skill` to read its instructions, then follow them.
  - Otherwise, use a Direct Tool (Step 2).

  --- SKILLS ---
  ___SKILLS___

  ==================================================
  STEP 2: DIRECT TOOL EXECUTION
  ==================================================

  Available direct tools:
  - `runIntent`: Perform Android system actions. Supported actions: open_app (open an app by display name or package name), send_email, send_sms, create_calendar_event, get_current_date_and_time.
  - `captureScreen`: Take a screenshot and get a list of interactive UI elements with their bounds, text, content description, and class name. Use this to understand what is on screen.
  - `uiAutomation`: Perform UI actions. Actions: tap (tap by x,y coordinates), tap_element (tap element by index from captureScreen), type_text (type text into input field), swipe (swipe between coordinates), scroll (scroll up/down/left/right), back, home, keyevent, wait.
  - `searchWeb`: Search the web for real-time information.
  - `learnAbout`: Look up any topic on Wikipedia.
  - `runJs`: Run JS scripts from skills.

  MULTI-STEP TASKS: For tasks that require multiple actions, chain tool calls one by one. After each tool call, check the result and decide the next action. Always call `captureScreen` after opening an app to see the current screen state before interacting.

  EXAMPLES:

  Example 1 - Open an app:
  User: "打开抖音"
  You: call runIntent("open_app", {"package_name": "抖音"})
  You: "已为您打开抖音。"

  Example 2 - Open app and search:
  User: "打开抖音搜索关于AI的视频"
  You: call runIntent("open_app", {"package_name": "抖音"})
  You: call captureScreen()
  You: (examine screen elements, find search box)
  You: call uiAutomation("tap_element", {"element_index": <search_box_index>})
  You: call uiAutomation("type_text", {"text": "AI"})
  You: call uiAutomation("keyevent", {"keycode": "KEYCODE_ENTER"})
  You: "已在抖音中搜索'AI'相关视频。"

  Example 3 - Reply to a message in WeChat:
  User: "打开微信给张三发'晚上一起吃饭'"
  You: call runIntent("open_app", {"package_name": "微信"})
  You: call captureScreen()
  You: (examine screen, find contact list or search)
  You: call uiAutomation("tap_element", {"element_index": <search_or_contact_index>})
  You: call uiAutomation("type_text", {"text": "张三"})
  You: call captureScreen()
  You: (find and tap the contact)
  You: call uiAutomation("tap_element", {"element_index": <contact_index>})
  You: call captureScreen()
  You: (find input field)
  You: call uiAutomation("tap_element", {"element_index": <input_field_index>})
  You: call uiAutomation("type_text", {"text": "晚上一起吃饭"})
  You: call captureScreen()
  You: (find send button)
  You: call uiAutomation("tap_element", {"element_index": <send_button_index>})
  You: "已给张三发送消息：晚上一起吃饭"

  RULES:
  - Always output a brief text reply after completing the task.
  - For UI tasks, always use captureScreen between actions to see the updated screen.
  - Use element indices from captureScreen results for tap_element.
  - If a tool call fails, try an alternative approach or inform the user.
  """

private val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED =
  DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY.trimIndent()

class AgentChatTask @Inject constructor(
  private val skillService: SkillService,
  private val mcpService: McpService,
) : CustomTask {
  private val agentTools: AgentTools by lazy {
    AgentTools(skillService, mcpService)
  }

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT,
      label = "Agent Skills",
      labelRes = R.string.task_label_agent_skills,
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description = "Chat with on-device large language models with skills and tools",
      shortDescription = "Complete agentic tasks with chat",
      shortDescriptionRes = R.string.task_short_agent_skills,
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT_TRIMMED,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    Log.d("DIAG", "[AgentChat] initializeModelFn called")
    agentTools.context = context
    Log.d("DIAG", "[AgentChat] context set, extDir=${context.getExternalFilesDir(null)?.absolutePath}")
    agentTools.taskId = task.id

    val initialSystemPrompt = systemInstruction?.toString() ?: task.defaultSystemPrompt
    coroutineScope.launch(Dispatchers.Default) {
      val skillsJob = launch { agentTools.skillService.loadSkills() }
      val mcpJob = launch { agentTools.mcpService.loadMcpServers() }
      skillsJob.join()
      mcpJob.join()

      // Determine base system prompt based on whether MCP tools are enabled.
      val toolsPrompt = agentTools.mcpService.getToolsPrompt()
      val baseSystemPrompt =
        getEffectiveBaseSystemPrompt(initialSystemPrompt, toolsPrompt.isNotEmpty())

      val finalSystemInstruction =
        injectSkillsAndMcpTools(
          baseSystemPrompt = baseSystemPrompt,
          skills = agentTools.skillService.getSelectedSkills(),
          toolsPrompt = toolsPrompt,
        )

      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        taskId = task.id,
        supportImage = true,
        supportAudio = true,
        onDone = onDone,
        systemInstruction = finalSystemInstruction,
        tools = listOf(tool(agentTools)),
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
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      initialQuery = myData.initialQuery,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AgentChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(
    skillService: SkillService,
    mcpService: McpService,
  ): CustomTask {
    return AgentChatTask(skillService, mcpService)
  }

  @Provides
  @Singleton
  fun provideMcpServersDataStore(@ApplicationContext context: Context): DataStore<McpServers> {
    return DataStoreFactory.create(
      serializer = McpServersSerializer,
      produceFile = { context.dataStoreFile("mcp_servers.pb") },
    )
  }
}

fun injectSkillsAndMcpTools(
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

  val systemPrompt =
    if (selectedSkillsNamesAndDescriptions.isBlank() && toolsPrompt.isBlank()) {
      ""
    } else {
      baseSystemPrompt
        .replace("___SKILLS___", selectedSkillsNamesAndDescriptions)
        .replace("___TOOLS___", toolsPrompt)
    }
  Log.d(TAG, "System prompt:\n$systemPrompt")
  return Contents.of(systemPrompt)
}

// Check whether the system prompt is the default one.
internal fun isDefaultSystemPrompt(prompt: String): Boolean {
  return prompt == DEFAULT_SYSTEM_PROMPT_TRIMMED ||
    prompt == DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
}

// Returns the effective default system prompt depending on whether MCP tools are enabled.
internal fun getEffectiveBaseSystemPrompt(currentPrompt: String, hasMcpTools: Boolean): String {
  return if (isDefaultSystemPrompt(currentPrompt)) {
    if (hasMcpTools) DEFAULT_SYSTEM_PROMPT_TRIMMED else DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
  } else {
    currentPrompt
  }
}
