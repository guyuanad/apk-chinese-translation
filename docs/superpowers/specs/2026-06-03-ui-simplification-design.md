# UI界面简化设计文档

**日期**: 2026-06-03
**版本**: 1.0
**状态**: 设计阶段

## 1. 概述

### 1.1 设计目标

将原有的多入口界面简化为**单一入口AI Chat模式**，通过智能工具调用机制集成所有功能，提升用户体验和操作便捷性。

### 1.2 当前问题

- 主屏幕功能卡片过多（5-6个），用户选择困难
- 功能入口分散，用户需要在不同页面间切换
- 图片问答、音频转录等功能与AI Chat流程割裂

### 1.3 设计原则

- **极简主义**: 一个入口解决所有需求
- **智能优先**: AI自动判断用户需求，调用合适工具
- **渐进式增强**: 保留高级功能入口，不破坏现有架构

---

## 2. 功能设计

### 2.1 首页结构调整

#### 修改前
```
首页
├── AI Chat（卡片）
├── Prompt Lab（卡片）
├── Ask Image（卡片）
├── Audio Scribe（卡片）
├── Mobile Actions（卡片）
└── Tiny Garden（卡片）

侧边栏
├── Settings
└── Models
```

#### 修改后
```
首页
└── AI Chat（唯一卡片入口）

侧边栏
├── Settings
└── Models（保留）
```

### 2.2 AI Chat功能增强

#### 2.2.1 输入框工具按钮

在聊天输入框左侧添加合并的图片工具按钮，采用长按录音交互：

```
┌─────────────────────────────────────────┐
│ [🖼️]  输入消息...  [发送]    │
└─────────────────────────────────────────┘
↑ 长按输入框区域开始录音
```

按钮说明：
- 🖼️ **图片图标** - 点击弹出菜单选择「拍照」或「从相册选择」
- **长按输入框** - 开始录音，松开发送

#### 2.2.2 图片选择菜单设计

**菜单选项**：
1. 📷 **拍照** - 启动相机拍照
2. 🖼️ **从相册选择** - 打开相册选择图片

**交互流程**：
1. 用户点击图片图标 → 弹出底部菜单
2. 选择拍照或相册 → 执行相应操作
3. 选择完成后图片自动添加到对话

#### 2.2.3 长按录音交互设计

**交互流程**：
1. 用户长按输入框区域 → 显示录音波形动画
2. 向上滑动 → 取消录音
3. 松开手指 → 发送语音消息

**视觉反馈**：
- 长按时输入框变为录音状态，显示麦克风图标
- 显示实时录音时长（0:00 → 0:05）
- 显示波形动画效果
- 向上滑动时显示"松开取消"提示

#### 2.2.4 工具调用系统

为AI Chat添加Function Calling能力，支持以下工具：

| 工具名称 | 触发场景 | 功能说明 |
|----------|----------|----------|
| `analyze_image` | 用户上传图片或询问图片内容 | 分析并描述图片 |
| `transcribe_audio` | 用户发送语音消息或请求转录 | 音频转录为文字 |
| `execute_mobile_action` | 用户请求手机操作任务 | 执行移动操作 |
| `play_tiny_garden` | 用户想玩花园游戏 | 启动Tiny Garden |

### 2.3 对话流程示例

#### 场景1：图片分析
```
用户：帮我看看这张照片
    ↓
[用户点击相册图标，选择照片]
    ↓
AI：正在分析图片...
    ↓
[工具调用：analyze_image]
    ↓
AI：这张照片显示的是一只可爱的小狗在草地上玩耍...
```

#### 场景2：语音转录
```
用户：帮我把这段录音转成文字
    ↓
[用户点击麦克风图标，录制语音]
    ↓
AI：正在转录音频...
    ↓
[工具调用：transcribe_audio]
    ↓
AI：转录内容：[语音内容文本]
```

---

## 3. 技术实现

### 3.1 文件修改清单

| 文件路径 | 修改类型 | 说明 |
|----------|----------|------|
| `HomeScreen.kt` | ✅ 修改 | 移除其他功能卡片，仅保留AI Chat |
| `LlmChatTaskModule.kt` | ✅ 修改 | 增强AI Chat功能，添加工具定义 |
| `MessageInputText.kt` | ✅ 修改 | 添加快捷工具按钮 |
| `LlmChatViewModel.kt` | ✅ 修改 | 处理工具调用逻辑 |
| `LlmChatScreen.kt` | ✅ 修改 | 优化聊天界面展示 |

### 3.2 技术架构

#### 3.2.1 首页简化

修改 `HomeScreen.kt` 中的任务过滤逻辑，只显示AI Chat任务：

```kotlin
// 过滤：只显示AI Chat任务
tasks = uiState.tasks.filter { it.id == BuiltInTaskId.LLM_CHAT }
```

#### 3.2.2 输入框增强

在 `MessageInputText.kt` 中添加合并的图片按钮和长按录音逻辑：

```kotlin
var showImageMenu by remember { mutableStateOf(false) }

Row(
    modifier = Modifier.padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    // 图片按钮（点击弹出菜单）
    IconButton(onClick = { showImageMenu = true }) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = "选择图片")
    }
    // 输入框（支持长按录音）
    Box(
        modifier = Modifier
            .weight(1f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        // 长按开始录音
                        startRecording()
                    }
                )
                detectDragGestures(
                    onDragEnd = {
                        // 拖动结束，判断是发送还是取消
                        if (isCanceledBySwipe) {
                            cancelRecording()
                        } else {
                            sendVoiceMessage()
                        }
                    }
                )
            }
    ) {
        TextField(...)
    }
    // 发送按钮
}

// 图片选择底部菜单
if (showImageMenu) {
    ModalBottomSheet(onDismissRequest = { showImageMenu = false }) {
        Column(Modifier.padding(16.dp)) {
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Camera, contentDescription = null) },
                headlineContent = { Text("拍照") },
                onClick = {
                    showImageMenu = false
                    launchCamera()
                }
            )
            ListItem(
                leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                headlineContent = { Text("从相册选择") },
                onClick = {
                    showImageMenu = false
                    launchGallery()
                }
            )
        }
    }
}
```

**实现要点**：
- 使用 `ModalBottomSheet` 显示选择菜单
- 使用 `detectTapGestures` 检测长按
- 使用 `detectDragGestures` 处理滑动取消
- 录音状态显示波形动画和时长

#### 3.2.3 工具调用机制

扩展 `LlmChatViewModel` 添加工具处理逻辑：

```kotlin
sealed class ToolCall {
    data class AnalyzeImage(val imageUri: Uri) : ToolCall()
    data class TranscribeAudio(val audioUri: Uri) : ToolCall()
    data class ExecuteMobileAction(val action: String) : ToolCall()
    object PlayTinyGarden : ToolCall()
}

class LlmChatViewModel {
    fun processUserMessage(message: String, attachments: List<Uri>?) {
        // 1. 分析用户意图和附件
        // 2. 判断是否需要调用工具
        // 3. 执行工具调用
        // 4. 将结果整合到AI回复中
    }
}
```

### 3.3 数据模型变化

无需新增数据模型，复用现有架构：

- 图片上传：使用现有的 `MediaAttachment` 机制
- 音频上传：复用现有的 `AudioAttachment` 机制
- 工具调用结果：作为特殊消息类型显示

---

## 4. 实现阶段规划

### 阶段1：首页简化（优先）
- [ ] 移除其他功能卡片
- [ ] 测试侧边栏功能
- [ ] 确保只显示AI Chat

### 阶段2：输入框增强
- [ ] 添加合并的图片按钮
- [ ] 实现底部菜单选择「拍照」或「从相册选择」
- [ ] 实现长按录音交互
- [ ] 实现录音波形动画和时长显示
- [ ] 实现滑动取消录音
- [ ] 实现图片/音频上传
- [ ] UI/UX测试

### 阶段3：工具调用集成
- [ ] 实现图片分析工具
- [ ] 实现音频转录工具
- [ ] 实现移动操作工具
- [ ] 实现Tiny Garden启动器

### 阶段4：整体测试
- [ ] 完整流程测试
- [ ] 边缘用例测试
- [ ] 性能优化

---

## 5. 用户体验优化

### 5.1 视觉反馈

- **工具调用状态**：显示AI正在"分析图片"、"转录音频"的提示
- **进度指示器**：长时间操作时显示加载动画
- **工具结果展示**：在聊天消息中清晰展示工具执行结果

### 5.2 降级方案

如果工具调用失败，提供优雅的降级处理：
```kotlin
try {
    // 尝试调用工具
} catch (e: Exception) {
    // 提示用户直接访问该功能页面
}
```

---

## 6. 回退计划

如果新设计效果不佳，可随时回退：
1. 恢复 `HomeScreen.kt` 中的任务列表显示
2. 移除输入框中的工具按钮
3. 恢复各功能的独立卡片入口

---

## 7. 验收标准

- [ ] 首页只显示AI Chat一个卡片
- [ ] 侧边栏设置和模型管理可正常访问
- [ ] 输入框左侧显示一个图片按钮
- [ ] 点击图片按钮弹出底部菜单选择「拍照」或「从相册选择」
- [ ] 长按输入框可启动录音
- [ ] 向上滑动可取消录音
- [ ] 录音时显示波形动画和时长
- [ ] 输入框可正常上传图片和音频
- [ ] 用户使用AI Chat可完成所有现有功能
- [ ] 工具调用响应时间小于3秒
- [ ] 构建APK通过编译

---

**文档完成日期**: 2026-06-03
**下次评审**: 等待用户审查确认
