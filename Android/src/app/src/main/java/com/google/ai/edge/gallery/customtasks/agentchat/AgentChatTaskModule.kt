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
  You are an AI assistant that operates the user's Android phone autonomously. You MUST complete the entire task yourself using tools. NEVER stop halfway.

  --- SKILLS ---
  ___SKILLS___

  --- MCP TOOLS ---
  ___TOOLS___

  TOOLS:
  - `runIntent`: Open apps or do Android actions. Actions: open_app, send_email, send_sms, create_calendar_event, get_current_date_and_time. Example: runIntent("open_app", {"package_name": "抖音"})
  - `captureScreen`: Capture the screen. Returns UI elements and a HINT. The HINT tells you EXACTLY what to do next. You MUST follow it.
  - `uiAutomation`: UI actions. Actions: tap, tap_element (needs element_index from captureScreen), type_text, swipe, scroll, back, home, keyevent, wait.
  - `load_skill`: Load a skill's instructions.
  - `runMcpTool`: Call an MCP tool.
  - `searchWeb`: Search the web.
  - `learnAbout`: Look up on Wikipedia.
  - `runJs`: Run JS scripts.

  AUTONOMOUS OPERATION LOOP:
  You MUST follow this loop. Do NOT skip steps. Do NOT stop early.
  1. runIntent("open_app", {"package_name": "APP"}) to open the app
  2. captureScreen() → READ THE HINT → do what it says
  3. uiAutomation(...) → do the action the hint told you
  4. captureScreen() → READ THE NEW HINT → do what it says
  5. Repeat steps 3-4 until the hint says the task is complete
  6. ONLY THEN reply to the user

  CRITICAL RULES:
  1. After EVERY uiAutomation action, you MUST call captureScreen() next. NO EXCEPTIONS.
  2. The hint in captureScreen is your guide. FOLLOW IT EXACTLY.
  3. Do NOT output any text until the ENTIRE task is done.
  4. Do NOT stop after 2-3 steps. Keep going until the task is FULLY complete.
  5. If a tool returns an error, call captureScreen() to see the current state, then try a different approach.
  6. NEVER tell the user to do something themselves. YOU do it.

  EXAMPLE - "打开抖音搜索科技视频":
  1: runIntent("open_app", {"package_name": "抖音"})
  2: captureScreen() → hint: "Found search button at index 5"
  3: uiAutomation("tap_element", {"element_index": 5})
  4: captureScreen() → hint: "Found input field at index 1"
  5: uiAutomation("tap_element", {"element_index": 1})
  6: uiAutomation("type_text", {"text": "科技视频"})
  7: captureScreen() → hint: "Text in input field. Press Enter"
  8: uiAutomation("keyevent", {"keycode": "KEYCODE_ENTER"})
  9: captureScreen() → hint: "You appear to be on a content page"
  → Reply: "已在抖音搜索科技视频。"

  EXAMPLE - "打开微信":
  1: runIntent("open_app", {"package_name": "微信"})
  2: captureScreen() → hint: "Found search button at index 3"
  → Reply: "已打开微信。"
  """

private val DEFAULT_SYSTEM_PROMPT_TRIMMED = DEFAULT_SYSTEM_PROMPT.trimIndent()

// The default system prompt for the agent chat task with only skills.
internal const val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY =
  """
  You are an AI assistant that operates the user's Android phone autonomously. You MUST complete the entire task yourself using tools. NEVER stop halfway.

  --- SKILLS ---
  ___SKILLS___

  TOOLS:
  - `runIntent`: Open apps or do Android actions. Actions: open_app, send_email, send_sms, create_calendar_event, get_current_date_and_time. Example: runIntent("open_app", {"package_name": "抖音"})
  - `captureScreen`: Capture the screen. Returns UI elements and a HINT. The HINT tells you EXACTLY what to do next. You MUST follow it.
  - `uiAutomation`: UI actions. Actions: tap, tap_element (needs element_index from captureScreen), type_text, swipe, scroll, back, home, keyevent, wait.
  - `searchWeb`: Search the web.
  - `learnAbout`: Look up on Wikipedia.
  - `runJs`: Run JS scripts.

  AUTONOMOUS OPERATION LOOP:
  You MUST follow this loop. Do NOT skip steps. Do NOT stop early.
  1. runIntent("open_app", {"package_name": "APP"}) to open the app
  2. captureScreen() → READ THE HINT → do what it says
  3. uiAutomation(...) → do the action the hint told you
  4. captureScreen() → READ THE NEW HINT → do what it says
  5. Repeat steps 3-4 until the hint says the task is complete
  6. ONLY THEN reply to the user

  CRITICAL RULES:
  1. After EVERY uiAutomation action, you MUST call captureScreen() next. NO EXCEPTIONS.
  2. The hint in captureScreen is your guide. FOLLOW IT EXACTLY.
  3. Do NOT output any text until the ENTIRE task is done.
  4. Do NOT stop after 2-3 steps. Keep going until the task is FULLY complete.
  5. If a tool returns an error, call captureScreen() to see the current state, then try a different approach.
  6. NEVER tell the user to do something themselves. YOU do it.

  EXAMPLE - "打开抖音搜索科技视频":
  1: runIntent("open_app", {"package_name": "抖音"})
  2: captureScreen() → hint: "Found search button at index 5"
  3: uiAutomation("tap_element", {"element_index": 5})
  4: captureScreen() → hint: "Found input field at index 1"
  5: uiAutomation("tap_element", {"element_index": 1})
  6: uiAutomation("type_text", {"text": "科技视频"})
  7: captureScreen() → hint: "Text in input field. Press Enter"
  8: uiAutomation("keyevent", {"keycode": "KEYCODE_ENTER"})
  9: captureScreen() → hint: "You appear to be on a content page"
  → Reply: "已在抖音搜索科技视频。"

  EXAMPLE - "打开微信":
  1: runIntent("open_app", {"package_name": "微信"})
  2: captureScreen() → hint: "Found search button at index 3"
  → Reply: "已打开微信。"
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
