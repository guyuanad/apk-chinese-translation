# ADB WiFi 调试 + 系统级操作 - 设计文档

## 概述

在 AI Agent 聊天界面中新增 ADB WiFi 调试功能，使 AI 能够通过自然语言指令执行系统级操作（如修改系统设置、管理应用、读取文件等），无需 root 权限。

## 架构

```
AI Agent → run_adb(command=...) → 安全分析 → ADB 连接 → 执行命令 → 返回结果
```

## 核心组件

### 1. AdbConnectionManager
- **职责**: WiFi 调试配对、连接管理、状态持久化、自动重连
- **存储**: 使用 DataStore 保存配对信息（IP、端口、密钥）
- **连接**: 通过 WiFi Socket 连接到本地无线调试服务
- **重连**: 连接断开自动重试 3 次，失败后引导用户重新配对

### 2. AdbShellProtocol
- **职责**: 简化版 ADB 协议实现，仅支持 shell 命令执行
- **认证**: RSA Token 认证握手
- **命令**: 封装 `shell:` 消息，分离 stdout/stderr 响应
- **超时**: 单个命令执行 10 秒超时

### 3. AdbCommandSecurity
- **职责**: 命令安全分级、白名单/黑名单检查
- **分级**:
  - READ: 只读命令（cat, ls, dumpsys, settings get, pm list），直接执行
  - WRITE: 写操作（settings put, cmd notification），用户确认
  - DANGEROUS: 高风险（rm -rf, pm uninstall, reboot），强确认
  - BLOCKED: 禁止执行（su, root），直接拒绝

### 4. AgentTools.runAdb()
- **新增 @Tool 方法**
- **参数**: command (String)
- **返回**: 命令执行结果（stdout 或错误信息）
- **流程**: 安全检查 → 连接检查 → 执行命令 → 返回结果

## 新增文件

| 文件 | 用途 |
|------|------|
| `customtasks/agentchat/AdbConnectionManager.kt` | 连接管理 |
| `customtasks/agentchat/AdbShellProtocol.kt` | ADB 协议 |
| `customtasks/agentchat/AdbCommandSecurity.kt` | 安全分级 |
| `skills/adb-shell/SKILL.md` | AI 使用说明 |

## 修改文件

| 文件 | 修改内容 |
|------|---------|
| `AgentTools.kt` | 新增 runAdb @Tool 方法 + AskAdbPermissionAction |
| `AndroidManifest.xml` | 新增 ACCESS_WIFI_STATE, CHANGE_WIFI_STATE |

## 配对流程

1. 首次使用：检测无线调试 → 引导开启 → 输入配对码 → 完成配对 → 保存信息
2. 后续使用：读取保存信息 → 自动连接 → 失败重试 → 提示重新配对

## 安全控制

- 命令分级执行（READ/WRITE/DANGEROUS/BLOCKED）
- 危险命令需要用户明确确认
- 命令审计日志记录
- 超时保护（连接 5s，命令 10s）
