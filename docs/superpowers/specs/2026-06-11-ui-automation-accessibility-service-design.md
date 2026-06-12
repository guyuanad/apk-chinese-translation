# UI Automation & Accessibility Service Integration — Technical Design Document

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [New Tool Specifications](#3-new-tool-specifications)
4. [Operation Flow Example](#4-operation-flow-example)
5. [Permissions & Configuration](#5-permissions--configuration)
6. [Security Considerations](#6-security-considerations)
7. [File Manifest](#7-file-manifest)
8. [Future Considerations](#8-future-considerations)

---

## 1. Overview

### 1.1 Project Background

The Edge Gallery Android application includes an **Agent Chat** feature powered by on-device large language models (LLMs) via LiteRT-LM. The agent can already execute tasks using skills (JS-based), MCP tools, and Android intents. This design extends the agent's capabilities by integrating **Android Accessibility Service** and **UI Automation**, enabling the AI to:

- **Read** what is currently displayed on the screen (foreground app, UI node tree, available controls).
- **Act** on the screen by tapping elements, typing text, swiping, sending key events, and launching apps.

This transforms the agent from a passive assistant into an active one that can perform multi-step UI tasks across any third-party app on the device — for example, "Send a message to Zhang San on WeChat saying hello" or "Check today's weather on the weather app."

### 1.2 Design Goals

| Goal | Description |
|------|-------------|
| **Screen Awareness** | The agent must be able to capture and understand the current screen content |
| **UI Interaction** | The agent must be able to perform common UI actions (tap, type, swipe, navigate) |
| **Safety** | Sensitive operations require explicit user confirmation before execution |
| **Zero Network Dependency** | All automation runs locally using Accessibility Service and shell commands |
| **Compatibility** | Works on Android 12+ (API 31+), consistent with the app's `minSdkVersion` |

---

## 2. Architecture

The implementation follows a **three-layer architecture** that cleanly separates concerns:

```
┌─────────────────────────────────────────────────────────┐
│                    Agent Layer                          │
│  AgentTools.kt                                          │
│  ┌─────────────────┐  ┌──────────────────────────────┐  │
│  │ captureScreen()  │  │ uiAutomation()               │  │
│  │  (read screen)   │  │  (execute UI actions)        │  │
│  └────────┬─────────┘  └──────────────┬───────────────┘  │
│           │                           │                   │
│           ▼                           ▼                   │
│  ┌──────────────────────────────────────────────────┐    │
│  │           UiAutomationTools.kt                    │    │
│  │  • Coordinates between Agent layer and            │    │
│  │    Accessibility Service / shell execution        │    │
│  └────────┬──────────────────────────────┬───────────┘    │
└───────────┼──────────────────────────────┼────────────────┘
            │                              │
┌───────────▼──────────────────────────────┼────────────────┐
│         Accessibility Service Layer      │                │
│  UiAutomationService.kt                  │                │
│  ┌─────────────────────────────────────┐ │                │
│  │ • getRootInActiveWindow()           │ │                │
│  │ • Traverse accessibility node tree  │ │                │
│  │ • getCurrentPackageName()           │ │                │
│  │ • onAccessibilityEvent() — window   │ │                │
│  │   change listener                    │ │                │
│  └─────────────────────────────────────┘ │                │
└──────────────────────────────────────────┼────────────────┘
                                           │
┌──────────────────────────────────────────▼────────────────┐
│                    Execution Layer                         │
│  Runtime.exec("input ...") shell commands                  │
│  ┌────────────────────────────────────────────────────┐   │
│  │ input tap x y          — tap at coordinates        │   │
│  │ input text "hello"     — type text                 │   │
│  │ input keyevent KEYCODE — send key event             │   │
│  │ input swipe x1 y1 x2 y2 duration — swipe gesture   │   │
│  │ input keyevent HOME    — home button               │   │
│  │ input keyevent BACK    — back button               │   │
│  └────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

### 2.1 Layer Responsibilities

#### Agent Layer (`AgentTools.kt` — modified)

- Exposes `captureScreen` and `uiAutomation` as `@Tool`-annotated methods discoverable by the LLM.
- Orchestrates tool calls based on AI-generated instructions.
- Integrates with the existing tool infrastructure (call counting, logging, stats).
- Triggers `AskInfoAgentAction` for sensitive operations requiring user confirmation.

#### Accessibility Service Layer (`UiAutomationService.kt` — new)

- Singleton `AccessibilityService` that maintains a reference to the current root accessibility node.
- Provides methods to:
  - **`getRootNode()`**: Returns the current screen's root `AccessibilityNodeInfo`.
  - **`getForegroundPackageName()`**: Returns the package name of the currently active app.
  - **`getScreenInfo()`**: Returns a structured summary of the current screen (package name, activity title, list of interactive elements with their properties).
  - **`waitForWindowChange(timeoutMs)`**: Suspends until a new window event is received (useful for waiting for app launches or page transitions).
- Listens for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED` events to track screen transitions.

#### Execution Layer (via `Runtime.exec`)

- Executes Android `input` shell commands to simulate user interactions.
- No additional permissions required beyond what Accessibility Service already needs.
- Commands are non-invasive and work across all apps.

### 2.2 Data Flow

```
User Prompt → LLM decides to use tool
                  │
                  ▼
        AgentTools.uiAutomation(action="open_app", package_name="com.tencent.mm")
                  │
                  ├──► UiAutomationService.waitForWindowChange(5000)
                  │
                  └──► Runtime.exec("input keyevent KEYCODE_HOME")
                           │
                  ┌────────▼─────────┐
                  │ UiAutomationService  │
                  │ receives window     │
                  │ change event        │
                  └────────┬─────────┘
                           │
                  ▼
        captureScreen() → returns JSON with screen state
                  │
                  ▼
        LLM analyzes screen → decides next action (e.g., tap search box)
                  │
                  ▼
        uiAutomation(action="tap", x=500, y=300)
                  │
                  ▼
        Runtime.exec("input tap 500 300")
```

---

## 3. New Tool Specifications

### 3.1 `captureScreen`

**Purpose:** Capture the current screen state, including a screenshot reference and a structured list of interactive UI elements.

**Tool Signature:**

```kotlin
@Tool(description = "Capture the current screen. Returns a screenshot file path, " +
    "the foreground app package name, activity title, and a list of interactive " +
    "UI elements (buttons, text fields, lists, etc.) with their bounds, text, " +
    "content description, and class name. Use this to understand what is on screen " +
    "before performing actions.")
suspend fun captureScreen(): Map<String, Any>
```

**Return Format (JSON):**

```json
{
  "status": "success",
  "foreground_package": "com.tencent.mm",
  "activity_title": "WeChat",
  "screenshot_path": "/data/local/tmp/screen_capture.png",
  "interactive_elements": [
    {
      "index": 0,
      "text": "Search",
      "content_description": "",
      "class_name": "android.widget.TextView",
      "bounds": {"left": 50, "top": 120, "right": 650, "bottom": 200},
      "center": {"x": 350, "y": 160},
      "is_clickable": true,
      "is_editable": false
    },
    {
      "index": 1,
      "text": "",
      "content_description": "Contacts",
      "class_name": "android.widget.ImageView",
      "bounds": {"left": 50, "top": 200, "right": 150, "bottom": 300},
      "center": {"x": 100, "y": 250},
      "is_clickable": true,
      "is_editable": false
    }
  ],
  "element_count": 2
}
```

**Implementation Details:**

1. **Screenshot**: Uses `screencap` shell command to capture the screen to a temp file:
   ```kotlin
   Runtime.getRuntime().exec("screencap -p /data/local/tmp/screen_capture.png")
   ```

2. **Screen Info**: Obtained from `UiAutomationService.getScreenInfo()`:
   - Recursively traverses the accessibility node tree from `getRootInActiveWindow()`.
   - Collects all nodes where `isClickable == true`, `isEditable == true`, or `isFocusable == true`.
   - Computes center coordinates from `Rect` bounds.
   - Limits depth to prevent performance issues (max 5 levels deep, max 200 elements).

3. **Error Handling**: Returns `{"status": "error", "message": "..."}` if the Accessibility Service is not running or no root node is available.

### 3.2 `uiAutomation`

**Purpose:** Execute UI automation actions on the current screen.

**Tool Signature:**

```kotlin
@Tool(description = "Perform a UI automation action on the current screen. " +
    "Supports actions: open_app, tap, tap_element, type_text, swipe, keyevent, " +
    "back, home, scroll, wait. Use captureScreen first to find element coordinates.")
suspend fun uiAutomation(
    @ToolParam(description = "The action to perform. One of: open_app, tap, tap_element, " +
        "type_text, swipe, keyevent, back, home, scroll, wait.")
    action: String,
    @ToolParam(description = "JSON string with action-specific parameters. " +
        "Required fields vary by action type.")
    parameters: String
): Map<String, String>
```

**Supported Actions:**

| Action | Required Parameters | Description | Example `parameters` JSON |
|--------|-------------------|-------------|---------------------------|
| `open_app` | `package_name` | Launch an app by package name | `{"package_name": "com.tencent.mm"}` |
| `tap` | `x`, `y` | Tap at screen coordinates | `{"x": 350, "y": 160}` |
| `tap_element` | `element_index` | Tap the Nth interactive element from `captureScreen` result | `{"element_index": 0}` |
| `type_text` | `text` | Type text into the currently focused text field | `{"text": "Hello"}` |
| `swipe` | `x1`, `y1`, `x2`, `y2` | Swipe from one coordinate to another | `{"x1": 500, "y1": 1500, "x2": 500, "y2": 500, "duration": 300}` |
| `keyevent` | `keycode` | Send a key event | `{"keycode": "KEYCODE_ENTER"}` |
| `back` | *(none)* | Press the back button | `{}` |
| `home` | *(none)* | Press the home button | `{}` |
| `scroll` | `direction` | Scroll in a direction (up/down/left/right) | `{"direction": "down"}` |
| `wait` | `timeout_ms` | Wait for a window change (e.g., after opening an app) | `{"timeout_ms": 5000}` |

**Return Format:**

```json
{
  "status": "success",
  "action": "tap",
  "details": "Tapped at coordinates (350, 160)"
}
```

or on failure:

```json
{
  "status": "error",
  "action": "tap",
  "message": "Accessibility service not enabled"
}
```

**Implementation Details (`UiAutomationTools.kt`):**

```kotlin
object UiAutomationTools {

    fun isServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val serviceName = "${context.packageName}/com.google.ai.edge.gallery.customtasks.agentchat.UiAutomationService"
        return enabledServices.contains(serviceName)
    }

    suspend fun captureScreen(context: Context, service: UiAutomationService): Map<String, Any> {
        // 1. Take screenshot via screencap
        // 2. Traverse accessibility node tree
        // 3. Build and return JSON
    }

    suspend fun executeUiAction(
        context: Context,
        service: UiAutomationService,
        action: String,
        parameters: String
    ): Map<String, String> {
        // Dispatch to appropriate handler based on action type
    }

    // --- Internal command builders ---

    private fun execInputCommand(command: String): String {
        val process = Runtime.getRuntime().exec("input $command")
        val exitCode = process.waitFor()
        return if (exitCode == 0) "success" else "failed (exit code $exitCode)"
    }
}
```

**Key Execution Patterns:**

```kotlin
// open_app: launch activity via am start
val intent = "am start -n $packageName/$(cmd package resolve-activity --brief $packageName | tail -n 1 | tr '/' ' ' | awk '{print $1}')"
// Fallback: monkey launch
val intent = "monkey -p $packageName -c android.intent.category.LAUNCHER 1"

// tap
execInputCommand("tap $x $y")

// type_text (must escape spaces for shell)
execInputCommand("text \"${text.replace("\"", "\\\"").replace(" ", "%s")}\"")

// swipe
execInputCommand("swipe $x1 $y1 $x2 $y2 ${duration ?: 300}")

// keyevent
execInputCommand("keyevent $keycode")

// back
execInputCommand("keyevent KEYCODE_BACK")

// home
execInputCommand("keyevent KEYCODE_HOME")

// scroll (swipe-based)
when (direction) {
    "up"    -> execInputCommand("swipe 500 1500 500 500 300")
    "down"  -> execInputCommand("swipe 500 500 500 1500 300")
    "left"  -> execInputCommand("swipe 900 1000 100 1000 300")
    "right" -> execInputCommand("swipe 100 1000 900 1000 300")
}
```

---

## 4. Operation Flow Example

### Scenario: "Send a message to Zhang San on WeChat saying hello"

The following sequence shows how the LLM agent would complete this multi-step task using the new tools.

```
Step 1: Agent calls captureScreen()
        → Returns current screen info (e.g., home screen, launcher app)

Step 2: Agent calls uiAutomation(action="open_app", parameters='{"package_name":"com.tencent.mm"}')
        → Launches WeChat

Step 3: Agent calls uiAutomation(action="wait", parameters='{"timeout_ms":5000}')
        → Waits for WeChat to finish loading

Step 4: Agent calls captureScreen()
        → Returns WeChat home screen with element list:
            [0] text="Search", bounds={center: (350,160)}
            [1] text="Zhang San", bounds={center: (350,400)}
            [2] text="Li Si", bounds={center: (350,520)}
            ...

Step 5: Agent identifies "Zhang San" at element_index=1
        → Calls uiAutomation(action="tap_element", parameters='{"element_index":1}')
        → Opens the chat with Zhang San

Step 6: Agent calls captureScreen()
        → Returns chat screen with text input field detected

Step 7: Agent calls uiAutomation(action="tap", parameters='{"x":350,"y":1800}')
        → Taps the message input field to focus it

Step 8: Agent calls uiAutomation(action="type_text", parameters='{"text":"Hello"}')
        → Types "Hello" into the input field

Step 9: Agent calls captureScreen()
        → Confirms the text field now contains "Hello", Send button visible

Step 10: Agent calls uiAutomation(action="tap", parameters='{"x":600,"y":1800}')
         → Taps the Send button

Step 11: Agent calls captureScreen()
         → Confirms message was sent (text field cleared, message bubble visible)

Step 12: Agent responds to user: "I've sent 'Hello' to Zhang San on WeChat."
```

**LLM Tool Call Sequence (summary):**

```
1. captureScreen()                                    → get current state
2. uiAutomation(open_app, "com.tencent.mm")            → launch WeChat
3. uiAutomation(wait, 5000ms)                          → wait for load
4. captureScreen()                                    → find Zhang San in list
5. uiAutomation(tap_element, index=1)                  → open chat
6. captureScreen()                                    → verify chat screen
7. uiAutomation(tap, x=350, y=1800)                    → focus input field
8. uiAutomation(type_text, "Hello")                    → type message
9. captureScreen()                                    → verify text entered
10. uiAutomation(tap, x=600, y=1800)                   → tap Send
11. captureScreen()                                    → confirm sent
```

---

## 5. Permissions & Configuration

### 5.1 AndroidManifest.xml Changes

Add the Accessibility Service declaration inside the `<application>` tag:

```xml
<!-- AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.google.ai.edge.gallery"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- ... existing permissions ... -->

    <application
        android:name="${applicationName}"
        ...>

        <!-- ... existing components ... -->

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

    </application>
</manifest>
```

### 5.2 accessibility_service_config.xml

New file at `res/xml/accessibility_service_config.xml`:

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

**Configuration Breakdown:**

| Attribute | Value | Purpose |
|-----------|-------|---------|
| `accessibilityEventTypes` | `typeWindowStateChanged\|typeWindowsChanged\|typeViewTextChanged\|typeViewClicked` | Listen for window transitions, text changes, and clicks to track screen state |
| `accessibilityFlags` | `flagDefault\|flagRetrieveInteractiveWindows\|flagReportViewIds` | Enable comprehensive window content access |
| `canRetrieveWindowContent` | `true` | Required to read the UI node tree |
| `canPerformGestures` | `true` | Required for dispatching gestures via AccessibilityService (Android 13+) |
| `notificationTimeout` | `100` | 100ms debounce between events |

### 5.3 String Resource

Add to `res/values/strings.xml`:

```xml
<string name="accessibility_service_description">
    Edge Gallery UI Automation service enables the AI assistant to read screen content
    and perform UI interactions (tap, type, swipe) on your behalf. This service is
    required for the Agent Chat feature to interact with other apps. You can enable
    or disable this service at any time in Settings → Accessibility.
</string>
```

---

## 6. Security Considerations

### 6.1 User Consent & Control

- **Opt-in Required**: The Accessibility Service is **not** enabled by default. The user must manually enable it via **Settings → Accessibility → Edge Gallery UI Automation**.
- **Revocable at Any Time**: The user can disable the service at any time through system settings. The app should detect this and gracefully degrade (tools return "service not enabled" errors).
- **System-Managed**: Android's Accessibility Service framework is managed by the OS. The app cannot programmatically enable itself — the user must do it through the system UI.

### 6.2 Sensitive Operation Confirmation

For potentially sensitive operations (opening banking apps, sending messages to specific contacts, making payments), the agent uses the existing `AskInfoAgentAction` pattern to require explicit user confirmation:

```kotlin
// Before executing a sensitive action
val action = AskInfoAgentAction(
    dialogTitle = "Confirm Action",
    fieldLabel = "The AI is about to send a message to 'Zhang San' on WeChat. " +
                 "Type 'confirm' to proceed, or leave empty to cancel."
)
_actionChannel.send(action)
val result = action.result.await()
if (result != "confirm") {
    return mapOf("status" to "cancelled", "message" to "User did not confirm the action")
}
// Proceed with execution
```

**Actions Requiring Confirmation:**

| Action Category | Examples | Requires Confirmation |
|----------------|----------|----------------------|
| Navigation | home, back, open_app | No |
| Read screen | captureScreen | No |
| Input | type_text, tap | No (general) |
| **Messaging** | type_text in messaging apps | **Yes** |
| **Payments** | tap in banking/payment apps | **Yes** |
| **Data deletion** | tap delete buttons | **Yes** |

### 6.3 Privacy

- **No Data Storage**: Screen content and screenshots are processed in memory and not persisted to disk (except temporary screenshot files in `/data/local/tmp/` which are cleaned up after each session).
- **No Network Transmission**: Accessibility data never leaves the device.
- **Scoped Access**: The service only has access to the currently visible screen content. It cannot read notifications, SMS, or other background data unless those are displayed on screen.

### 6.4 Rate Limiting

The existing `checkCallLimit` mechanism in `AgentTools` (max 10 calls per tool per session) applies to `captureScreen` and `uiAutomation`, preventing infinite loops or excessive automation.

---

## 7. File Manifest

### New Files

| File | Path | Purpose |
|------|------|---------|
| **UiAutomationService.kt** | `customtasks/agentchat/UiAutomationService.kt` | AccessibilityService implementation. Manages root node access, foreground package tracking, window change events, and screen info extraction. |
| **UiAutomationTools.kt** | `customtasks/agentchat/UiAutomationTools.kt` | Contains `captureScreen()` and `uiAutomation()` tool implementations. Bridges agent layer with accessibility service and shell execution. |
| **accessibility_service_config.xml** | `res/xml/accessibility_service_config.xml` | Accessibility service configuration (event types, flags, capabilities). |

### Modified Files

| File | Path | Changes |
|------|------|---------|
| **AgentTools.kt** | `customtasks/agentchat/AgentTools.kt` | Add `@Tool`-annotated `captureScreen()` and `uiAutomation()` methods. Add `UiAutomationService` as a dependency or obtain via singleton accessor. Integrate with existing logging and call-limit infrastructure. |
| **AndroidManifest.xml** | `AndroidManifest.xml` | Add `<service>` declaration for `UiAutomationService` with `BIND_ACCESSIBILITY_SERVICE` permission and meta-data reference to config XML. |
| **strings.xml** | `res/values/strings.xml` | Add `accessibility_service_description` string resource. |

### Integration Points

```
AgentTools.kt
    │
    ├── UiAutomationTools.captureScreen()
    │       │
    │       ├── UiAutomationService.getScreenInfo()
    │       │       ├── getRootInActiveWindow()
    │       │       └── traverseAccessibilityNode()
    │       │
    │       └── Runtime.exec("screencap -p ...")
    │
    └── UiAutomationTools.executeUiAction()
            │
            ├── UiAutomationService.waitForWindowChange()
            ├── Runtime.exec("input tap/swipe/text/keyevent ...")
            └── Intent-based app launch (am start / monkey)
```

---

## 8. Future Considerations

### 8.1 GestureDispatcher (Android 13+)

Android 13 (API 33) introduced `GestureDescription` and `dispatchGesture()` on `AccessibilityService`, which provides a more reliable and secure way to perform gestures compared to shell `input` commands. The execution layer can be upgraded to use these APIs when available:

```kotlin
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun dispatchTapGesture(x: Float, y: Float) {
    val builder = GestureDescription.Builder()
    val path = Path()
    path.moveTo(x, y)
    path.lineTo(x, y)
    builder.addStroke(
        GestureDescription.StrokeDescription(path, 0, 100)
    )
    dispatchGesture(builder.build(), null, null)
}
```

### 8.2 Vision-Language Model (VLM) Integration

With a VLM, the agent could analyze screenshots directly (pixel-level understanding) rather than relying solely on the accessibility node tree. This would enable:
- Understanding complex layouts not fully exposed via accessibility nodes.
- Reading text from images or custom-rendered UI components.
- Better spatial reasoning for coordinate-based actions.

### 8.3 Action Replay & Undo

Future iterations could maintain a history of performed actions, enabling:
- Undo the last action.
- Replay a sequence of actions.
- Debug and audit what the agent did.

### 8.4 Cross-App Context Memory

The agent currently treats each `captureScreen()` call independently. Future work could include:
- Building a mental map of app navigation flows.
- Remembering element locations across sessions.
- Learning user preferences for common tasks.
