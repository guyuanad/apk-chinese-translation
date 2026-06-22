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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "TaskFSM"

/**
 * FSM States for task execution.
 * Each state represents a screen state in the app navigation flow.
 */
enum class FSMState {
    HOME,               // Home screen / launcher
    APP_OPENING,        // App is being opened
    APP_OPENED,         // App has been opened
    SEARCH_BUTTON_FOUND, // Search button/icon found on screen
    SEARCH_PAGE,        // On search input page
    TEXT_ENTERED,       // Text has been typed into input field
    SEARCH_SUBMITTED,   // Search has been submitted
    RESULTS_PAGE,       // On results/content page
    CONTACT_FOUND,      // Contact found in messaging app
    CHAT_OPENED,        // Chat conversation opened
    MESSAGE_READ,       // Message has been read (needs model to generate reply)
    REPLY_TYPED,        // Reply text has been typed
    MESSAGE_SENT,       // Message has been sent
    SETTINGS_OPENED,    // Settings app opened
    SETTING_FOUND,      // Specific setting found
    SETTING_CHANGED,    // Setting value changed
    DONE,               // Task completed successfully
    ERROR               // Task failed
}

/**
 * Result of an FSM execution step.
 */
data class FSMStepResult(
    val nextState: FSMState,
    val message: String = "",
    val data: Map<String, Any> = emptyMap()
)

/**
 * Result of a complete FSM execution.
 */
data class FSMResult(
    val status: String,  // "success", "partial", "need_input", "error"
    val message: String,
    val data: Map<String, Any> = emptyMap()
)

/**
 * Task Template definitions.
 * Each template has a name, description, required parameters, and an FSM executor.
 */
object TaskTemplates {

    data class TemplateDef(
        val name: String,
        val description: String,
        val params: List<String>,
        val example: String
    )

    val TEMPLATES = mapOf(
        "app_search" to TemplateDef(
            name = "app_search",
            description = "打开app并搜索内容",
            params = listOf("app", "query"),
            example = """executeTask("app_search", {"app": "抖音", "query": "科技视频"})"""
        ),
        "open_app" to TemplateDef(
            name = "open_app",
            description = "打开一个app",
            params = listOf("app"),
            example = """executeTask("open_app", {"app": "微信"})"""
        ),
        "send_message" to TemplateDef(
            name = "send_message",
            description = "在社交app中发送消息",
            params = listOf("app", "contact", "message"),
            example = """executeTask("send_message", {"app": "微信", "contact": "张三", "message": "好的"})"""
        ),
        "check_and_reply" to TemplateDef(
            name = "check_and_reply",
            description = "检查社交app的新消息并回复",
            params = listOf("app", "policy"),
            example = """executeTask("check_and_reply", {"app": "微信", "policy": "礼貌简短回复"})"""
        ),
        "send_reply" to TemplateDef(
            name = "send_reply",
            description = "发送回复消息（在check_and_reply之后使用）",
            params = listOf("app", "contact", "message"),
            example = """executeTask("send_reply", {"app": "微信", "contact": "张三", "message": "我明天来"})"""
        ),
        "settings_change" to TemplateDef(
            name = "settings_change",
            description = "修改系统设置",
            params = listOf("setting", "value"),
            example = """executeTask("settings_change", {"setting": "亮度", "value": "50%"})"""
        ),
        "app_browse" to TemplateDef(
            name = "app_browse",
            description = "打开app浏览内容",
            params = listOf("app"),
            example = """executeTask("app_browse", {"app": "小红书"})"""
        )
    )

    /**
     * Generate the template list description for the system prompt.
     */
    fun getTemplateListForPrompt(): String {
        return TEMPLATES.entries.map { (name, def) ->
            "- $name(${def.params.joinToString(", ")}): ${def.description}. 例: ${def.example}"
        }.joinToString("\n")
    }
}

/**
 * FSM Executor - runs the state machine for a given template.
 */
object FSMExecutor {

    private val searchKeywords = listOf("搜索", "search", "Search", "搜一搜", "查搜索")
    private val submitKeywords = listOf("搜索", "搜素", "搜索一下", "search", "Search", "确定", "完成", "发送")

    /**
     * Execute a task template by running its FSM.
     */
    suspend fun execute(
        context: Context,
        templateName: String,
        params: Map<String, Any>
    ): FSMResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "FSMExecutor: Starting template=$templateName, params=$params")

        try {
            when (templateName) {
                "app_search" -> executeAppSearch(context, params)
                "open_app" -> executeOpenApp(context, params)
                "send_message" -> executeSendMessage(context, params)
                "check_and_reply" -> executeCheckAndReply(context, params)
                "send_reply" -> executeSendReply(context, params)
                "settings_change" -> executeSettingsChange(context, params)
                "app_browse" -> executeAppBrowse(context, params)
                else -> FSMResult("error", "Unknown template: $templateName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FSMExecutor error: ${e.message}", e)
            FSMResult("error", "FSM execution failed: ${e.message}")
        }
    }

    // ==================== APP_SEARCH FSM ====================

    private suspend fun executeAppSearch(context: Context, params: Map<String, Any>): FSMResult {
        val app = params["app"] as? String ?: return FSMResult("error", "Missing parameter: app")
        val query = params["query"] as? String ?: return FSMResult("error", "Missing parameter: query")

        Log.d(TAG, "FSM[app_search]: app=$app, query=$query")

        // State: HOME → APP_OPENING
        val openResult = openApp(context, app)
        if (openResult.nextState == FSMState.ERROR) {
            return FSMResult("error", openResult.message)
        }
        delay(3000)

        // State: APP_OPENED → Find search button
        val searchResult = findAndTapSearchButton(context)
        if (searchResult.nextState == FSMState.ERROR) {
            return FSMResult("partial", "已打开${app}，但未找到搜索按钮。${searchResult.message}")
        }
        delay(2000)

        // State: SEARCH_PAGE → Find input field and type
        val typeResult = findInputAndType(context, query)
        if (typeResult.nextState == FSMState.ERROR) {
            return FSMResult("partial", "已打开搜索页面，但输入文字失败。${typeResult.message}")
        }
        delay(500)

        // State: TEXT_ENTERED → Submit search
        val submitResult = submitSearch(context, query)
        if (submitResult.nextState == FSMState.ERROR) {
            return FSMResult("partial", "已输入'${query}'，但提交搜索失败。${submitResult.message}")
        }
        delay(2000)

        // State: RESULTS_PAGE → Verify
        val verifyResult = verifyResultsPage(context)

        return FSMResult(
            status = if (verifyResult.nextState == FSMState.RESULTS_PAGE) "success" else "partial",
            message = if (verifyResult.nextState == FSMState.RESULTS_PAGE)
                "已在${app}搜索'${query}'。"
            else
                "已在${app}搜索'${query}'，但无法确认搜索结果页面。",
            data = mapOf("app" to app, "query" to query, "search_completed" to true)
        )
    }

    // ==================== OPEN_APP FSM ====================

    private suspend fun executeOpenApp(context: Context, params: Map<String, Any>): FSMResult {
        val app = params["app"] as? String ?: return FSMResult("error", "Missing parameter: app")

        val openResult = openApp(context, app)
        if (openResult.nextState == FSMState.ERROR) {
            return FSMResult("error", openResult.message)
        }
        delay(2000)

        // Verify app is open
        val screen = UiAutomationTools.captureScreen(context)
        val fgPackage = screen["foreground_package"] as? String ?: ""
        val isOnHome = fgPackage.contains("launcher") || fgPackage.contains("nexuslauncher") || fgPackage.isEmpty()

        return FSMResult(
            status = if (!isOnHome) "success" else "partial",
            message = if (!isOnHome) "已打开${app}。" else "可能未成功打开${app}。",
            data = mapOf("app" to app, "foreground_package" to fgPackage)
        )
    }

    // ==================== SEND_MESSAGE FSM ====================

    private suspend fun executeSendMessage(context: Context, params: Map<String, Any>): FSMResult {
        val app = params["app"] as? String ?: return FSMResult("error", "Missing parameter: app")
        val contact = params["contact"] as? String ?: return FSMResult("error", "Missing parameter: contact")
        val message = params["message"] as? String ?: return FSMResult("error", "Missing parameter: message")

        Log.d(TAG, "FSM[send_message]: app=$app, contact=$contact, message=$message")

        // Open app
        val openResult = openApp(context, app)
        if (openResult.nextState == FSMState.ERROR) {
            return FSMResult("error", openResult.message)
        }
        delay(3000)

        // Find and tap on contact
        val contactResult = findAndTapContact(context, contact)
        if (contactResult.nextState == FSMState.ERROR) {
            return FSMResult("partial", "已打开${app}，但未找到联系人'${contact}'。${contactResult.message}")
        }
        delay(2000)

        // Type and send message
        val sendResult = typeAndSendMessage(context, message)
        if (sendResult.nextState == FSMState.ERROR) {
            return FSMResult("partial", "已打开与'${contact}'的对话，但发送消息失败。${sendResult.message}")
        }

        return FSMResult(
            status = "success",
            message = "已在${app}向${contact}发送消息：${message}",
            data = mapOf("app" to app, "contact" to contact, "message" to message, "sent" to true)
        )
    }

    // ==================== CHECK_AND_REPLY FSM ====================

    private suspend fun executeCheckAndReply(context: Context, params: Map<String, Any>): FSMResult {
        val app = params["app"] as? String ?: return FSMResult("error", "Missing parameter: app")
        val policy = params["policy"] as? String ?: "礼貌简短回复"

        Log.d(TAG, "FSM[check_and_reply]: app=$app, policy=$policy")

        // Open app
        val openResult = openApp(context, app)
        if (openResult.nextState == FSMState.ERROR) {
            return FSMResult("error", openResult.message)
        }
        delay(3000)

        // Find unread messages
        val unreadResult = findUnreadMessages(context)
        if (unreadResult.nextState == FSMState.ERROR) {
            return FSMResult("partial", "已打开${app}，但未找到未读消息。${unreadResult.message}")
        }
        delay(1500)

        // Read the message content
        val readResult = readCurrentMessage(context)

        // Return need_input status - the model needs to generate a reply
        return FSMResult(
            status = "need_input",
            message = "在${app}中发现新消息。请根据以下消息内容生成回复。",
            data = mapOf(
                "app" to app,
                "policy" to policy,
                "messages" to (readResult.data["messages"] ?: emptyList<Any>()),
                "contact" to (readResult.data["contact"] ?: ""),
                "hint" to "请阅读上面的消息，然后用executeTask(\"send_reply\", {\"app\": \"${app}\", \"contact\": \"联系人\", \"message\": \"你的回复\"})发送回复。"
            )
        )
    }

    // ==================== SEND_REPLY FSM ====================

    private suspend fun executeSendReply(context: Context, params: Map<String, Any>): FSMResult {
        val app = params["app"] as? String ?: return FSMResult("error", "Missing parameter: app")
        val contact = params["contact"] as? String ?: ""
        val message = params["message"] as? String ?: return FSMResult("error", "Missing parameter: message")

        Log.d(TAG, "FSM[send_reply]: app=$app, contact=$contact, message=$message")

        // If we need to find the contact first
        if (contact.isNotEmpty()) {
            // Check if we're already in a chat
            val screen = UiAutomationTools.captureScreen(context)
            val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()
            val hasInputField = elements.any { (it["is_editable"] as? Boolean ?: false) }

            if (!hasInputField) {
                // Need to find and tap contact
                val contactResult = findAndTapContact(context, contact)
                if (contactResult.nextState == FSMState.ERROR) {
                    return FSMResult("partial", "未找到联系人'${contact}'。${contactResult.message}")
                }
                delay(1500)
            }
        }

        // Type and send message
        val sendResult = typeAndSendMessage(context, message)
        if (sendResult.nextState == FSMState.ERROR) {
            return FSMResult("partial", "发送回复失败。${sendResult.message}")
        }

        return FSMResult(
            status = "success",
            message = "已发送回复：$message",
            data = mapOf("app" to app, "contact" to contact, "message" to message, "sent" to true)
        )
    }

    // ==================== SETTINGS_CHANGE FSM ====================

    private suspend fun executeSettingsChange(context: Context, params: Map<String, Any>): FSMResult {
        val setting = params["setting"] as? String ?: return FSMResult("error", "Missing parameter: setting")
        val value = params["value"] as? String ?: return FSMResult("error", "Missing parameter: value")

        Log.d(TAG, "FSM[settings_change]: setting=$setting, value=$value")

        // Open Settings
        val openResult = openApp(context, "设置")
        if (openResult.nextState == FSMState.ERROR) {
            return FSMResult("error", openResult.message)
        }
        delay(2000)

        // Find the setting
        val screen = UiAutomationTools.captureScreen(context)
        val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

        val settingIdx = elements.indexOfFirst { el ->
            val text = el["text"] as? String ?: ""
            val desc = el["content_description"] as? String ?: ""
            text.contains(setting) || desc.contains(setting)
        }

        if (settingIdx >= 0) {
            val idx = elements[settingIdx]["index"] as? Int ?: -1
            if (idx >= 0) {
                UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                delay(1500)

                // Try to set the value (this is app-specific and may not always work)
                val valueScreen = UiAutomationTools.captureScreen(context)
                val valueElements = valueScreen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

                // Look for a slider or input field
                val inputIdx = valueElements.indexOfFirst { el ->
                    (el["is_editable"] as? Boolean ?: false) ||
                    (el["class"] as? String ?: "").contains("SeekBar") ||
                    (el["class"] as? String ?: "").contains("Slider")
                }

                if (inputIdx >= 0) {
                    // For now, just report that we found the setting
                    return FSMResult("success", "已打开${setting}设置页面。请手动调整到${value}。",
                        mapOf("setting" to setting, "value" to value))
                }
            }
        }

        return FSMResult("partial", "已打开设置，但未找到'${setting}'。",
            mapOf("setting" to setting, "value" to value))
    }

    // ==================== APP_BROWSE FSM ====================

    private suspend fun executeAppBrowse(context: Context, params: Map<String, Any>): FSMResult {
        val app = params["app"] as? String ?: return FSMResult("error", "Missing parameter: app")

        val openResult = openApp(context, app)
        if (openResult.nextState == FSMState.ERROR) {
            return FSMResult("error", openResult.message)
        }
        delay(2000)

        val screen = UiAutomationTools.captureScreen(context)
        val fgPackage = screen["foreground_package"] as? String ?: ""
        val isOnHome = fgPackage.contains("launcher") || fgPackage.isEmpty()

        return FSMResult(
            status = if (!isOnHome) "success" else "partial",
            message = if (!isOnHome) "已打开${app}。" else "可能未成功打开${app}。",
            data = mapOf("app" to app)
        )
    }

    // ==================== SHARED FSM STEP FUNCTIONS ====================

    private suspend fun openApp(context: Context, appName: String): FSMStepResult {
        Log.d(TAG, "FSM Step: openApp($appName)")
        val escapedAppName = appName.replace("\\", "\\\\").replace("\"", "\\\"")
        val result = IntentHandler.handleAction(context, "open_app", "{\"package_name\": \"$escapedAppName\"}") { permission ->
            // No permission callback in FSM context - always grant
            true
        }

        return if (result == "succeeded") {
            FSMStepResult(FSMState.APP_OPENED, "App $appName opened successfully")
        } else {
            FSMStepResult(FSMState.ERROR, "Failed to open $appName: $result")
        }
    }

    private suspend fun findAndTapSearchButton(context: Context): FSMStepResult {
        Log.d(TAG, "FSM Step: findAndTapSearchButton")

        for (attempt in 1..3) {
            val screen = UiAutomationTools.captureScreen(context)
            val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

            // Log all elements for debugging
            Log.d(TAG, "FSM: Screen has ${elements.size} elements:")
            for (el in elements) {
                val idx = el["index"] as? Int ?: -1
                val text = el["text"] as? String ?: ""
                val desc = el["content_description"] as? String ?: ""
                val clickable = el["is_clickable"] as? Boolean ?: false
                val editable = el["is_editable"] as? Boolean ?: false
                val bounds = el["bounds"] as? String ?: ""
                val cls = el["class"] as? String ?: ""
                Log.d(TAG, "FSM:   [$idx] text='$text' desc='$desc' clickable=$clickable editable=$editable bounds='$bounds' class='$cls'")
            }

            val searchIdx = UiAutomationTools.findSearchElementIndex(elements)
            Log.d(TAG, "FSM: findSearchElementIndex returned: $searchIdx")

            if (searchIdx != null) {
                Log.d(TAG, "FSM: Found search element at index $searchIdx, tapping")
                val tapResult = UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $searchIdx}")
                if (tapResult["status"] == "success") {
                    delay(1500)
                    // Verify: check if we're now on a search/input page
                    val verifyScreen = UiAutomationTools.captureScreen(context)
                    val verifyElements = verifyScreen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()
                    val hasInputField = verifyElements.any { el ->
                        (el["is_editable"] as? Boolean ?: false) ||
                        (el["class"] as? String ?: "").let { it.contains("EditText") || it.contains("SearchView") }
                    }
                    if (hasInputField) {
                        Log.d(TAG, "FSM: Verified search page with input field")
                        return FSMStepResult(FSMState.SEARCH_PAGE, "Tapped search button at index $searchIdx, now on search page")
                    }
                    Log.d(TAG, "FSM: Tapped index $searchIdx but no input field found, may have tapped wrong element")
                }
            }
            Log.d(TAG, "FSM: Search button not found or wrong element, attempt $attempt/3")
            delay(1000)
        }

        return FSMStepResult(FSMState.ERROR, "Could not find search button after 3 attempts")
    }

    private suspend fun findInputAndType(context: Context, text: String): FSMStepResult {
        Log.d(TAG, "FSM Step: findInputAndType($text)")

        for (attempt in 1..5) {
            val screen = UiAutomationTools.captureScreen(context)
            val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

            // Find editable field
            val inputIdx = elements.indexOfFirst { el ->
                (el["is_editable"] as? Boolean ?: false) ||
                (el["class"] as? String ?: "").let { it.contains("EditText") || it.contains("SearchView") }
            }

            if (inputIdx >= 0) {
                val idx = elements[inputIdx]["index"] as? Int ?: continue
                Log.d(TAG, "FSM: Found input field at index $idx, tapping")
                UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                delay(300)
            }

            // Type text via accessibility
            val typeResult = UiAutomationTools.typeTextViaAccessibility(text)
            Log.d(TAG, "FSM: typeTextViaAccessibility result=$typeResult")

            if (!typeResult) {
                // Fallback
                val escapedQuery = text.replace("\\", "\\\\").replace("\"", "\\\"")
                UiAutomationTools.executeUiAction(context, "type_text", "{\"text\": \"$escapedQuery\"}")
            }

            delay(500)

            // Verify text was entered
            val verifyScreen = UiAutomationTools.captureScreen(context)
            val verifyElements = verifyScreen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()
            val textInInput = verifyElements.any { el ->
                val editable = el["is_editable"] as? Boolean ?: false
                val elText = el["text"] as? String ?: ""
                editable && elText.contains(text)
            }

            if (textInInput) {
                Log.d(TAG, "FSM: Text verified in input field")
                return FSMStepResult(FSMState.TEXT_ENTERED, "Text '$text' entered and verified")
            }

            Log.d(TAG, "FSM: Text not found in input, attempt $attempt/5")
            delay(500)
        }

        return FSMStepResult(FSMState.ERROR, "Failed to type text after 5 attempts")
    }

    private suspend fun submitSearch(context: Context, query: String): FSMStepResult {
        Log.d(TAG, "FSM Step: submitSearch($query)")

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
                for (el in els) {
                    val text = el["text"] as? String ?: ""
                    val desc = el["content_description"] as? String ?: ""
                    val clickable = el["is_clickable"] as? Boolean ?: false
                    val editable = el["is_editable"] as? Boolean ?: false
                    val idx = el["index"] as? Int ?: continue
                    if (!editable && clickable && submitKeywords.any { text == it || desc == it }) {
                        UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                        return@to true
                    }
                }
                false
            }
        )

        for ((strategyName, strategy) in submitStrategies) {
            Log.d(TAG, "FSM: Trying submit strategy: $strategyName")
            try {
                strategy()
            } catch (e: Exception) {
                Log.d(TAG, "FSM: Strategy $strategyName threw: ${e.message}")
            }
            delay(2000)

            // Check if search was submitted
            val checkScreen = UiAutomationTools.captureScreen(context)
            val checkElements = checkScreen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()
            val stillHasQueryInInput = checkElements.any { el ->
                val editable = el["is_editable"] as? Boolean ?: false
                val elText = el["text"] as? String ?: ""
                editable && elText.contains(query)
            }

            if (!stillHasQueryInInput) {
                Log.d(TAG, "FSM: Search submitted successfully via $strategyName")
                return FSMStepResult(FSMState.SEARCH_SUBMITTED, "Search submitted via $strategyName")
            }
            Log.d(TAG, "FSM: Still on input page after $strategyName")
        }

        return FSMStepResult(FSMState.ERROR, "All submit strategies failed")
    }

    private suspend fun verifyResultsPage(context: Context): FSMStepResult {
        Log.d(TAG, "FSM Step: verifyResultsPage")
        val screen = UiAutomationTools.captureScreen(context)
        val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

        // Check if we're on a content page (has many elements, no editable field with our query)
        val hasEditableWithQuery = elements.any { el ->
            val editable = el["is_editable"] as? Boolean ?: false
            editable
        }

        val contentCount = elements.count { el ->
            val text = el["text"] as? String ?: ""
            text.isNotEmpty()
        }

        return if (!hasEditableWithQuery && contentCount > 3) {
            FSMStepResult(FSMState.RESULTS_PAGE, "On results page with $contentCount elements")
        } else {
            FSMStepResult(FSMState.SEARCH_PAGE, "May still be on search page")
        }
    }

    private suspend fun findAndTapContact(context: Context, contactName: String): FSMStepResult {
        Log.d(TAG, "FSM Step: findAndTapContact($contactName)")

        for (attempt in 1..3) {
            val screen = UiAutomationTools.captureScreen(context)
            val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

            // Find contact by name
            val contactIdx = elements.indexOfFirst { el ->
                val text = el["text"] as? String ?: ""
                val desc = el["content_description"] as? String ?: ""
                text.contains(contactName) || desc.contains(contactName)
            }

            if (contactIdx >= 0) {
                val idx = elements[contactIdx]["index"] as? Int ?: continue
                Log.d(TAG, "FSM: Found contact '$contactName' at index $idx, tapping")
                UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                return FSMStepResult(FSMState.CHAT_OPENED, "Opened chat with $contactName")
            }

            // Try using search to find contact
            if (attempt == 1) {
                val searchIdx = UiAutomationTools.findSearchElementIndex(elements)
                if (searchIdx != null) {
                    Log.d(TAG, "FSM: Using search to find contact")
                    UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $searchIdx}")
                    delay(1000)
                    UiAutomationTools.typeTextViaAccessibility(contactName)
                    delay(2000)
                    continue
                }
            }

            Log.d(TAG, "FSM: Contact not found, attempt $attempt/3")
            delay(1000)
        }

        return FSMStepResult(FSMState.ERROR, "Could not find contact '$contactName'")
    }

    private suspend fun typeAndSendMessage(context: Context, message: String): FSMStepResult {
        Log.d(TAG, "FSM Step: typeAndSendMessage($message)")

        // Find input field
        val screen = UiAutomationTools.captureScreen(context)
        val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

        val inputIdx = elements.indexOfFirst { el ->
            (el["is_editable"] as? Boolean ?: false) ||
            (el["class"] as? String ?: "").let { it.contains("EditText") }
        }

        if (inputIdx >= 0) {
            val idx = elements[inputIdx]["index"] as? Int ?: -1
            if (idx >= 0) {
                UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                delay(300)
            }
        }

        // Type message
        val typeResult = UiAutomationTools.typeTextViaAccessibility(message)
        if (!typeResult) {
            val escapedMsg = message.replace("\\", "\\\\").replace("\"", "\\\"")
            UiAutomationTools.executeUiAction(context, "type_text", "{\"text\": \"$escapedMsg\"}")
        }
        delay(500)

        // Find and click send button
        val sendKeywords = listOf("发送", "Send", "send", "发送消息", "发", "▶", "➤")
        val sendScreen = UiAutomationTools.captureScreen(context)
        val sendElements = sendScreen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

        val sendIdx = sendElements.indexOfFirst { el ->
            val text = el["text"] as? String ?: ""
            val desc = el["content_description"] as? String ?: ""
            val clickable = el["is_clickable"] as? Boolean ?: false
            clickable && sendKeywords.any { text.contains(it) || desc.contains(it) }
        }

        if (sendIdx >= 0) {
            val idx = sendElements[sendIdx]["index"] as? Int ?: -1
            if (idx >= 0) {
                UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                return FSMStepResult(FSMState.MESSAGE_SENT, "Message sent: $message")
            }
        }

        // Fallback: try Enter key
        UiAutomationTools.executeUiAction(context, "keyevent", "{\"keycode\": \"KEYCODE_ENTER\"}")
        return FSMStepResult(FSMState.MESSAGE_SENT, "Message sent via Enter: $message")
    }

    private suspend fun findUnreadMessages(context: Context): FSMStepResult {
        Log.d(TAG, "FSM Step: findUnreadMessages")

        val screen = UiAutomationTools.captureScreen(context)
        val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

        // Look for unread indicators (red dots, badges, "N条新消息")
        val unreadKeywords = listOf("条新消息", "条未读", "新消息", "未读", "unread")

        for (el in elements) {
            val text = el["text"] as? String ?: ""
            val desc = el["content_description"] as? String ?: ""
            val idx = el["index"] as? Int ?: continue
            val clickable = el["is_clickable"] as? Boolean ?: false

            if (unreadKeywords.any { text.contains(it) || desc.contains(it) }) {
                if (clickable) {
                    UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                    return FSMStepResult(FSMState.CHAT_OPENED, "Found unread indicator, tapped")
                }
            }
        }

        // If no unread indicators found, just look for recent conversations
        // and tap the first one that looks like a chat
        val chatKeywords = listOf("消息", "聊天", "对话", "chat", "Chat")
        for (el in elements) {
            val text = el["text"] as? String ?: ""
            val desc = el["content_description"] as? String ?: ""
            val idx = el["index"] as? Int ?: continue
            val clickable = el["is_clickable"] as? Boolean ?: false

            if (clickable && chatKeywords.any { text.contains(it) || desc.contains(it) }) {
                UiAutomationTools.executeUiAction(context, "tap_element", "{\"element_index\": $idx}")
                return FSMStepResult(FSMState.CHAT_OPENED, "Found chat element, tapped")
            }
        }

        return FSMStepResult(FSMState.ERROR, "No unread messages found")
    }

    private suspend fun readCurrentMessage(context: Context): FSMStepResult {
        Log.d(TAG, "FSM Step: readCurrentMessage")

        val screen = UiAutomationTools.captureScreen(context)
        val elements = screen["interactive_elements"] as? List<Map<String, Any>> ?: emptyList()

        // Extract messages from the screen
        val messages = mutableListOf<Map<String, String>>()
        var contactName = ""

        for (el in elements) {
            val text = el["text"] as? String ?: ""
            val desc = el["content_description"] as? String ?: ""

            // Try to identify contact name (usually at the top of the chat)
            if (contactName.isEmpty()) {
                val idx = el["index"] as? Int ?: continue
                // Contact name is usually one of the first clickable elements with short text
                if (idx < 5 && text.length in 2..10 && (el["is_clickable"] as? Boolean ?: false)) {
                    contactName = text
                }
            }

            // Collect text that looks like messages
            if (text.isNotEmpty() && text.length > 1 && text.length < 500) {
                val isEditable = el["is_editable"] as? Boolean ?: false
                if (!isEditable) {
                    messages.add(mapOf("text" to text, "desc" to desc))
                }
            }
        }

        return FSMStepResult(
            FSMState.MESSAGE_READ,
            "Read ${messages.size} messages from $contactName",
            mapOf("messages" to messages, "contact" to contactName)
        )
    }
}
