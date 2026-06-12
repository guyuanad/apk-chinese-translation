# ADB WiFi 调试 + 系统级操作 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 添加 ADB WiFi 调试工具，使 AI Agent 能够通过自然语言指令执行系统级操作

**Architecture:** 纯 ADB 方案，通过 WiFi Socket 连接无线调试服务，由 Kotlin 层实现简化版 ADB 协议执行 shell 命令

**Tech Stack:** Kotlin Coroutines, Java Socket, DataStore (持久化), Compose (确认弹窗)

---

### Task 1: 添加 WiFi 权限到 AndroidManifest

**Files:**
- Modify: `Android/src/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 添加 WiFi 权限**

在现有权限声明中添加：

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
```

放在 `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` 之后。

- [ ] **Step 2: 提交**

```bash
cd /workspace/apk-chinese-translation
git add Android/src/app/src/main/AndroidManifest.xml
git commit -m "feat: 添加WiFi权限用于ADB无线调试功能"
```

---

### Task 2: 创建 AdbCommandSecurity 命令安全分级器

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AdbCommandSecurity.kt`

- [ ] **Step 1: 创建 AdbCommandSecurity.kt**

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

import android.util.Log

private const val TAG = "AdbSecurity"

enum class CommandSecurityLevel {
  READ,       // 只读，直接执行
  WRITE,      // 写操作，需要用户确认
  DANGEROUS,  // 高风险，强确认
  BLOCKED     // 禁止执行
}

data class CommandAnalysis(
  val level: CommandSecurityLevel,
  val description: String,
  val warning: String? = null
)

object AdbCommandSecurity {
  
  private val blockedPatterns = listOf(
    Regex("\\bsu\\b"),
    Regex("\\broot\\b"),
    Regex("chmod\\s+777\\s+/"),
    Regex("dd\\s+if="),
    Regex("mkfs"),
    Regex("flash"),
  )
  
  private val dangerousPatterns = listOf(
    Regex("\\brm\\s+-rf\\b"),
    Regex("\\bpm\\s+uninstall\\b"),
    Regex("\\breboot\\b"),
    Regex("\\bwipe\\b"),
    Regex("\\bam\\s+broadcast\\b"),
    Regex("\\bsettings\\s+delete\\b"),
    Regex("\\bpm\\s+clear\\b"),
    Regex("\\bformat\\b"),
  )
  
  private val writePatterns = listOf(
    Regex("\\bsettings\\s+put\\b"),
    Regex("\\bcmd\\s+notification\\b"),
    Regex("\\bam\\s+force-stop\\b"),
    Regex("\\bam\\s+start\\b"),
    Regex("\\bpm\\s+install\\b"),
    Regex("\\bcontent\\s+insert\\b"),
    Regex("\\bcontent\\s+update\\b"),
    Regex("\\bcontent\\s+delete\\b"),
    Regex("\\bsvc\\s+power\\b"),
    Regex("\\bsvc\\s+wifi\\b"),
    Regex("\\binput\\s+"),
  )
  
  private val readPatterns = listOf(
    Regex("\\bcat\\b"),
    Regex("\\bls\\b"),
    Regex("\\bdumpsys\\b"),
    Regex("\\bsettings\\s+get\\b"),
    Regex("\\bpm\\s+list\\b"),
    Regex("\\bgetprop\\b"),
    Regex("\\bps\\b"),
    Regex("\\btop\\b"),
    Regex("\\bdf\\b"),
    Regex("\\bfree\\b"),
    Regex("\\buname\\b"),
    Regex("\\bid\\b"),
    Regex("\\bwhoami\\b"),
    Regex("\\bnetstat\\b"),
    Regex("\\bip\\s+addr\\b"),
    Regex("\\bifconfig\\b"),
    Regex("\\bcmd\\s+activity\\b"),
    Regex("\\bcmd\\s+window\\b"),
    Regex("\\bcontent\\s+query\\b"),
    Regex("\\bcontent\\s+call\\b"),
  )
  
  fun analyze(command: String): CommandAnalysis {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) {
      return CommandAnalysis(CommandLevel.BLOCKED, "空命令", "命令不能为空")
    }
    
    // Check blocked first
    for (pattern in blockedPatterns) {
      if (pattern.containsMatchIn(trimmed)) {
        Log.w(TAG, "Blocked command detected: $trimmed")
        return CommandAnalysis(
          CommandSecurityLevel.BLOCKED,
          "命令被禁止执行",
          "此命令包含危险操作，已被系统拦截"
        )
      }
    }
    
    // Check dangerous
    for (pattern in dangerousPatterns) {
      if (pattern.containsMatchIn(trimmed)) {
        Log.w(TAG, "Dangerous command detected: $trimmed")
        return CommandAnalysis(
          CommandSecurityLevel.DANGEROUS,
          "高风险命令",
          "此命令可能造成不可逆的数据丢失或系统变更"
        )
      }
    }
    
    // Check write
    for (pattern in writePatterns) {
      if (pattern.containsMatchIn(trimmed)) {
        return CommandAnalysis(
          CommandSecurityLevel.WRITE,
          "修改系统设置或数据"
        )
      }
    }
    
    // Check read
    for (pattern in readPatterns) {
      if (pattern.containsMatchIn(trimmed)) {
        return CommandAnalysis(
          CommandSecurityLevel.READ,
          "读取数据"
        )
      }
    }
    
    // Default to WRITE for unknown commands
    return CommandAnalysis(
      CommandSecurityLevel.WRITE,
      "未知命令类型，需要确认"
    )
  }
}
```

- [ ] **Step 2: 提交**

```bash
cd /workspace/apk-chinese-translation
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AdbCommandSecurity.kt
git commit -m "feat: 添加ADB命令安全分级器"
```

---

### Task 3: 创建 AdbShellProtocol ADB 协议实现

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AdbShellProtocol.kt`

- [ ] **Step 1: 创建 AdbShellProtocol.kt**

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

import android.util.Log
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

private const val TAG = "AdbProtocol"
private const val ADB_HEADER_SIZE = 24
private const val CONNECT_TIMEOUT_MS = 5000L
private const val COMMAND_TIMEOUT_MS = 10000L

private const val A_VERSION = 0x01000000
private const val A_MAXDATA = 4 * 1024 * 1024  // 4MB

// ADB message types
private const val A_SYNC = 0x434e5953
private const val A_CNXN = 0x4e584e43
private const val A_OPEN = 0x4e45504f
private const val A_OKAY = 0x59414b4f
private const val A_CLSE = 0x45534c43
private const val A_WRTE = 0x45545257

// ADB identity string
private const val ADB_CONNECT_STRING = "host::\n"

data class AdbShellResult(
  val stdout: String,
  val stderr: String,
  val exitCode: Int
)

class AdbShellProtocol(private val socket: Socket) {
  private val inputStream: InputStream = socket.inputStream
  private val outputStream: OutputStream = socket.outputStream
  private var localId = 1
  
  suspend fun connect(): Boolean {
    return try {
      withTimeout(CONNECT_TIMEOUT_MS) {
        sendConnect()
        readConnectResponse()
      }
    } catch (e: Exception) {
      Log.e(TAG, "ADB connect failed: ${e.message}")
      false
    }
  }
  
  private fun sendConnect() {
    val payload = ADB_CONNECT_STRING.toByteArray(Charsets.UTF_8)
    val message = createMessage(A_CNXN, A_VERSION, A_MAXDATA, payload)
    outputStream.write(message)
    outputStream.flush()
  }
  
  private fun readConnectResponse(): Boolean {
    val (command, _, _, payload) = readMessage()
    return command == A_CNXN
  }
  
  suspend fun executeShellCommand(command: String): AdbShellResult {
    return withTimeout(COMMAND_TIMEOUT_MS) {
      val dest = "shell:$command"
      val openLocalId = localId++
      
      // Send OPEN
      val openPayload = dest.toByteArray(Charsets.UTF_8)
      val openMsg = createMessage(A_OPEN, openLocalId, 0, openPayload)
      outputStream.write(openMsg)
      outputStream.flush()
      
      // Wait for OKAY
      var (cmd, remoteId, _, _) = readMessage()
      if (cmd == A_CNXN) {
        // Re-connect if needed
        sendConnect()
        readMessage() // Skip response
      }
      if (cmd != A_OKAY) {
        throw RuntimeException("Expected OKAY after OPEN, got: 0x${cmd.toString(16)}")
      }
      
      // Read response stream
      var stdout = ""
      var stderr = ""
      var exitCode = 0
      
      while (true) {
        val (msgCmd, msgRemoteId, _, msgPayload) = readMessage()
        if (msgRemoteId != openLocalId && msgRemoteId != 0) continue
        
        when (msgCmd) {
          A_WRTE -> {
            val text = msgPayload.toString(Charsets.UTF_8)
            // ADB shell returns stdout and stderr interleaved, with OKAY messages
            stdout += text
            // Send OKAY back
            val okayMsg = createMessage(A_OKAY, openLocalId, 0, byteArrayOf())
            outputStream.write(okayMsg)
            outputStream.flush()
          }
          A_CLSE -> {
            // Send CLSE back
            val clseMsg = createMessage(A_CLSE, openLocalId, 0, byteArrayOf())
            outputStream.write(clseMsg)
            outputStream.flush()
            break
          }
          else -> {
            Log.w(TAG, "Unexpected message: 0x${msgCmd.toString(16)}")
          }
        }
      }
      
      AdbShellResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }
  }
  
  private fun readMessage(): ReadResult {
    val header = ByteArray(ADB_HEADER_SIZE)
    var offset = 0
    while (offset < ADB_HEADER_SIZE) {
      val read = inputStream.read(header, offset, ADB_HEADER_SIZE - offset)
      if (read == -1) throw RuntimeException("Connection closed")
      offset += read
    }
    
    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val command = buffer.int
    val arg0 = buffer.int
    val arg1 = buffer.int
    val dataLength = buffer.int
    val checksum = buffer.int
    val magic = buffer.int
    
    if (command + magic != -1) {
      Log.w(TAG, "ADB message checksum failed: cmd=0x${command.toString(16)} magic=0x${magic.toString(16)}")
    }
    
    val payload = if (dataLength > 0) {
      val data = ByteArray(dataLength)
      var p = 0
      while (p < dataLength) {
        val read = inputStream.read(data, p, dataLength - p)
        if (read == -1) throw RuntimeException("Connection closed during payload read")
        p += read
      }
      data
    } else {
      byteArrayOf()
    }
    
    return ReadResult(command, arg0, arg1, payload)
  }
  
  private fun createMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
    val checksum = payload.sumOf { it.toLong() and 0xFFL }.toInt()
    val magic = command xor -1
    
    val buffer = ByteBuffer.allocate(ADB_HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(command)
    buffer.putInt(arg0)
    buffer.putInt(arg1)
    buffer.putInt(payload.size)
    buffer.putInt(checksum)
    buffer.putInt(magic)
    buffer.put(payload)
    
    return buffer.array()
  }
  
  fun close() {
    try {
      socket.close()
    } catch (e: Exception) {
      Log.w(TAG, "Error closing socket: ${e.message}")
    }
  }
  
  private data class ReadResult(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray
  )
}
```

- [ ] **Step 2: 提交**

```bash
cd /workspace/apk-chinese-translation
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AdbShellProtocol.kt
git commit -m "feat: 添加简化版ADB协议实现"
```

---

### Task 4: 创建 AdbConnectionManager 连接管理器

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AdbConnectionManager.kt`

- [ ] **Step 1: 创建 AdbConnectionManager.kt**

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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Socket

private const val TAG = "AdbConnection"
private const val MAX_RECONNECT_ATTEMPTS = 3

enum class AdbConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  ERROR
}

data class AdbConnectionInfo(
  val ip: String = "127.0.0.1",
  val port: Int = 5555,
  var isConnected: Boolean = false
)

class AdbConnectionManager(private val context: Context) {
  
  private val mutex = Mutex()
  private var protocol: AdbShellProtocol? = null
  var connectionState: AdbConnectionState = AdbConnectionState.DISCONNECTED
    private set
  
  private var connectionInfo = AdbConnectionInfo()
  
  suspend fun connect(ip: String = "127.0.0.1", port: Int = 5555): Result<AdbShellResult> {
    return mutex.withLock {
      withContext(Dispatchers.IO) {
        try {
          connectionState = AdbConnectionState.CONNECTING
          connectionInfo = AdbConnectionInfo(ip, port)
          Log.d(TAG, "Connecting to ADB at $ip:$port")
          
          val socket = Socket(ip, port)
          socket.soTimeout = 10000
          
          protocol = AdbShellProtocol(socket)
          val connected = protocol?.connect()
          
          if (connected == true) {
            connectionState = AdbConnectionState.CONNECTED
            connectionInfo.isConnected = true
            Log.d(TAG, "ADB connected successfully")
            Result.success(AdbShellResult("Connected to ADB at $ip:$port", "", 0))
          } else {
            connectionState = AdbConnectionState.ERROR
            socket.close()
            Result.failure(Exception("ADB connection handshake failed"))
          }
        } catch (e: Exception) {
          connectionState = AdbConnectionState.ERROR
          Log.e(TAG, "ADB connection failed: ${e.message}")
          Result.failure(e)
        }
      }
    }
  }
  
  suspend fun executeCommand(command: String): Result<AdbShellResult> {
    return mutex.withLock {
      withContext(Dispatchers.IO) {
        // Auto-connect if not connected
        if (connectionState != AdbConnectionState.CONNECTED || protocol == null) {
          val connectResult = connect()
          if (connectResult.isFailure) {
            return@withContext Result.failure(connectResult.exceptionOrNull() ?: Exception("Connection failed"))
          }
        }
        
        try {
          val result = protocol?.executeShellCommand(command)
          if (result != null) {
            Result.success(result)
          } else {
            Result.failure(Exception("ADB protocol is null"))
          }
        } catch (e: Exception) {
          Log.e(TAG, "Command execution failed: ${e.message}")
          // Try to reconnect once
          connectionState = AdbConnectionState.DISCONNECTED
          protocol?.close()
          protocol = null
          Result.failure(e)
        }
      }
    }
  }
  
  fun disconnect() {
    connectionState = AdbConnectionState.DISCONNECTED
    connectionInfo.isConnected = false
    protocol?.close()
    protocol = null
  }
  
  fun getConnectionInfo(): AdbConnectionInfo = connectionInfo.copy(isConnected = connectionState == AdbConnectionState.CONNECTED)
  
  fun isConnected(): Boolean = connectionState == AdbConnectionState.CONNECTED
}
```

- [ ] **Step 2: 提交**

```bash
cd /workspace/apk-chinese-translation
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AdbConnectionManager.kt
git commit -m "feat: 添加ADB连接管理器"
```

---

### Task 5: 添加 runAdb 工具到 AgentTools

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTools.kt`

- [ ] **Step 1: 添加 runAdb @Tool 方法**

在 `AgentTools` 类中添加以下代码（放在 `searchWeb` 方法之后，`runIntent` 方法之前）：

```kotlin
  // --- ADB Connection Manager ---
  private val adbManager: AdbConnectionManager by lazy { AdbConnectionManager(context) }

  @Tool(
    description =
      "Execute ADB shell commands on the device. Requires wireless debugging to be enabled. " +
        "Can read files, modify system settings, manage apps, and more. " +
        "Examples: 'cat /sdcard/test.txt', 'settings get system screen_brightness', 'pm list packages'"
  )
  fun runAdb(
    @ToolParam(description = "The ADB shell command to execute. Examples: 'ls /sdcard/', 'dumpsys battery', 'settings put system screen_brightness 50'")
    command: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (!checkCallLimit("runAdb")) {
        return@runBlocking mapOf("error" to "Too many calls to runAdb", "status" to "blocked")
      }
      withToolLogging("runAdb") {
        runAdbInternal(command)
      }
    }
  }

  private suspend fun runAdbInternal(command: String): Map<String, Any> {
    writeLog("D", TAG, "runAdb tool called. command=$command")

    // Security analysis
    val analysis = AdbCommandSecurity.analyze(command)
    writeLog("D", TAG, "Command security level: ${analysis.level}, description: ${analysis.description}")

    when (analysis.level) {
      CommandSecurityLevel.BLOCKED -> {
        return mapOf(
          "error" to "Command blocked by security policy: ${analysis.warning}",
          "status" to "blocked"
        )
      }
      CommandSecurityLevel.DANGEROUS -> {
        // Show confirmation dialog
        val confirmAction = AskInfoAgentAction(
          dialogTitle = "⚠️ Dangerous Command",
          fieldLabel = "AI wants to execute: $command\n${analysis.warning}\n\nType 'CONFIRM' to proceed:"
        )
        _actionChannel.send(confirmAction)
        val userInput = confirmAction.result.await()
        if (userInput.trim() != "CONFIRM") {
          return mapOf("error" to "User did not confirm the dangerous operation", "status" to "cancelled")
        }
      }
      CommandSecurityLevel.WRITE -> {
        // Show confirmation for write operations
        val confirmAction = AskInfoAgentAction(
          dialogTitle = "Confirm Action",
          fieldLabel = "AI wants to execute: $command\n${analysis.description}\n\nType 'YES' to continue:"
        )
        _actionChannel.send(confirmAction)
        val userInput = confirmAction.result.await()
        if (userInput.trim() != "YES") {
          return mapOf("error" to "User did not confirm the operation", "status" to "cancelled")
        }
      }
      CommandSecurityLevel.READ -> {
        // Direct execution for read-only commands
      }
    }

    // Execute command
    _actionChannel.send(
      SkillProgressAgentAction(
        label = "Executing ADB command: $command",
        inProgress = true,
        addItemTitle = "ADB: $command",
        addItemDescription = "Security level: ${analysis.level}",
      )
    )

    val result = adbManager.executeCommand(command)
    return result.fold(
      onSuccess = { shellResult ->
        if (shellResult.exitCode == 0) {
          writeLog("D", TAG, "ADB command succeeded: ${shellResult.stdout}")
          mapOf("result" to shellResult.stdout, "status" to "succeeded")
        } else {
          writeLog("E", TAG, "ADB command failed (exit ${shellResult.exitCode}): ${shellResult.stderr}")
          mapOf("error" to (shellResult.stderr.ifEmpty { "Command failed with exit code ${shellResult.exitCode}" }), "status" to "failed")
        }
      },
      onFailure = { e ->
        val errorMsg = when {
          e.message?.contains("Connection refused", ignoreCase = true) == true ->
            "ADB connection refused. Please enable Wireless Debugging in Developer Options and try again."
          e.message?.contains("timed out", ignoreCase = true) == true ->
            "ADB connection timed out. Please check your wireless debugging connection."
          e.message?.contains("handshake", ignoreCase = true) == true ->
            "ADB connection handshake failed. Please re-pair your wireless debugging connection."
          else -> "ADB execution failed: ${e.message}"
        }
        writeLog("E", TAG, "ADB command failed: $errorMsg")
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "ADB command failed: $errorMsg",
            inProgress = false,
          )
        )
        mapOf("error" to errorMsg, "status" to "failed")
      }
    )
  }
```

- [ ] **Step 2: 提交**

```bash
cd /workspace/apk-chinese-translation
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTools.kt
git commit -m "feat: 添加runAdb工具方法到AgentTools"
```

---

### Task 6: 创建 adb-shell Skill 说明文档

**Files:**
- Create: `Android/src/app/src/main/assets/skills/adb-shell/SKILL.md`
- Create: `Android/src/app/src/main/assets/skills/adb-shell/scripts/index.html` (空文件)

- [ ] **Step 1: 创建 SKILL.md**

```markdown
---
name: adb-shell
description: Execute ADB shell commands for system-level operations like reading files, modifying settings, managing apps, and more.
instructions: |
  Use the runAdb tool to execute ADB shell commands.

  Common commands:
  - Read file: cat /path/to/file
  - List files: ls /path/to/directory
  - Get setting: settings get system/secure/global <key>
  - Set setting: settings put system/secure/global <key> <value>
  - List packages: pm list packages
  - Get battery info: dumpsys battery
  - Get activity info: dumpsys activity
  - Force stop app: am force-stop <package>
  - Start app: am start -n <package>/<activity>
  - Install APK: pm install -r /path/to/apk
  - Check network: ip addr show
  - Get properties: getprop <key>

  Important:
  - Read-only commands (cat, ls, dumpsys, settings get) execute directly
  - Write commands (settings put, am start) require user confirmation
  - Dangerous commands (rm -rf, pm uninstall, reboot) are blocked or require strong confirmation
  - Always use specific paths, avoid wildcards for destructive operations
  - If the command fails with a connection error, inform the user to enable Wireless Debugging

  Security:
  - Never use 'su' or 'root' commands
  - Never use 'rm -rf' without a specific path
  - Never format or wipe data
  - Respect user privacy - don't read sensitive files
---
```

- [ ] **Step 2: 创建空的 scripts/index.html**

```html
<!DOCTYPE html>
<html>
<head><title>ADB Shell</title></head>
<body>
<script>
// This skill is executed through the runAdb tool, not through JS.
if (typeof ai_edge_gallery_get_result === 'function') {
  ai_edge_gallery_get_result(JSON.stringify({ result: "ADB shell commands are executed via the runAdb tool." }));
}
</script>
</body>
</html>
```

- [ ] **Step 3: 提交**

```bash
cd /workspace/apk-chinese-translation
git add Android/src/app/src/main/assets/skills/adb-shell/
git commit -m "feat: 添加adb-shell skill文档"
```

---

### Task 7: 构建验证和最终提交

- [ ] **Step 1: 验证所有文件存在**

```bash
cd /workspace/apk-chinese-translation
ls -la Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/Adb*.kt
ls -la Android/src/app/src/main/assets/skills/adb-shell/
grep "ACCESS_WIFI_STATE" Android/src/app/src/main/AndroidManifest.xml
grep "runAdb" Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTools.kt
```

Expected: All files and references present.

- [ ] **Step 2: 推送并触发构建**

```bash
cd /workspace/apk-chinese-translation
git push
```

- [ ] **Step 3: 查看构建状态**

```bash
GH_TOKEN=***REDACTED*** gh run list --repo wenyuxiang123/apk-chinese-translation --limit 3
```

---
