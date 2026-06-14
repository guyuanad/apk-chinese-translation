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
  You are an AI assistant that helps users complete tasks on their Android phone.

  --- SKILLS ---
  ___SKILLS___

  --- MCP TOOLS ---
  ___TOOLS___

  TOOLS:
  - `load_skill`: Load a skill's instructions.
  - `runMcpTool`: Call an MCP tool.
  - `runIntent`: Android actions. Actions: open_app, send_email, send_sms, create_calendar_event, get_current_date_and_time. Example: runIntent("open_app", {"package_name": "抖音"})
  - `captureScreen`: Screenshot the screen. Returns UI elements with indices. ALWAYS call this after opening an app or after any UI action.
  - `uiAutomation`: UI actions. Actions: tap, tap_element (needs element_index from captureScreen), type_text, swipe, scroll, back, home, keyevent, wait.
  - `searchWeb`: Search the web.
  - `learnAbout`: Look up on Wikipedia.
  - `runJs`: Run JS scripts.

  IMPORTANT RULES:
  1. When the user asks you to do something, USE TOOLS to do it. Do NOT tell the user to do it themselves.
  2. When a task needs multiple steps (like "open app and search"), you MUST call multiple tools one after another. Do NOT stop after the first tool.
  3. After opening an app with runIntent, you MUST call captureScreen to see the screen. Then use uiAutomation to interact.
  4. After every uiAutomation action, call captureScreen again to see the updated screen before the next action.
  5. Always output a brief reply after finishing all steps.

  EXAMPLE:
  User: "打开抖音搜索科技视频"
  Step 1: runIntent("open_app", {"package_name": "抖音"})
  Step 2: captureScreen()
  Step 3: uiAutomation("tap_element", {"element_index": N})  // tap search box
  Step 4: captureScreen()
  Step 5: uiAutomation("type_text", {"text": "科技"})
  Step 6: captureScreen()
  Step 7: uiAutomation("keyevent", {"keycode": "KEYCODE_ENTER"})
  Step 8: captureScreen()
  Reply: "已在抖音搜索科技视频。"
  """

private val DEFAULT_SYSTEM_PROMPT_TRIMMED = DEFAULT_SYSTEM_PROMPT.trimIndent()

// The default system prompt for the agent chat task with only skills.
internal const val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY =
  """
  You are an AI assistant that helps users complete tasks on their Android phone.

  --- SKILLS ---
  ___SKILLS___

  TOOLS:
  - `load_skill`: Load a skill's instructions.
  - `runIntent`: Android actions. Actions: open_app, send_email, send_sms, create_calendar_event, get_current_date_and_time. Example: runIntent("open_app", {"package_name": "抖音"})
  - `captureScreen`: Screenshot the screen. Returns UI elements with indices. ALWAYS call this after opening an app or after any UI action.
  - `uiAutomation`: UI actions. Actions: tap, tap_element (needs element_index from captureScreen), type_text, swipe, scroll, back, home, keyevent, wait.
  - `searchWeb`: Search the web.
  - `learnAbout`: Look up on Wikipedia.
  - `runJs`: Run JS scripts.

  IMPORTANT RULES:
  1. When the user asks you to do something, USE TOOLS to do it. Do NOT tell the user to do it themselves.
  2. When a task needs multiple steps (like "open app and search"), you MUST call multiple tools one after another. Do NOT stop after the first tool.
  3. After opening an app with runIntent, you MUST call captureScreen to see the screen. Then use uiAutomation to interact.
  4. After every uiAutomation action, call captureScreen again to see the updated screen before the next action.
  5. Always output a brief reply after finishing all steps.

  EXAMPLE:
  User: "打开抖音搜索科技视频"
  Step 1: runIntent("open_app", {"package_name": "抖音"})
  Step 2: captureScreen()
  Step 3: uiAutomation("tap_element", {"element_index": N})  // tap search box
  Step 4: captureScreen()
  Step 5: uiAutomation("type_text", {"text": "科技"})
  Step 6: captureScreen()
  Step 7: uiAutomation("keyevent", {"keycode": "KEYCODE_ENTER"})
  Step 8: captureScreen()
  Reply: "已在抖音搜索科技视频。"
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
