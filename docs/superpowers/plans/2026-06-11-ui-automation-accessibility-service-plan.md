# UI Automation & Accessibility Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new AI tools (`captureScreen` and `uiAutomation`) that let the agent read the current screen and perform UI interactions (tap, type, swipe, navigate) across any Android app via AccessibilityService and shell commands.

**Architecture:** Three-layer design:
- **Agent layer** (`AgentTools.kt`): Exposes `@Tool`-annotated methods to the LLM.
- **Accessibility Service layer** (`UiAutomationService.kt`): Singleton `AccessibilityService` that maintains the root node reference and tracks window changes.
- **Execution layer** (`UiAutomationTools.kt`): Bridges agent tools with the accessibility service and `Runtime.exec` shell commands.

**Tech Stack:** Kotlin coroutines, Android `AccessibilityService`, `Runtime.exec` for `input` commands, Moshi for JSON parsing, Hilt DI.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `UiAutomationService.kt` | **Create** | AccessibilityService implementation: manages root node, foreground package, window change events, node tree traversal |
| `UiAutomationTools.kt` | **Create** | Tool implementations: `captureScreen()`, `executeUiAction()`, `execInputCommand()`, service enablement check |
| `accessibility_service_config.xml` | **Create** | XML config for the AccessibilityService (event types, flags, capabilities) |
| `AgentTools.kt` | **Modify** | Add `captureScreen` and `uiAutomation` `@Tool` methods, integrate with `UiAutomationTools` |
| `AndroidManifest.xml` | **Modify** | Add `<service>` declaration for `UiAutomationService` |
| `strings.xml` | **Modify** | Add `accessibility_service_description` string resource |

---

## Task 1: Create Accessibility Service Config XML

**Files:**
- Create: `Android/src/app/src/main/res/xml/accessibility_service_config.xml`

- [ ] **Step 1: Write the XML config file**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged|typeViewTextChanged|typeViewClicked"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:settingsActivity="com.google.ai.edge.gallery.MainActivity"
    android:canPerformGestures="true" />
```

- [ ] **Step 2: Commit**

```bash
git add Android/src/app/src/main/res/xml/accessibility_service_config.xml
git commit -m "feat: add accessibility service config XML for UI automation"
```

---

## Task 2: Add String Resource

**Files:**
- Modify: `Android/src/app/src/main/res/values/strings.xml` — add string before closing `</resources>` tag

- [ ] **Step 1: Add the accessibility service description string**

Find the end of the `<resources>` block and add before `</resources>`:

```xml
  <string name="accessibility_service_description" translatable="false">
    Edge Gallery UI Automation service enables the AI assistant to read screen content
    and perform UI interactions (tap, type, swipe) on your behalf. This service is
    required for the Agent Chat feature to interact with other apps. You can enable
    or disable this service at any time in Settings → Accessibility.
  </string>
```

- [ ] **Step 2: Commit**

```bash
git add Android/src/app/src/main/res/values/strings.xml
git commit -m "feat: add accessibility service description string"
```

---

## Task 3: Create UiAutomationService

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/UiAutomationService.kt`

- [ ] **Step 1: Write the full UiAutomationService implementation**

```kotlin
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

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "UiAutomationSvc"

/**
 * Singleton accessor for UiAutomationService. The service is started by Android's
 * accessibility framework, so we store a reference here for other components to access.
 */
object UiAutomationServiceHolder {
  @Volatile
  var instance: UiAutomationService? = null
}

/**
 * AccessibilityService that tracks the current screen state and provides
 * methods for reading UI elements and waiting for window changes.
 */
class UiAutomationService : AccessibilityService() {

  // Latest root node — updated on every window state change.
  @Volatile
  private var rootNode: AccessibilityNodeInfo? = null

  // Latest foreground package name.
  @Volatile
  private var foregroundPackage: String? = null

  // Channel for notifying waiters of window changes.
  private val windowChangeChannel = Channel<Unit>(Channel.CONFLATED)

  override fun onServiceConnected() {
    super.onServiceConnected()
    UiAutomationServiceHolder.instance = this
    Log.d(TAG, "UiAutomationService connected")
  }

  override fun onInterrupt() {
    // No-op — we don't handle interruption.
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return

    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
        // Update root node.
        try {
          val newRoot = rootInActiveWindow
          val oldRoot = rootNode
          rootNode = newRoot
          // Recycle the old root to avoid memory leaks.
          oldRoot?.recycle()
        } catch (e: Exception) {
          Log.w(TAG, "Failed to update root node: ${e.message}")
        }

        // Update foreground package.
        foregroundPackage = event.packageName?.toString()

        // Notify waiters.
        try {
          windowChangeChannel.trySend(Unit)
        } catch (_: Exception) {}

        Log.d(TAG, "Window changed: package=$foregroundPackage")
      }
      else -> {}
    }
  }

  /** Returns the current root accessibility node, or null if not available. */
  fun getRootNode(): AccessibilityNodeInfo? = rootNode

  /** Returns the package name of the currently active app. */
  fun getForegroundPackageName(): String? = foregroundPackage

  /**
   * Suspends until a window change event is received, or until [timeoutMs] elapses.
   * Returns true if a window change was detected, false if timed out.
   */
  suspend fun waitForWindowChange(timeoutMs: Long): Boolean {
    return withTimeoutOrNull(timeoutMs) {
      windowChangeChannel.receive()
      true
    } ?: false
  }

  /**
   * Recursively traverses the accessibility node tree and collects interactive elements.
   *
   * @param maxDepth Maximum recursion depth (default 5).
   * @param maxElements Maximum number of elements to collect (default 200).
   * @return List of element info maps.
   */
  fun getInteractiveElements(maxDepth: Int = 5, maxElements: Int = 200): List<Map<String, Any>> {
    val root = rootNode ?: return emptyList()
    val elements = mutableListOf<Map<String, Any>>()
    traverseNode(root, elements, maxDepth, 0, maxElements)
    return elements
  }

  /** Returns a structured summary of the current screen. */
  fun getScreenInfo(): ScreenInfo {
    return ScreenInfo(
      foregroundPackage = foregroundPackage ?: "",
      rootNode = rootNode,
      interactiveElements = getInteractiveElements(),
    )
  }

  /**
   * Returns the element at [index] from the current interactive elements list,
   * or null if index is out of bounds.
   */
  fun getInteractiveElement(index: Int): AccessibilityNodeInfo? {
    val elements = getInteractiveElements()
    if (index < 0 || index >= elements.size) return null

    val elementInfo = elements[index]
    val bounds = elementInfo["bounds"] as? Map<String, Int> ?: return null
    val rect = Rect(
      bounds["left"] ?: 0,
      bounds["top"] ?: 0,
      bounds["right"] ?: 0,
      bounds["bottom"] ?: 0,
    )

    // Find the node by matching bounds and text.
    return findNodeByBounds(rootNode, rect, elementInfo["text"] as? String)
  }

  private fun findNodeByBounds(
    node: AccessibilityNodeInfo?,
    bounds: Rect,
    text: String?
  ): AccessibilityNodeInfo? {
    if (node == null) return null
    val nodeRect = Rect()
    node.getBoundsInScreen(nodeRect)
    if (nodeRect == bounds && (text == null || node.text?.toString() == text)) {
      return node
    }
    for (i in 0 until node.childCount) {
      val child = node.getChild(i)
      val result = findNodeByBounds(child, bounds, text)
      if (result != null) return result
      child?.recycle()
    }
    return null
  }

  private fun traverseNode(
    node: AccessibilityNodeInfo,
    elements: MutableList<Map<String, Any>>,
    maxDepth: Int,
    currentDepth: Int,
    maxElements: Int,
  ) {
    if (elements.size >= maxElements) return
    if (currentDepth > maxDepth) return

    val isClickable = node.isClickable
    val isEditable = node.isEditable
    val isFocusable = node.isFocusable

    if (isClickable || isEditable || isFocusable) {
      val rect = Rect()
      node.getBoundsInScreen(rect)
      if (rect.width() > 0 && rect.height() > 0) {
        elements.add(
          mapOf(
            "index" to elements.size,
            "text" to (node.text?.toString() ?: ""),
            "content_description" to (node.contentDescription?.toString() ?: ""),
            "class_name" to (node.className?.toString() ?: ""),
            "bounds" to mapOf(
              "left" to rect.left,
              "top" to rect.top,
              "right" to rect.right,
              "bottom" to rect.bottom,
            ),
            "center" to mapOf(
              "x" to (rect.left + rect.right) / 2,
              "y" to (rect.top + rect.bottom) / 2,
            ),
            "is_clickable" to isClickable,
            "is_editable" to isEditable,
          )
        )
      }
    }

    for (i in 0 until node.childCount) {
      val child = node.getChild(i)
      if (child != null) {
        traverseNode(child, elements, maxDepth, currentDepth + 1, maxElements)
        child.recycle()
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    UiAutomationServiceHolder.instance = null
    rootNode?.recycle()
    rootNode = null
    windowChangeChannel.close()
    Log.d(TAG, "UiAutomationService destroyed")
  }
}

/** Data class holding screen information. */
data class ScreenInfo(
  val foregroundPackage: String,
  val rootNode: AccessibilityNodeInfo?,
  val interactiveElements: List<Map<String, Any>>,
)
```

- [ ] **Step 2: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/UiAutomationService.kt
git commit -m "feat: create UiAutomationService for screen reading and window tracking"
```

---

## Task 4: Create UiAutomationTools

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/UiAutomationTools.kt`

- [ ] **Step 1: Write the full UiAutomationTools implementation**

```kotlin
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
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "UiAutomationTools"

object UiAutomationTools {

  /** Checks if the UiAutomationService is enabled in system settings. */
  fun isServiceEnabled(context: Context): Boolean {
    return try {
      val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
      ) ?: ""
      val serviceName = "${context.packageName}/com.google.ai.edge.gallery.customtasks.agentchat.UiAutomationService"
      enabledServices.contains(serviceName)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to check accessibility service: ${e.message}")
      false
    }
  }

  /**
   * Captures the current screen: takes a screenshot and extracts interactive elements.
   */
  suspend fun captureScreen(context: Context): Map<String, Any> {
    return withContext(Dispatchers.IO) {
      val service = UiAutomationServiceHolder.instance
      if (service == null) {
        return@withContext mapOf(
          "status" to "error",
          "message" to "Accessibility service is not enabled. Please enable it in Settings > Accessibility."
        )
      }

      try {
        // 1. Take screenshot via screencap.
        val screenshotPath = "/data/local/tmp/screen_capture.png"
        val screenshotResult = execShellCommand("screencap -p $screenshotPath")
        if (screenshotResult != 0) {
          Log.e(TAG, "Screenshot failed with exit code $screenshotResult")
          return@withContext mapOf(
            "status" to "error",
            "message" to "Screenshot failed. Exit code: $screenshotResult"
          )
        }

        // 2. Get screen info from accessibility service.
        val screenInfo = service.getScreenInfo()
        val elements = screenInfo.interactiveElements

        // 3. Build result.
        mapOf(
          "status" to "success",
          "foreground_package" to screenInfo.foregroundPackage,
          "screenshot_path" to screenshotPath,
          "interactive_elements" to elements,
          "element_count" to elements.size,
        )
      } catch (e: Exception) {
        Log.e(TAG, "captureScreen failed: ${e.message}", e)
        mapOf(
          "status" to "error",
          "message" to "Capture failed: ${e.message}"
        )
      }
    }
  }

  /**
   * Executes a UI automation action.
   */
  suspend fun executeUiAction(
    context: Context,
    action: String,
    parameters: String
  ): Map<String, String> {
    return withContext(Dispatchers.IO) {
      val service = UiAutomationServiceHolder.instance
      if (service == null) {
        return@withContext mapOf(
          "status" to "error",
          "action" to action,
          "message" to "Accessibility service is not enabled. Please enable it in Settings > Accessibility."
        )
      }

      try {
        when (action) {
          "open_app" -> executeOpenApp(parameters)
          "tap" -> executeTap(parameters)
          "tap_element" -> executeTapElement(context, service, parameters)
          "type_text" -> executeTypeText(parameters)
          "swipe" -> executeSwipe(parameters)
          "keyevent" -> executeKeyevent(parameters)
          "back" -> execInputCommand("keyevent KEYCODE_BACK")?.let {
            mapOf("status" to "success", "action" to action, "details" to "Back button pressed")
          } ?: errorResult(action, "Failed to send back key event")
          "home" -> execInputCommand("keyevent KEYCODE_HOME")?.let {
            mapOf("status" to "success", "action" to action, "details" to "Home button pressed")
          } ?: errorResult(action, "Failed to send home key event")
          "scroll" -> executeScroll(parameters)
          "wait" -> executeWait(service, parameters)
          else -> errorResult(action, "Unknown action: $action. Supported: open_app, tap, tap_element, type_text, swipe, keyevent, back, home, scroll, wait")
        }
      } catch (e: Exception) {
        Log.e(TAG, "executeUiAction failed: ${e.message}", e)
        mapOf("status" to "error", "action" to action, "message" to e.message ?: "Unknown error")
      }
    }
  }

  private fun executeOpenApp(parameters: String): Map<String, String> {
    val json = runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val packageName = json?.get("package_name")?.toString()?.trim('"')
    if (packageName.isNullOrEmpty()) {
      return errorResult("open_app", "Missing required parameter: package_name")
    }

    // Try monkey launch as it's the most reliable way to launch an app from shell.
    val exitCode = execShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    return if (exitCode == 0) {
      mapOf("status" to "success", "action" to "open_app", "details" to "Launched app: $packageName")
    } else {
      errorResult("open_app", "Failed to launch app: $packageName (exit code: $exitCode)")
    }
  }

  private fun executeTap(parameters: String): Map<String, String> {
    val json = runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val x = json?.get("x")?.toString()?.toIntOrNull()
    val y = json?.get("y")?.toString()?.toIntOrNull()
    if (x == null || y == null) {
      return errorResult("tap", "Missing required parameters: x, y")
    }
    val result = execInputCommand("tap $x $y")
    return result?.let {
      mapOf("status" to "success", "action" to "tap", "details" to "Tapped at coordinates ($x, $y)")
    } ?: errorResult("tap", "Failed to execute tap")
  }

  private fun executeTapElement(
    context: Context,
    service: UiAutomationService,
    parameters: String
  ): Map<String, String> {
    val json = runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val index = json?.get("element_index")?.toString()?.toIntOrNull()
    if (index == null) {
      return errorResult("tap_element", "Missing required parameter: element_index")
    }

    val element = service.getInteractiveElement(index)
    if (element == null) {
      return errorResult("tap_element", "Element at index $index not found. Try captureScreen first.")
    }

    val rect = android.graphics.Rect()
    element.getBoundsInScreen(rect)
    element.recycle()

    val x = (rect.left + rect.right) / 2
    val y = (rect.top + rect.bottom) / 2
    val result = execInputCommand("tap $x $y")
    return result?.let {
      mapOf("status" to "success", "action" to "tap_element", "details" to "Tapped element $index at ($x, $y)")
    } ?: errorResult("tap_element", "Failed to tap element")
  }

  private fun executeTypeText(parameters: String): Map<String, String> {
    val json = runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val text = json?.get("text")?.toString()?.trim('"')
    if (text.isNullOrEmpty()) {
      return errorResult("type_text", "Missing required parameter: text")
    }

    // Escape for shell: replace spaces with %s and escape double quotes.
    val escaped = text.replace("\"", "\\\"").replace(" ", "%s")
    val result = execInputCommand("text \"$escaped\"")
    return result?.let {
      mapOf("status" to "success", "action" to "type_text", "details" to "Typed: $text")
    } ?: errorResult("type_text", "Failed to type text")
  }

  private fun executeSwipe(parameters: String): Map<String, String> {
    val json = runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val x1 = json?.get("x1")?.toString()?.toIntOrNull()
    val y1 = json?.get("y1")?.toString()?.toIntOrNull()
    val x2 = json?.get("x2")?.toString()?.toIntOrNull()
    val y2 = json?.get("y2")?.toString()?.toIntOrNull()
    val duration = json?.get("duration")?.toString()?.toIntOrNull() ?: 300

    if (x1 == null || y1 == null || x2 == null || y2 == null) {
      return errorResult("swipe", "Missing required parameters: x1, y1, x2, y2")
    }

    val result = execInputCommand("swipe $x1 $y1 $x2 $y2 $duration")
    return result?.let {
      mapOf("status" to "success", "action" to "swipe", "details" to "Swiped from ($x1,$y1) to ($x2,$y2)")
    } ?: errorResult("swipe", "Failed to swipe")
  }

  private fun executeKeyevent(parameters: String): Map<String, String> {
    val json = runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val keycode = json?.get("keycode")?.toString()?.trim('"')
    if (keycode.isNullOrEmpty()) {
      return errorResult("keyevent", "Missing required parameter: keycode")
    }
    val result = execInputCommand("keyevent $keycode")
    return result?.let {
      mapOf("status" to "success", "action" to "keyevent", "details" to "Sent key event: $keycode")
    } ?: errorResult("keyevent", "Failed to send key event")
  }

  private fun executeScroll(parameters: String): Map<String, String> {
    val json = runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val direction = json?.get("direction")?.toString()?.trim('"')
    if (direction.isNullOrEmpty()) {
      return errorResult("scroll", "Missing required parameter: direction")
    }

    val command = when (direction) {
      "up" -> "swipe 500 1500 500 500 300"
      "down" -> "swipe 500 500 500 1500 300"
      "left" -> "swipe 900 1000 100 1000 300"
      "right" -> "swipe 100 1000 900 1000 300"
      else -> return errorResult("scroll", "Invalid direction: $direction. Use up/down/left/right.")
    }

    val result = execInputCommand(command)
    return result?.let {
      mapOf("status" to "success", "action" to "scroll", "details" to "Scrolled $direction")
    } ?: errorResult("scroll", "Failed to scroll")
  }

  private suspend fun executeWait(service: UiAutomationService, parameters: String): Map<String, String> {
    val json = runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val timeoutMs = json?.get("timeout_ms")?.toString()?.toLongOrNull() ?: 5000L

    val changed = service.waitForWindowChange(timeoutMs)
    return if (changed) {
      mapOf("status" to "success", "action" to "wait", "details" to "Window change detected within ${timeoutMs}ms")
    } else {
      mapOf("status" to "success", "action" to "wait", "details" to "Timed out after ${timeoutMs}ms (no window change)")
    }
  }

  /**
   * Executes an `input` shell command and returns the exit code (0 = success).
   */
  private fun execInputCommand(command: String): Int? {
    return execShellCommand("input $command")
  }

  /**
   * Executes a shell command and returns the exit code.
   */
  private fun execShellCommand(command: String): Int? {
    return try {
      val process = Runtime.getRuntime().exec(command)
      val exitCode = process.waitFor()
      // Log stderr for debugging.
      val errorOutput = BufferedReader(InputStreamReader(process.errorStream)).readText()
      if (errorOutput.isNotEmpty()) {
        Log.w(TAG, "Shell command stderr: $errorOutput")
      }
      exitCode
    } catch (e: Exception) {
      Log.e(TAG, "Shell command failed: $command — ${e.message}")
      null
    }
  }

  private fun errorResult(action: String, message: String): Map<String, String> {
    return mapOf("status" to "error", "action" to action, "message" to message)
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/UiAutomationTools.kt
git commit -m "feat: create UiAutomationTools with captureScreen and executeUiAction"
```

---

## Task 5: Register UiAutomationService in AndroidManifest.xml

**Files:**
- Modify: `Android/src/app/src/main/AndroidManifest.xml` — add `<service>` before closing `</application>` tag

- [ ] **Step 1: Add the UiAutomationService declaration**

Find the last `</service>` or component before `</application>` (after the Firebase AppMeasurementJobService block, around line 136) and add:

```xml

        <!-- UI Automation Accessibility Service -->
        <service
            android:name=".customtasks.agentchat.UiAutomationService"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="false"
            android:label="Edge Gallery UI Automation">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
```

- [ ] **Step 2: Commit**

```bash
git add Android/src/app/src/main/AndroidManifest.xml
git commit -m "feat: register UiAutomationService in AndroidManifest.xml"
```

---

## Task 6: Add captureScreen and uiAutomation tools to AgentTools.kt

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTools.kt`

- [ ] **Step 1: Add the two new @Tool methods**

Insert the following code **before** the `runIntent` `@Tool` method (around line 761, replacing the comment `// ---` if present):

```kotlin
  // --- UI Automation Tools ---

  @Tool(
    description =
      "Capture the current screen. Returns a screenshot file path, " +
        "the foreground app package name, and a list of interactive " +
        "UI elements (buttons, text fields, lists, etc.) with their bounds, text, " +
        "content description, and class name. Use this to understand what is on screen " +
        "before performing actions. Elements are indexed starting from 0."
  )
  fun captureScreen(): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("captureScreen")) {
        return@runBlocking mapOf("error" to "Too many calls to captureScreen", "status" to "blocked")
      }
      withToolLogging("captureScreen") {
        UiAutomationTools.captureScreen(context)
      }
    }
  }

  @Tool(
    description =
      "Perform a UI automation action on the current screen. " +
        "Supports actions: open_app, tap, tap_element, type_text, swipe, keyevent, " +
        "back, home, scroll, wait. Use captureScreen first to find element coordinates. " +
        "Actions: " +
        "- open_app: {\"package_name\": \"com.example.app\"} — Launch an app " +
        "- tap: {\"x\": 100, \"y\": 200} — Tap at coordinates " +
        "- tap_element: {\"element_index\": 0} — Tap the Nth element from captureScreen " +
        "- type_text: {\"text\": \"Hello\"} — Type text into focused field " +
        "- swipe: {\"x1\": 100, \"y1\": 500, \"x2\": 100, \"y2\": 200, \"duration\": 300} — Swipe " +
        "- keyevent: {\"keycode\": \"KEYCODE_ENTER\"} — Send key event " +
        "- back: {} — Press back button " +
        "- home: {} — Press home button " +
        "- scroll: {\"direction\": \"up\"} — Scroll (up/down/left/right) " +
        "- wait: {\"timeout_ms\": 5000} — Wait for window change"
  )
  fun uiAutomation(
    @ToolParam(description = "The action to perform. One of: open_app, tap, tap_element, " +
        "type_text, swipe, keyevent, back, home, scroll, wait.")
    action: String,
    @ToolParam(description = "JSON string with action-specific parameters. " +
        "Required fields vary by action type. Example: {\"x\": 100, \"y\": 200}")
    parameters: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("uiAutomation")) {
        return@runBlocking mapOf("error" to "Too many calls to uiAutomation", "status" to "blocked")
      }
      withToolLogging("uiAutomation") {
        UiAutomationTools.executeUiAction(context, action, parameters)
      }
    }
  }
```

- [ ] **Step 2: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTools.kt
git commit -m "feat: add captureScreen and uiAutomation tools to AgentTools"
```

---

## Task 7: Build and verify

- [ ] **Step 1: Build the project**

```bash
cd /workspace/apk-chinese-translation
./gradlew assembleDebug --no-daemon 2>&1 | tail -20
```

Expected: Build succeeds with `BUILD SUCCESSFUL`. If there are compilation errors, fix them (likely import issues or type mismatches) before proceeding.

- [ ] **Step 2: Push all changes**

```bash
git push
```

- [ ] **Step 3: Verify the GitHub Actions build**

Check the build status via GitHub API or web UI to confirm the APK builds successfully.

---

## Self-Review Against Spec

| Spec Requirement | Task Coverage | Status |
|------------------|--------------|--------|
| UiAutomationService.kt (AccessibilityService) | Task 3 | ✅ |
| UiAutomationTools.kt (captureScreen + executeUiAction) | Task 4 | ✅ |
| accessibility_service_config.xml | Task 1 | ✅ |
| AndroidManifest.xml service declaration | Task 5 | ✅ |
| strings.xml accessibility description | Task 2 | ✅ |
| AgentTools.kt @Tool methods | Task 6 | ✅ |
| All 10 actions (open_app, tap, tap_element, type_text, swipe, keyevent, back, home, scroll, wait) | Task 4 | ✅ |
| Screenshot via screencap | Task 4 | ✅ |
| Node tree traversal (max 5 depth, 200 elements) | Task 3 | ✅ |
| Service enablement check | Task 4 | ✅ |
| Error handling (service not enabled, missing params) | Task 4 | ✅ |
| build.gradle / Gradle build | Task 7 | ✅ |
| windowChangeChannel for waitForWindowChange | Task 3 | ✅ |

**Placeholder scan:** No TBD, TODO, or placeholder patterns found.

**Type consistency:** All method signatures match between tasks. `UiAutomationTools.captureScreen()` returns `Map<String, Any>`, `executeUiAction()` returns `Map<String, String>`. `UiAutomationServiceHolder.instance` provides singleton access.

**Spec coverage complete.** No gaps found.
