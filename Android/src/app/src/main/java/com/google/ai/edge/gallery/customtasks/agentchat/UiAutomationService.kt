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
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

/** Data class holding screen information. */
data class ScreenInfo(
  val foregroundPackage: String,
  val rootNode: AccessibilityNodeInfo?,
  val interactiveElements: List<Map<String, Any>>,
)

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
    // Try to get foreground package from rootInActiveWindow first (more reliable)
    val pkgFromRoot = try {
      rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) { null }
    val pkg = pkgFromRoot ?: foregroundPackage ?: ""
    return ScreenInfo(
      foregroundPackage = pkg,
      rootNode = rootNode,
      interactiveElements = getInteractiveElements(),
    )
  }

  /**
   * Returns the element at [index] from the current interactive elements list,
   * or null if index is out of bounds.
   */
  fun getInteractiveElement(index: Int): AccessibilityNodeInfo? {
    // Re-traverse to get the node at the given index
    val root = rootNode ?: return null
    val nodes = mutableListOf<AccessibilityNodeInfo>()
    collectInteractiveNodes(root, nodes, 5, 0, 200)
    if (index < 0 || index >= nodes.size) return null
    return nodes[index]
  }

  private fun collectInteractiveNodes(
    node: AccessibilityNodeInfo,
    nodes: MutableList<AccessibilityNodeInfo>,
    maxDepth: Int,
    currentDepth: Int,
    maxNodes: Int,
  ) {
    if (nodes.size >= maxNodes) return
    if (currentDepth > maxDepth) return

    val isClickable = node.isClickable
    val isEditable = node.isEditable
    val isFocusable = node.isFocusable

    if (isClickable || isEditable || isFocusable) {
      val rect = Rect()
      node.getBoundsInScreen(rect)
      if (rect.width() > 0 && rect.height() > 0) {
        nodes.add(node)
      }
    }

    for (i in 0 until node.childCount) {
      val child = node.getChild(i)
      if (child != null) {
        collectInteractiveNodes(child, nodes, maxDepth, currentDepth + 1, maxNodes)
        // Don't recycle children here as they're stored in the list
      }
    }
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
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        // Simplify class name: only keep the last part after last dot
        val simpleClass = className.substringAfterLast(".")
        elements.add(
          mapOf(
            "index" to elements.size,
            "text" to text,
            "content_description" to contentDesc,
            "class" to simpleClass,
            "is_editable" to isEditable,
            "is_clickable" to isClickable,
          ),
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
