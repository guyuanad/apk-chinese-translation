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
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
        val result = mutableMapOf<String, Any>(
          "status" to "success",
          "foreground_package" to screenInfo.foregroundPackage,
          "interactive_elements" to elements,
          "element_count" to elements.size,
          "hint" to "Look at the elements above. Find the search box (usually has is_editable=true or content_description containing '搜索'). Call uiAutomation('tap_element', {\"element_index\": INDEX}) to tap it, then uiAutomation('type_text', {\"text\": \"YOUR QUERY\"}) to type. If you see a search icon, tap it first.",
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

    // Escape for shell: replace spaces with %s and escape double quotes.
    val escaped = text.replace("\"", "\\\"").replace(" ", "%s")
    val result = execInputCommand("text \"$escaped\"")
    return result?.let {
      mapOf(
        "status" to "success",
        "action" to "type_text",
        "details" to "Typed: $text",
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
}
