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
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "UiAutomationTools"

object UiAutomationTools {

  /** Checks if the UiAutomationService is enabled in system settings. */
  fun isServiceEnabled(context: Context): Boolean {
    return try {
      val enabledServices =
        Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
      val serviceName =
        "${context.packageName}/com.google.ai.edge.gallery.customtasks.agentchat.UiAutomationService"
      enabledServices.contains(serviceName)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to check accessibility service: ${e.message}")
      false
    }
  }

  /**
   * Captures the current screen as a Bitmap using AccessibilityService.takeScreenshot().
   * Requires canTakeScreenshot="true" in service config (API 30+).
   * Falls back to reading the screencap file if takeScreenshot fails.
   * Returns null if screenshot cannot be captured.
   */
  suspend fun captureScreenBitmap(): Bitmap? {
    val service = UiAutomationServiceHolder.instance
    if (service == null) {
      Log.w(TAG, "captureScreenBitmap: AccessibilityService not available")
      return null
    }

    // Method 1: Use AccessibilityService.takeScreenshot() (API 24+, needs canTakeScreenshot on API 30+)
    try {
      val screenshot = suspendCancellableCoroutine<Bitmap?> { continuation ->
        val handler = Handler(Looper.getMainLooper())
        service.takeScreenshot(
          android.view.Display.DEFAULT_DISPLAY,
          { handler.post(it) },
          object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.Screenshot) {
              try {
                val hardwareBitmap = screenshot.hardwareBitmap
                // Convert hardware bitmap to a regular bitmap for compatibility
                val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                screenshot.hardwareBitmap.close()
                screenshot.close()
                Log.d(TAG, "captureScreenBitmap: Success via takeScreenshot(), size=${bitmap?.width}x${bitmap?.height}")
                continuation.resume(bitmap)
              } catch (e: Exception) {
                Log.e(TAG, "captureScreenBitmap: Error processing screenshot: ${e.message}")
                continuation.resume(null)
              }
            }

            override fun onFailure(errorCode: Int) {
              Log.w(TAG, "captureScreenBitmap: takeScreenshot() failed with errorCode=$errorCode")
              continuation.resume(null)
            }
          },
        )
      }
      if (screenshot != null) return screenshot
    } catch (e: Exception) {
      Log.w(TAG, "captureScreenBitmap: takeScreenshot() exception: ${e.message}")
    }

    // Method 2: Fallback - read screencap file (requires root)
    try {
      val exitCode = execShellCommand("screencap -p /data/local/tmp/screen_capture.png")
      if (exitCode == 0) {
        val file = java.io.File("/data/local/tmp/screen_capture.png")
        if (file.exists()) {
          val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
          if (bitmap != null) {
            Log.d(TAG, "captureScreenBitmap: Success via screencap file, size=${bitmap.width}x${bitmap.height}")
            return bitmap
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "captureScreenBitmap: screencap fallback failed: ${e.message}")
    }

    Log.w(TAG, "captureScreenBitmap: All methods failed")
    return null
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
          "message" to
            "Accessibility service is not enabled. Please enable it in Settings > Accessibility.",
        )
      }

      try {
        // 1. Try to take screenshot (optional - may fail without root).
        var screenshotPath: String? = null
        val screenshotResult = execShellCommand("screencap -p /data/local/tmp/screen_capture.png")
        if (screenshotResult == 0) {
          screenshotPath = "/data/local/tmp/screen_capture.png"
        } else {
          Log.w(TAG, "Screenshot failed with exit code $screenshotResult, continuing without screenshot")
        }

        // 2. Get screen info from accessibility service (this is the critical part).
        val screenInfo = service.getScreenInfo()
        val elements = screenInfo.interactiveElements

        // 3. Build result - screenshot is optional, elements are required.
        // Create a text summary that's easy for the model to understand
        val summary = buildString {
          append("Screen: ${screenInfo.foregroundPackage}\n")
          append("Elements:\n")
          for (el in elements) {
            val idx = el["index"]
            val text = el["text"] as? String ?: ""
            val desc = el["content_description"] as? String ?: ""
            val cls = el["class"] as? String ?: ""
            val editable = el["is_editable"] as? Boolean ?: false
            val clickable = el["is_clickable"] as? Boolean ?: false
            val label = when {
              text.isNotEmpty() -> text
              desc.isNotEmpty() -> desc
              else -> cls
            }
            val flags = buildList {
              if (editable) add("editable")
              if (clickable) add("clickable")
            }.joinToString(",")
            append("[$idx] $label ($flags)\n")
          }
        }

        // Smart hint: analyze screen state and suggest next action
        val smartHint = buildSmartHint(elements, screenInfo.foregroundPackage)

        val result = mutableMapOf<String, Any>(
          "status" to "success",
          "foreground_package" to screenInfo.foregroundPackage,
          "interactive_elements" to elements,
          "element_count" to elements.size,
          "screen_summary" to summary,
          "hint" to smartHint,
        )
        if (screenshotPath != null) {
          result["screenshot_path"] = screenshotPath
        }
        result as Map<String, Any>
      } catch (e: Exception) {
        Log.e(TAG, "captureScreen failed: ${e.message}", e)
        mapOf(
          "status" to "error",
          "message" to "Capture failed: ${e.message}",
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
    parameters: String,
  ): Map<String, String> {
    return withContext(Dispatchers.IO) {
      val service = UiAutomationServiceHolder.instance
      if (service == null) {
        return@withContext mapOf(
          "status" to "error",
          "action" to action,
          "message" to
            "Accessibility service is not enabled. Please enable it in Settings > Accessibility.",
        )
      }

      try {
        when (action) {
          "open_app" -> executeOpenApp(parameters)
          "tap" -> executeTap(parameters)
          "tap_element" -> executeTapElement(service, parameters)
          "type_text" -> executeTypeText(parameters)
          "swipe" -> executeSwipe(parameters)
          "keyevent" -> executeKeyevent(parameters)
          "back" ->
            execInputCommand("keyevent KEYCODE_BACK")?.let {
              mapOf(
                "status" to "success",
                "action" to action,
                "details" to "Back button pressed",
              )
            } ?: errorResult(action, "Failed to send back key event")
          "home" ->
            execInputCommand("keyevent KEYCODE_HOME")?.let {
              mapOf(
                "status" to "success",
                "action" to action,
                "details" to "Home button pressed",
              )
            } ?: errorResult(action, "Failed to send home key event")
          "scroll" -> executeScroll(parameters)
          "wait" -> executeWait(service, parameters)
          else ->
            errorResult(
              action,
              "Unknown action: $action. Supported: open_app, tap, tap_element, type_text, swipe, keyevent, back, home, scroll, wait",
            )
        }
      } catch (e: Exception) {
        Log.e(TAG, "executeUiAction failed: ${e.message}", e)
        errorResult(action, e.message ?: "Unknown error")
      }
    }
  }

  private fun executeOpenApp(parameters: String): Map<String, String> {
    val json =
      runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val packageName = json?.get("package_name")?.toString()?.trim('"')
    if (packageName.isNullOrEmpty()) {
      return errorResult("open_app", "Missing required parameter: package_name")
    }

    // Try monkey launch as it's the most reliable way to launch an app from shell.
    val exitCode =
      execShellCommand(
        "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
      )
    return if (exitCode == 0) {
      mapOf(
        "status" to "success",
        "action" to "open_app",
        "details" to "Launched app: $packageName",
      )
    } else {
      errorResult("open_app", "Failed to launch app: $packageName (exit code: $exitCode)")
    }
  }

  private fun executeTap(parameters: String): Map<String, String> {
    val json =
      runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val x = json?.get("x")?.toString()?.toIntOrNull()
    val y = json?.get("y")?.toString()?.toIntOrNull()
    if (x == null || y == null) {
      return errorResult("tap", "Missing required parameters: x, y")
    }
    val result = execInputCommand("tap $x $y")
    return result?.let {
      mapOf(
        "status" to "success",
        "action" to "tap",
        "details" to "Tapped at coordinates ($x, $y)",
      )
    } ?: errorResult("tap", "Failed to execute tap")
  }

  private fun executeTapElement(
    service: UiAutomationService,
    parameters: String,
  ): Map<String, String> {
    val json =
      runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val index = json?.get("element_index")?.toString()?.toIntOrNull()
    if (index == null) {
      return errorResult("tap_element", "Missing required parameter: element_index")
    }

    val element = service.getInteractiveElement(index)
    if (element == null) {
      return errorResult(
        "tap_element",
        "Element at index $index not found. Try captureScreen first.",
      )
    }

    // Try accessibility ACTION_CLICK first (more reliable than coordinate tap)
    try {
      val clicked = element.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      if (clicked) {
        element.recycle()
        return mapOf(
          "status" to "success",
          "action" to "tap_element",
          "details" to "Clicked element $index via accessibility",
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Accessibility click failed for element $index: ${e.message}")
    }

    // Fallback to coordinate-based tap
    val rect = android.graphics.Rect()
    element.getBoundsInScreen(rect)
    element.recycle()

    val x = (rect.left + rect.right) / 2
    val y = (rect.top + rect.bottom) / 2
    val result = execInputCommand("tap $x $y")
    return result?.let {
      mapOf(
        "status" to "success",
        "action" to "tap_element",
        "details" to "Tapped element $index at ($x, $y)",
      )
    } ?: errorResult("tap_element", "Failed to tap element")
  }

  private fun executeTypeText(parameters: String): Map<String, String> {
    val json =
      runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val text = json?.get("text")?.toString()?.trim('"')
    if (text.isNullOrEmpty()) {
      return errorResult("type_text", "Missing required parameter: text")
    }

    // Try accessibility service ACTION_SET_TEXT first (supports Chinese and all Unicode)
    val service = UiAutomationServiceHolder.instance
    if (service != null) {
      try {
        val rootNode = service.rootInActiveWindow
        if (rootNode != null) {
          // Find the currently focused input field
          val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
          if (focusedNode != null) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val setResult = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
            if (setResult) {
              Log.d(TAG, "Typed via accessibility ACTION_SET_TEXT: $text")
              return mapOf(
                "status" to "success",
                "action" to "type_text",
                "details" to "Typed via accessibility: $text",
              )
            } else {
              Log.w(TAG, "ACTION_SET_TEXT returned false, trying fallback")
            }
          } else {
            Log.w(TAG, "No focused input field found, trying fallback")
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Accessibility type failed: ${e.message}, trying fallback")
      }
    }

    // Fallback: use clipboard + paste for non-ASCII text
    val hasNonAscii = text.any { it.code > 127 }
    if (hasNonAscii) {
      // For Chinese/Unicode text, use clipboard approach
      try {
        // Set clipboard via am broadcast
        val escapedForClip = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
        val clipResult = execShellCommand("am broadcast -a com.android.clipboard.copy --es text \"$escapedForClip\"")
        // Alternative: use service to set clipboard
        if (service != null) {
          val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
          if (clipboard != null) {
            val clip = android.content.ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "Set clipboard via ClipboardManager: $text")
          }
        }
        // Long press to paste, or use Ctrl+V
        Thread.sleep(300)
        execInputCommand("keyevent 279") // KEYCODE_PASTE
        Log.d(TAG, "Pasted from clipboard: $text")
        return mapOf(
          "status" to "success",
          "action" to "type_text",
          "details" to "Typed via clipboard paste: $text",
        )
      } catch (e: Exception) {
        Log.w(TAG, "Clipboard paste failed: ${e.message}")
      }
    }

    // Final fallback: input text command (ASCII only)
    val escaped = text.replace("\"", "\\\"").replace(" ", "%s")
    val result = execInputCommand("text \"$escaped\"")
    return result?.let {
      mapOf(
        "status" to "success",
        "action" to "type_text",
        "details" to "Typed via shell: $text",
      )
    } ?: errorResult("type_text", "Failed to type text")
  }

  private fun executeSwipe(parameters: String): Map<String, String> {
    val json =
      runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
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
      mapOf(
        "status" to "success",
        "action" to "swipe",
        "details" to "Swiped from ($x1,$y1) to ($x2,$y2)",
      )
    } ?: errorResult("swipe", "Failed to swipe")
  }

  private fun executeKeyevent(parameters: String): Map<String, String> {
    val json =
      runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val keycode = json?.get("keycode")?.toString()?.trim('"')
    if (keycode.isNullOrEmpty()) {
      return errorResult("keyevent", "Missing required parameter: keycode")
    }
    val result = execInputCommand("keyevent $keycode")
    return result?.let {
      mapOf(
        "status" to "success",
        "action" to "keyevent",
        "details" to "Sent key event: $keycode",
      )
    } ?: errorResult("keyevent", "Failed to send key event")
  }

  private fun executeScroll(parameters: String): Map<String, String> {
    val json =
      runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val direction = json?.get("direction")?.toString()?.trim('"')
    if (direction.isNullOrEmpty()) {
      return errorResult("scroll", "Missing required parameter: direction")
    }

    val command =
      when (direction) {
        "up" -> "swipe 500 1500 500 500 300"
        "down" -> "swipe 500 500 500 1500 300"
        "left" -> "swipe 900 1000 100 1000 300"
        "right" -> "swipe 100 1000 900 1000 300"
        else ->
          return errorResult(
            "scroll",
            "Invalid direction: $direction. Use up/down/left/right.",
          )
      }

    val result = execInputCommand(command)
    return result?.let {
      mapOf(
        "status" to "success",
        "action" to "scroll",
        "details" to "Scrolled $direction",
      )
    } ?: errorResult("scroll", "Failed to scroll")
  }

  private suspend fun executeWait(
    service: UiAutomationService,
    parameters: String,
  ): Map<String, String> {
    val json =
      runCatching { Json.parseToJsonElement(parameters).jsonObject }.getOrNull()
    val timeoutMs = json?.get("timeout_ms")?.toString()?.toLongOrNull() ?: 5000L

    val changed = service.waitForWindowChange(timeoutMs)
    return if (changed) {
      mapOf(
        "status" to "success",
        "action" to "wait",
        "details" to "Window change detected within ${timeoutMs}ms",
      )
    } else {
      mapOf(
        "status" to "success",
        "action" to "wait",
        "details" to "Timed out after ${timeoutMs}ms (no window change)",
      )
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
      val errorOutput =
        java.io.BufferedReader(java.io.InputStreamReader(process.errorStream)).readText()
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

  /**
   * Types text into the currently focused input field using Accessibility ACTION_SET_TEXT.
   * This works for Chinese and all Unicode characters.
   * Returns true if text was successfully set, false otherwise.
   */
  fun typeTextViaAccessibility(text: String): Boolean {
    val service = UiAutomationServiceHolder.instance ?: return false
    try {
      val rootNode = service.rootInActiveWindow ?: return false

      // Method 1: Find the currently focused input node
      val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      if (focusedNode != null) {
        Log.d(TAG, "Found focused node: class=${focusedNode.className}, editable=${focusedNode.isEditable}, text=${focusedNode.text}")
        // Try ACTION_SET_TEXT on the focused node
        if (focusedNode.isEditable || focusedNode.className?.toString()?.contains("EditText") == true || focusedNode.isFocusable) {
          val args = Bundle()
          args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
          val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
          Log.d(TAG, "ACTION_SET_TEXT on focused node: result=$result")
          focusedNode.recycle()
          if (result) return true
        }
        focusedNode.recycle()
      }

      // Method 2: Search for any editable or EditText-like node
      val candidateNode = findInputNode(rootNode)
      if (candidateNode != null) {
        Log.d(TAG, "Found input node: class=${candidateNode.className}, editable=${candidateNode.isEditable}")
        // Focus it first
        candidateNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Thread.sleep(200)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = candidateNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "ACTION_SET_TEXT on found node: result=$result")
        candidateNode.recycle()
        if (result) return true
      }

    } catch (e: Exception) {
      Log.e(TAG, "typeTextViaAccessibility failed: ${e.message}", e)
    }
    return false
  }

  /**
   * Recursively searches for an input/EditText node in the accessibility tree.
   */
  private fun findInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    // Check if this node is an input field
    val className = node.className?.toString() ?: ""
    if (node.isEditable || className.contains("EditText") || className.contains("AutoComplete") || className.contains("SearchView")) {
      val rect = Rect()
      node.getBoundsInScreen(rect)
      if (rect.width() > 0 && rect.height() > 0) {
        return AccessibilityNodeInfo.obtain(node)
      }
    }

    // Recurse into children
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val result = findInputNode(child)
      child.recycle()
      if (result != null) return result
    }
    return null
  }

  /**
   * Searches the entire accessibility tree for a search submit button and clicks it.
   * This bypasses the element list and finds nodes that might not be reported
   * as clickable/editable/focusable but are still tappable.
   *
   * Looks for nodes with text or contentDescription matching "搜索", "搜索一下", "Search", etc.
   * that are NOT the input field (i.e., don't contain the query text as editable).
   *
   * Returns true if a button was found and clicked, false otherwise.
   */
  fun findAndClickSubmitButton(queryText: String): Boolean {
    val service = UiAutomationServiceHolder.instance ?: return false
    try {
      val rootNode = service.rootInActiveWindow ?: return false

      // Debug: dump all nodes with text to find what's available
      val dumpResult = StringBuilder()
      dumpResult.append("=== DUMP: Looking for submit button, query='$queryText' ===\n")
      dumpNodesWithText(rootNode, 0, dumpResult)
      dumpResult.append("=== END DUMP ===")
      Log.d(TAG, dumpResult.toString())

      // Strategy 1: Find "搜索" text node and tap at its coordinates
      val submitCoords = findSubmitButtonCoordinates(rootNode, queryText)
      if (submitCoords != null) {
        val (x, y) = submitCoords
        Log.d(TAG, "Strategy 1: Found submit button coordinates: ($x, $y), tapping there")
        val tapResult = execShellCommand("input tap $x $y")
        if (tapResult == 0) return true
      }

      // Strategy 2: Use screen dimensions to calculate submit button position
      // This is the most reliable approach for Douyin and similar apps
      // where the submit button is not in the accessibility tree
      val screenCoords = calculateSubmitButtonFromScreen(service)
      if (screenCoords != null) {
        val (x, y) = screenCoords
        Log.d(TAG, "Strategy 2: Calculated submit position from screen: ($x, $y), tapping there")
        val tapResult = execShellCommand("input tap $x $y")
        if (tapResult == 0) return true
      }

      // Strategy 3: Find input field bounds from element list and calculate
      val inputCoords = findInputFieldAndCalculateSubmit(rootNode, queryText)
      if (inputCoords != null) {
        val (x, y) = inputCoords
        Log.d(TAG, "Strategy 3: Calculated from input field: ($x, $y), tapping there")
        val tapResult = execShellCommand("input tap $x $y")
        if (tapResult == 0) return true
      }

      return false
    } catch (e: Exception) {
      Log.e(TAG, "findAndClickSubmitButton failed: ${e.message}", e)
    }
    return false
  }

  /**
   * Calculate the submit button position from screen dimensions.
   * In Douyin and most Chinese apps, the search page has a fixed layout:
   * - Status bar at top (~70px)
   * - Search bar below status bar (~100px height)
   * - Input field on the left ~75% of the bar
   * - "搜索" submit button on the right ~25% of the bar
   *
   * The submit button center is approximately at:
   *   x = screenWidth * 0.88
   *   y = 70 + 50 = ~120 (status bar + half of search bar)
   */
  private fun calculateSubmitButtonFromScreen(service: UiAutomationService): Pair<Int, Int>? {
    try {
      val displayMetrics = service.resources?.displayMetrics
      if (displayMetrics != null) {
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        // Status bar height is typically 24dp-25dp = ~70-75px at 3x density
        val statusBarHeight = Math.round(25 * density)

        // Search bar is typically 44-48dp tall
        val searchBarHeight = Math.round(48 * density)

        // Submit button is at the right side of the search bar
        // In Douyin: the "搜索" button takes up the right ~20% of the search bar
        val buttonX = screenWidth - Math.round(60 * density)  // ~60dp from right edge
        val buttonY = statusBarHeight + searchBarHeight / 2   // Center of search bar

        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight}, density=$density, statusBar=$statusBarHeight, searchBar=$searchBarHeight")
        Log.d(TAG, "Calculated submit button: ($buttonX, $buttonY)")
        return Pair(buttonX, buttonY)
      }
    } catch (e: Exception) {
      Log.e(TAG, "calculateSubmitButtonFromScreen failed: ${e.message}", e)
    }
    return null
  }

  /**
   * Find a "搜索" text node in the accessibility tree and return its center coordinates.
   * Only considers nodes that are in the same row as the input field (not suggestions below).
   * Returns null if not found.
   */
  private fun findSubmitButtonCoordinates(rootNode: AccessibilityNodeInfo, queryText: String): Pair<Int, Int>? {
    val submitKeywords = listOf("搜索", "搜素", "搜索一下", "search", "Search", "确定", "完成")

    // First find the input field to know which row to look in
    val inputNode = findNodeByText(rootNode, queryText, true)
    var inputTop = 0
    var inputBottom = Int.MAX_VALUE
    if (inputNode != null) {
      val inputRect = Rect()
      inputNode.getBoundsInScreen(inputRect)
      inputTop = inputRect.top
      inputBottom = inputRect.bottom
      inputNode.recycle()
      Log.d(TAG, "Input field row: top=$inputTop, bottom=$inputBottom")
    }

    // Search for submit text node
    return findSubmitTextNodeCoords(rootNode, submitKeywords, queryText, inputTop, inputBottom)
  }

  private fun findSubmitTextNodeCoords(
    node: AccessibilityNodeInfo,
    keywords: List<String>,
    queryText: String,
    inputTop: Int,
    inputBottom: Int,
  ): Pair<Int, Int>? {
    val text = node.text?.toString() ?: ""
    val desc = node.contentDescription?.toString() ?: ""
    val matchesKeyword = keywords.any { text == it || desc == it }
    val isNotInputField = !(node.isEditable && text.contains(queryText))

    if (matchesKeyword && isNotInputField) {
      val rect = Rect()
      node.getBoundsInScreen(rect)
      val centerY = (rect.top + rect.bottom) / 2

      // Must be in the same row as the input field (if we found one)
      if (inputTop > 0 && (centerY < inputTop - 20 || centerY > inputBottom + 20)) {
        Log.d(TAG, "SKIP submit node (different row): text='$text', centerY=$centerY, row=[$inputTop,$inputBottom]")
      } else {
        val x = (rect.left + rect.right) / 2
        val y = centerY
        Log.d(TAG, "Found submit node coords: text='$text', desc='$desc', pos=($x,$y), rect=(${rect.left},${rect.top},${rect.right},${rect.bottom})")
        return Pair(x, y)
      }
    }

    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val result = findSubmitTextNodeCoords(child, keywords, queryText, inputTop, inputBottom)
      child.recycle()
      if (result != null) return result
    }
    return null
  }

  /**
   * Find the input field and calculate where the submit button should be.
   * In Douyin, the "搜索" button is right after the input field in the action bar.
   * We tap at (inputRight + offset, inputCenterY).
   */
  private fun findInputFieldAndCalculateSubmit(rootNode: AccessibilityNodeInfo, queryText: String): Pair<Int, Int>? {
    val inputNode = findNodeByText(rootNode, queryText, true) ?: return null
    val inputRect = Rect()
    inputNode.getBoundsInScreen(inputRect)
    val inputRight = inputRect.right
    val inputCenterY = (inputRect.top + inputRect.bottom) / 2
    val inputWidth = inputRect.right - inputRect.left
    inputNode.recycle()

    // The submit button is typically 40-80px to the right of the input field
    // and at the same vertical position
    val buttonX = inputRight + 50
    val buttonY = inputCenterY
    Log.d(TAG, "Input field: right=$inputRight, width=$inputWidth, centerY=$inputCenterY")
    Log.d(TAG, "Calculated submit position: ($buttonX, $buttonY)")
    return Pair(buttonX, buttonY)
  }

  /** Debug: dump all nodes that have text or contentDescription */
  private fun dumpNodesWithText(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
    val text = node.text?.toString() ?: ""
    val desc = node.contentDescription?.toString() ?: ""
    if (text.isNotEmpty() || desc.isNotEmpty()) {
      val indent = "  ".repeat(depth)
      sb.append("$indent Node: text='$text', desc='$desc', clickable=${node.isClickable}, editable=${node.isEditable}, class=${node.className}\n")
    }
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      dumpNodesWithText(child, depth + 1, sb)
      child.recycle()
    }
  }

  /**
   * Tap the submit button using screen position calculation.
   * Uses the Accessibility Service's context to get display metrics.
   * Falls back to using the context if service.resources is null.
   */
  fun tapSubmitButtonByScreenPosition(): Boolean {
    try {
      val service = UiAutomationServiceHolder.instance
      if (service == null) {
        Log.e(TAG, "tapSubmitButtonByScreenPosition: UiAutomationService not available")
        return false
      }

      val displayMetrics = service.resources?.displayMetrics
        ?: return false

      val screenWidth = displayMetrics.widthPixels
      val density = displayMetrics.density

      // Status bar height is typically 24-25dp
      val statusBarHeight = Math.round(25 * density)
      // Search bar is typically 44-48dp tall
      val searchBarHeight = Math.round(48 * density)

      // Submit button is at the right side of the search bar
      val buttonX = screenWidth - Math.round(60 * density)  // ~60dp from right edge
      val buttonY = statusBarHeight + searchBarHeight / 2   // Center of search bar

      Log.d(TAG, "tapSubmitButtonByScreenPosition: screen=${screenWidth}x${displayMetrics.heightPixels}, density=$density, tapping ($buttonX, $buttonY)")
      val tapResult = execShellCommand("input tap $buttonX $buttonY")
      Log.d(TAG, "tapSubmitButtonByScreenPosition: tap result=$tapResult")
      return tapResult == 0
    } catch (e: Exception) {
      Log.e(TAG, "tapSubmitButtonByScreenPosition failed: ${e.message}", e)
    }
    return false
  }

  /** Find a node by its text content */
  private fun findNodeByText(node: AccessibilityNodeInfo, targetText: String, mustBeEditable: Boolean): AccessibilityNodeInfo? {
    val text = node.text?.toString() ?: ""
    if (text.contains(targetText) && (!mustBeEditable || node.isEditable)) {
      return AccessibilityNodeInfo.obtain(node)
    }
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val result = findNodeByText(child, targetText, mustBeEditable)
      child.recycle()
      if (result != null) return result
    }
    return null
  }

  /**
   * Finds the index of a search-related element in the list of interactive elements.
   * Returns null if no search element is found.
   * Priority: editable search field > clickable search button > any editable field.
   */
  fun findSearchElementIndex(elements: List<Map<String, Any>>): Int? {
    val searchKeywords = listOf("搜索", "search", "Search", "搜一搜")
    val excludeKeywords = listOf("商城", "购物", "订单", "消息", "我", "首页", "朋友")

    // Helper: get element Y position from bounds (top coordinate)
    fun getY(el: Map<String, Any>): Int {
      val bounds = el["bounds"] as? String ?: ""
      val match = Regex("\\[(\\d+),(\\d+)").find(bounds)
      return match?.groupValues?.get(2)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    // Priority 1: Find editable search field (top half of screen preferred)
    val editableMatches = elements.filter { el ->
      val text = el["text"] as? String ?: ""
      val desc = el["content_description"] as? String ?: ""
      val editable = el["is_editable"] as? Boolean ?: false
      editable && (searchKeywords.any { text.contains(it) || desc.contains(it) })
    }
    if (editableMatches.isNotEmpty()) {
      // Prefer topmost element
      return editableMatches.minByOrNull { getY(it) }?.get("index") as? Int
    }

    // Priority 2: Find clickable search button/icon (top half preferred, exclude bottom nav)
    val clickableMatches = elements.filter { el ->
      val text = el["text"] as? String ?: ""
      val desc = el["content_description"] as? String ?: ""
      val clickable = el["is_clickable"] as? Boolean ?: false
      val idx = el["index"] as? Int ?: -1
      clickable &&
        (searchKeywords.any { text.contains(it) || desc.contains(it) }) &&
        !excludeKeywords.any { text.contains(it) || desc.contains(it) }
    }
    if (clickableMatches.isNotEmpty()) {
      // Sort by Y position, prefer topmost (smallest Y = closest to top)
      return clickableMatches.minByOrNull { getY(it) }?.get("index") as? Int
    }

    // Priority 3: Find any editable field (top half preferred)
    val anyEditable = elements.filter { el ->
      (el["is_editable"] as? Boolean ?: false)
    }
    if (anyEditable.isNotEmpty()) {
      return anyEditable.minByOrNull { getY(it) }?.get("index") as? Int
    }

    return null
  }

  /**
   * Scans elements for search-related items and builds a specific hint
   * telling the model exactly which element_index to tap.
   */
  /**
   * Smart hint generator - analyzes screen state and suggests the next action.
   * This is the core of autonomous operation: the code does the thinking,
   * the model just follows instructions.
   */
  private fun buildSmartHint(elements: List<Map<String, Any>>, foregroundPackage: String): String {
    val searchKeywords = listOf("搜索", "search", "Search", "查搜索", "搜一搜")
    val submitKeywords = listOf("搜索", "搜素", "搜索一下", "search", "Search", "确定", "完成", "发送")
    val backKeywords = listOf("返回", "back", "Back")

    // State 1: No app open (home screen or launcher)
    if (foregroundPackage.isEmpty() || foregroundPackage.contains("launcher") || 
        foregroundPackage.contains("nexuslauncher") || foregroundPackage.contains("googlequicksearchbox")) {
      return "TASK NOT DONE. You are on the home screen. Call runIntent('open_app', {\"package_name\": \"APP_NAME\"}) to open an app."
    }

    // State 2: Search input page - there's an editable field
    val editableElements = elements.filter { (it["is_editable"] as? Boolean ?: false) }
    if (editableElements.isNotEmpty()) {
      val inputEl = editableElements.first()
      val inputIdx = inputEl["index"] as? Int ?: -1
      val inputText = inputEl["text"] as? String ?: ""

      // Sub-state 2a: Input field has text - suggest submitting
      if (inputText.isNotEmpty()) {
        // Look for submit button in element list
        for (el in elements) {
          val text = el["text"] as? String ?: ""
          val desc = el["content_description"] as? String ?: ""
          val clickable = el["is_clickable"] as? Boolean ?: false
          val editable = el["is_editable"] as? Boolean ?: false
          val idx = el["index"] as? Int ?: continue
          if (!editable && clickable && submitKeywords.any { text == it || desc == it }) {
            return "TASK NOT DONE. Text '$inputText' is in the input field. Found submit button '$text' at index $idx. Call uiAutomation('tap_element', {\"element_index\": $idx}) NOW to submit."
          }
        }
        // No submit button found in list - try Enter
        return "TASK NOT DONE. Text '$inputText' is in the input field. Call uiAutomation('keyevent', {\"keycode\": \"KEYCODE_ENTER\"}) NOW to submit the search."
      }

      // Sub-state 2b: Input field is empty - suggest typing
      return "TASK NOT DONE. Found input field at index $inputIdx. Call uiAutomation('tap_element', {\"element_index\": $inputIdx}) to focus it, then uiAutomation('type_text', {\"text\": \"YOUR_QUERY\"}) to type."
    }

    // State 3: App page with search button - suggest tapping it
    for (el in elements) {
      val text = el["text"] as? String ?: ""
      val desc = el["content_description"] as? String ?: ""
      val clickable = el["is_clickable"] as? Boolean ?: false
      val idx = el["index"] as? Int ?: continue
      if (clickable && (searchKeywords.any { text.contains(it) || desc.contains(it) })) {
        return "TASK NOT DONE. Found search button at index $idx. Call uiAutomation('tap_element', {\"element_index\": $idx}) NOW to tap it, then call captureScreen() to see the search page."
      }
    }

    // State 4: Results page - check if we see content that looks like results
    val hasContentElements = elements.count { el ->
      val text = el["text"] as? String ?: ""
      val desc = el["content_description"] as? String ?: ""
      text.isNotEmpty() && !backKeywords.any { text == it }
    }
    if (hasContentElements > 5) {
      // Likely a content/results page
      val contentSummary = elements.take(5).mapNotNull { el ->
        val text = el["text"] as? String ?: ""
        val desc = el["content_description"] as? String ?: ""
        val idx = el["index"] as? Int ?: return@mapNotNull null
        val label = if (text.isNotEmpty()) text else desc
        if (label.isNotEmpty()) "[$idx] $label" else null
      }.joinToString(", ")
      return "TASK MAY BE COMPLETE. You appear to be on a content page. Visible items: $contentSummary. If the task is complete, reply to the user. To interact more, call uiAutomation('tap_element', {\"element_index\": INDEX}) or uiAutomation('scroll', {\"direction\": \"down\"})."
    }

    // State 5: Generic - list clickable elements for the model
    val clickableElements = elements.filter { (it["is_clickable"] as? Boolean ?: false) }
    if (clickableElements.isNotEmpty()) {
      val topClickable = clickableElements.take(5).mapNotNull { el ->
        val text = el["text"] as? String ?: ""
        val desc = el["content_description"] as? String ?: ""
        val idx = el["index"] as? Int ?: return@mapNotNull null
        val label = if (text.isNotEmpty()) text else if (desc.isNotEmpty()) desc else el["class"] as? String ?: ""
        if (label.isNotEmpty()) "[$idx] $label" else null
      }.joinToString(", ")
      return "TASK NOT DONE. Clickable elements: $topClickable. Call uiAutomation('tap_element', {\"element_index\": INDEX}) to tap one, or uiAutomation('scroll', {\"direction\": \"down\"}) to scroll."
    }

    // Fallback
    return "TASK NOT DONE. Call uiAutomation('tap_element', {\"element_index\": INDEX}) to tap an element, or uiAutomation('scroll', {\"direction\": \"down\"}) to scroll."
  }

  private fun buildSearchHint(elements: List<Map<String, Any>>): String {
    val searchKeywords = listOf("搜索", "search", "Search", "查搜索", "搜一搜")

    // Priority 1: Find editable search field
    for (el in elements) {
      val text = el["text"] as? String ?: ""
      val desc = el["content_description"] as? String ?: ""
      val editable = el["is_editable"] as? Boolean ?: false
      val idx = el["index"] as? Int ?: continue
      if (editable && (searchKeywords.any { text.contains(it) || desc.contains(it) })) {
        return "Found search input at index $idx. Call uiAutomation('tap_element', {\"element_index\": $idx}) to tap it, then uiAutomation('type_text', {\"text\": \"YOUR QUERY\"}) to type your search."
      }
    }

    // Priority 2: Find clickable search button/icon
    for (el in elements) {
      val text = el["text"] as? String ?: ""
      val desc = el["content_description"] as? String ?: ""
      val clickable = el["is_clickable"] as? Boolean ?: false
      val idx = el["index"] as? Int ?: continue
      if (clickable && (searchKeywords.any { text.contains(it) || desc.contains(it) })) {
        return "Found search button at index $idx. Call uiAutomation('tap_element', {\"element_index\": $idx}) to tap it, then call captureScreen() again to see the search page."
      }
    }

    // Priority 3: Find any editable field (might be a search box without label)
    for (el in elements) {
      val editable = el["is_editable"] as? Boolean ?: false
      val idx = el["index"] as? Int ?: continue
      if (editable) {
        return "Found input field at index $idx. Call uiAutomation('tap_element', {\"element_index\": $idx}) to tap it, then uiAutomation('type_text', {\"text\": \"YOUR QUERY\"}) to type."
      }
    }

    // Fallback: generic hint
    return "Look at the screen_summary above. Find the element you need and call uiAutomation('tap_element', {\"element_index\": INDEX}) to tap it."
  }
}
