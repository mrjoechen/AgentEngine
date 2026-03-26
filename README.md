# AgentEngine

**Kotlin Multiplatform AI Agent Framework**

A framework for Android, iOS, and Desktop apps to expose business functions and system capabilities to AI language models through standardized tool calling.

[English](#overview) | [中文](#概述)

```
+-----------------------------------------------------------+
|                        Host App                           |
|  +-----------------------------------------------------+ |
|  |                    AgentEngine                       | |
|  |  +---------+  +----------+  +--------------------+  | |
|  |  |  Tool   |  |  Agent   |  |    MCP Server      |  | |
|  |  |Registry |->|  Runtime |<-|  (stdio / SSE)     |  | |
|  |  +---------+  +----------+  +--------------------+  | |
|  |       ^            ^                                 | |
|  |  +---------+  +----------+                           | |
|  |  |Platform |  |   LLM    |                           | |
|  |  |Provider |  |Connectors|                           | |
|  |  +---------+  +----------+                           | |
|  +-----------------------------------------------------+ |
+-----------------------------------------------------------+
```

---

## Overview

AgentEngine lets you turn any app function into an AI-callable tool with a few lines of code. The AI model decides when and how to call your tools based on user intent, and AgentEngine handles the entire orchestration loop automatically.

### Key Features

- **Kotlin Multiplatform** -- Core logic is 100% in `commonMain`, targeting Android, iOS, and Desktop (JVM)
- **Three Tool Registration Methods** -- DSL builder, `@AgentTool` annotation (KSP), or `ToolProvider` interface
- **Multi-LLM Support** -- Pluggable `LlmConnector` abstraction with built-in connectors for OpenAI, Anthropic Claude, and Google Gemini; supports dynamic switching at runtime
- **OpenAI-Compatible Proxies** -- `OpenAiConnector` accepts a custom `baseUrl`, making it work with DeepSeek, GLM, Qwen, Moonshot, and any OpenAI-compatible API
- **MCP Protocol** -- Built-in MCP Server and Client with Stdio and SSE transports
- **Streaming** -- Flow-based SSE streaming for real-time token delivery
- **Permission Control** -- Tool-level permissions: `ALLOWED`, `REQUIRES_CONFIRMATION`, `BLOCKED`
- **Platform Capabilities** -- Cross-platform device/system tools auto-registered via `expect`/`actual`
- **Zero Intrusion** -- Register tools without modifying existing business code

### Module Structure

| Module | Description |
|--------|-------------|
| `agent-engine-core` | Core framework: Tool system, Agent Runtime, MCP protocol, LLM abstraction, Session management |
| `agent-engine-llm-anthropic` | Anthropic Claude connector (Messages API + SSE streaming) |
| `agent-engine-llm-gemini` | Google Gemini connector (generateContent + SSE streaming + Function Calling) |
| `agent-engine-llm-openai` | OpenAI connector (Chat Completions + SSE streaming + Function Calling; compatible with third-party proxies) |
| `agent-engine-platform` | Platform capabilities abstraction (`expect`/`actual`) with auto-registration as tools |
| `agent-engine-ksp` | KSP annotation processor for `@AgentTool` compile-time code generation |
| `composeApp` | Demo app (Compose Multiplatform) |
| `server` | Standalone MCP Server (Ktor) |

---

## Quick Start

### 1. Add Dependencies

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.alpha:agent-engine-core:<version>")
            // Pick one or more LLM connectors:
            implementation("com.alpha:agent-engine-llm-openai:<version>")
            implementation("com.alpha:agent-engine-llm-anthropic:<version>")
            implementation("com.alpha:agent-engine-llm-gemini:<version>")
            // Optional: platform capabilities
            implementation("com.alpha:agent-engine-platform:<version>")
        }
    }
}

// Optional: annotation-based tool generation
plugins {
    id("com.google.devtools.ksp")
}
dependencies {
    ksp("com.alpha:agent-engine-ksp:<version>")
}
```

### 2. Initialize AgentEngine

```kotlin
val engine = AgentEngine.builder()
    // Register one or more LLM connectors (first one is the default):
    .llm("openai", OpenAiConnector(apiKey = "sk-..."))
    .llm("claude", AnthropicConnector(apiKey = "sk-ant-..."))
    .llm("gemini", GeminiConnector(apiKey = "AIza..."))
    // Or use an OpenAI-compatible third-party API:
    // .llm("deepseek", OpenAiConnector(
    //     apiKey = "sk-...",
    //     modelId = "deepseek-chat",
    //     baseUrl = "https://api.deepseek.com/v1"
    // ))
    .systemPrompt("You are a helpful assistant.")
    .tools {
        tool("get_battery") {
            description = "Get current battery level"
            execute { ToolResult.text("${getBatteryLevel()}%") }
        }

        tool("search_contacts") {
            description = "Search contacts by name"
            parameter("query", StringType, "Search keyword")
            execute { params ->
                val results = contactsRepo.search(params.getString("query"))
                ToolResult.text(results.joinToString("\n"))
            }
        }

        tool("send_message") {
            description = "Send a message to a contact"
            parameter("to", StringType, "Recipient name")
            parameter("content", StringType, "Message content")
            permission = Permission.REQUIRES_CONFIRMATION
            execute { params ->
                messagingService.send(
                    params.getString("to"),
                    params.getString("content")
                )
                ToolResult.text("Message sent")
            }
        }
    }
    .onConfirmationRequired { toolCall ->
        showConfirmDialog(toolCall)  // Prompt user for sensitive operations
    }
    .build()
```

### 3. Chat

```kotlin
// Single-turn (tool calling loop handled automatically)
val result = engine.chat("Find Zhang San's contact info")
println(result)

// With session context
val session = engine.createSession()
engine.chat(session, "Hello")
engine.chat(session, "What did I just say?")  // Context preserved

// Streaming
engine.chatStream(session, "Check recent orders").collect { event ->
    when (event) {
        is AgentEvent.TextDelta -> print(event.text)
        is AgentEvent.ToolCallStart -> println("\n[Calling tool: ${event.toolName}]")
        is AgentEvent.ToolCallComplete -> println("[Result: ${event.result}]")
        is AgentEvent.ConfirmationRequired -> event.onResult(true)
        is AgentEvent.Done -> println("\n--- Done ---")
        is AgentEvent.Error -> println("Error: ${event.throwable.message}")
    }
}
```

### 4. Switch LLM at Runtime

```kotlin
// Check available connectors
engine.availableLlms  // ["openai", "claude", "gemini"]
engine.currentLlmName // "openai" (first registered is default)

// Switch to a different model
engine.switchLlm("claude")

// Add/remove connectors dynamically
engine.addLlm("qwen", OpenAiConnector(
    apiKey = "sk-...",
    modelId = "qwen-max-latest",
    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
))
engine.removeLlm("openai")
```

---

## Tool Registration

### Method 1: DSL

```kotlin
engine.tools {
    tool("calculate") {
        description = "Evaluate a math expression"
        parameter("expression", StringType, "Math expression, e.g. '2 + 3 * 4'")
        execute { params ->
            val result = eval(params.getString("expression"))
            ToolResult.text("$result")
        }
    }
}
```

### Method 2: @AgentTool Annotation (KSP)

```kotlin
class OrderService {
    @AgentTool(name = "search_orders", description = "Search order history")
    suspend fun searchOrders(
        @Param("Search keyword") query: String,
        @Param("Order status", required = false) status: String? = null
    ): List<Order> {
        return orderRepo.search(query, status)
    }
}

// KSP generates OrderServiceToolProvider : ToolProvider
val engine = AgentEngine.builder()
    .tools { register(OrderServiceToolProvider(orderService)) }
    .build()
```

### Method 3: ToolProvider Interface

```kotlin
class PaymentToolProvider : ToolProvider {
    override fun provideTools(): List<ToolDefinition> = listOf(
        tool("check_balance") {
            description = "Check account balance"
            execute { ToolResult.text("${wallet.balance} USD") }
        },
        tool("transfer") {
            description = "Transfer money"
            parameter("to", StringType, "Recipient")
            parameter("amount", NumberType, "Amount")
            permission = Permission.REQUIRES_CONFIRMATION
            execute { params -> /* ... */ }
        }
    )
}

engine.registerProvider(PaymentToolProvider())
```

---

## LLM Connectors

### Anthropic Claude

```kotlin
AnthropicConnector(
    apiKey = "sk-ant-...",
    modelId = "claude-sonnet-4-6",          // Default model
    baseUrl = "https://api.anthropic.com",   // Custom endpoint
    apiVersion = "2023-06-01",
    enableLogging = false                    // Debug logging
)
```

### Google Gemini

```kotlin
GeminiConnector(
    apiKey = "AIza...",
    modelId = "gemini-2.5-flash",                           // Default model
    baseUrl = "https://generativelanguage.googleapis.com",   // Custom endpoint
    apiVersion = "v1beta",
    enableLogging = true
)
```

### OpenAI (and Compatible APIs)

```kotlin
// OpenAI
OpenAiConnector(
    apiKey = "sk-...",
    modelId = "gpt-4o",                   // Default model
    baseUrl = "https://api.openai.com/v1", // Default endpoint
    enableLogging = true
)

// DeepSeek
OpenAiConnector(
    apiKey = "sk-...",
    modelId = "deepseek-chat",
    baseUrl = "https://api.deepseek.com/v1"
)

// Qwen (Alibaba)
OpenAiConnector(
    apiKey = "sk-...",
    modelId = "qwen-max-latest",
    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
)

// GLM (Zhipu AI)
OpenAiConnector(
    apiKey = "...",
    modelId = "glm-4",
    baseUrl = "https://open.bigmodel.cn/api/paas/v4/"
)

// Moonshot (Kimi)
OpenAiConnector(
    apiKey = "sk-...",
    modelId = "kimi-k2.5",
    baseUrl = "https://api.moonshot.cn/v1"
)
```

---

## Platform Capabilities

The `agent-engine-platform` module provides cross-platform device and system capabilities that are automatically registered as AI-callable tools.

```kotlin
val engine = AgentEngine.builder()
    .tools {
        register(PlatformToolProvider())  // Auto-detects platform
    }
    .build()

// The AI can now call these tools:
// get_device_info, get_screen_info, get_app_info,
// get_battery_level, get_network_status,
// get_memory_info, get_storage_info,
// get_clipboard_text, set_clipboard_text,
// open_url, get_locale, get_timezone
```

| Tool | Description |
|------|-------------|
| `get_device_info` | Device model, manufacturer, OS version, CPU architecture |
| `get_screen_info` | Screen resolution, density, logical dimensions |
| `get_app_info` | Package name, version name, version code |
| `get_battery_level` | Battery percentage and charging state |
| `get_network_status` | WiFi / Cellular / Ethernet / Disconnected |
| `get_memory_info` | Used / free / total memory in MB |
| `get_storage_info` | Used / available / total storage in MB |
| `get_clipboard_text` | Read clipboard content |
| `set_clipboard_text` | Write text to clipboard |
| `open_url` | Open URL in browser or deep link |
| `get_locale` | Language, country, display name |
| `get_timezone` | Current timezone identifier |

---

## MCP Protocol

### As MCP Server (Expose App Tools to External Clients)

```kotlin
val transport = StdioTransport()
val server = McpServer.builder()
    .name("my-agent")
    .version("1.0.0")
    .toolRegistry(engine.toolRegistry)
    .transport(transport)
    .build()
server.start(coroutineScope)

// SSE Transport -- see server/ module for Ktor integration
```

### As MCP Client (Connect to External MCP Servers)

```kotlin
val transport = StdioTransport()
val client = McpClient(transport)
client.connect(coroutineScope)

val mcpProvider = McpToolProvider(client, toolNamePrefix = "mcp")
mcpProvider.refresh()  // Fetch remote tool list

engine.registerProvider(mcpProvider)  // Merge into local ToolRegistry
```

---

## Running the Demo

The demo app includes a mock LLM (no API key required) with 5 sample tools, plus real LLM connector configurations for live testing.

```bash
# Desktop (JVM)
./gradlew :composeApp:run

# Android
./gradlew :composeApp:assembleDebug

# iOS -- open iosApp/ in Xcode
```

## Running the MCP Server

```bash
./gradlew :server:run
```

Server endpoints:
- `GET /mcp/sse` -- SSE connection
- `POST /mcp/message?sessionId=xxx` -- JSON-RPC messages

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Networking | Ktor Client / Server (KMP) |
| Serialization | kotlinx.serialization |
| Async | kotlinx.coroutines + Flow |
| MCP Protocol | JSON-RPC 2.0 (custom implementation) |
| Annotation Processing | KSP |
| UI (Demo) | Compose Multiplatform |

## Project Structure

```
AgentEngine/
+-- agent-engine-core/          # Core: Tool system, Agent Runtime, MCP, LLM, Session
|   +-- tool/                   #   Tool definition, registration, DSL, execution
|   +-- agent/                  #   Agent Runtime (conversation loop + tool calling)
|   +-- llm/                    #   LlmConnector interface, request/response models
|   +-- mcp/                    #   MCP protocol (Server / Client / Transport / JSON-RPC)
|   +-- session/                #   Session management
+-- agent-engine-llm-anthropic/ # Anthropic Claude connector
+-- agent-engine-llm-gemini/    # Google Gemini connector
+-- agent-engine-llm-openai/    # OpenAI connector (3rd-party compatible)
+-- agent-engine-platform/      # Platform capabilities (expect/actual)
+-- agent-engine-ksp/           # @AgentTool annotation processor
+-- composeApp/                 # Demo app (Compose Multiplatform)
+-- server/                     # MCP Server (Ktor)
+-- shared/                     # Shared utilities
```

---

## License

TBD

---

# 中文文档

## 概述

AgentEngine 是一个 Kotlin Multiplatform AI Agent 框架，让 Android、iOS 和桌面端应用能够将已有的业务功能和系统能力以标准化方式暴露给 AI 大模型调用。AI 模型根据用户意图自动决定何时调用哪些工具，AgentEngine 自动处理整个编排循环。

### 核心特性

- **Kotlin Multiplatform** -- 核心逻辑 100% 在 `commonMain`，支持 Android / iOS / Desktop (JVM)
- **三种 Tool 注册方式** -- DSL 构建器、`@AgentTool` 注解 (KSP 编译期生成)、`ToolProvider` 接口
- **多模型支持** -- 可插拔的 `LlmConnector` 抽象层，内置 OpenAI、Anthropic Claude、Google Gemini 三大连接器；支持运行时动态切换
- **第三方 API 兼容** -- `OpenAiConnector` 支持自定义 `baseUrl`，可直接对接 DeepSeek、智谱 GLM、通义千问、Moonshot 等 OpenAI 兼容 API
- **MCP 协议** -- 内置 MCP Server 和 Client，支持 Stdio / SSE 传输
- **流式对话** -- 基于 `Flow` 的 SSE 流式响应，实时输出 token
- **权限管控** -- Tool 级别权限控制：`ALLOWED`（静默执行）/ `REQUIRES_CONFIRMATION`（需用户确认）/ `BLOCKED`（禁止）
- **平台能力** -- 跨平台设备/系统工具，通过 `expect`/`actual` 自动注册
- **零侵入接入** -- 注册 Tool 即可，无需修改已有业务代码

### 模块结构

| 模块 | 说明 |
|------|------|
| `agent-engine-core` | 核心框架：Tool 系统、Agent Runtime、MCP 协议、LLM 抽象层、Session 管理 |
| `agent-engine-llm-anthropic` | Anthropic Claude 连接器（Messages API + SSE 流式） |
| `agent-engine-llm-gemini` | Google Gemini 连接器（generateContent + SSE 流式 + Function Calling） |
| `agent-engine-llm-openai` | OpenAI 连接器（Chat Completions + SSE 流式 + Function Calling；兼容第三方中转） |
| `agent-engine-platform` | 平台能力抽象（`expect`/`actual`），自动注册为 Tool |
| `agent-engine-ksp` | KSP 注解处理器，`@AgentTool` 编译期代码生成 |
| `composeApp` | 演示 App（Compose Multiplatform） |
| `server` | MCP Server 独立部署（Ktor） |

---

## 快速开始

### 1. 添加依赖

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.alpha:agent-engine-core:<version>")
            // 按需选择 LLM 连接器：
            implementation("com.alpha:agent-engine-llm-openai:<version>")
            implementation("com.alpha:agent-engine-llm-anthropic:<version>")
            implementation("com.alpha:agent-engine-llm-gemini:<version>")
            // 可选：平台能力
            implementation("com.alpha:agent-engine-platform:<version>")
        }
    }
}

// 可选：注解模式
plugins {
    id("com.google.devtools.ksp")
}
dependencies {
    ksp("com.alpha:agent-engine-ksp:<version>")
}
```

### 2. 初始化 AgentEngine

```kotlin
val engine = AgentEngine.builder()
    // 注册一个或多个 LLM 连接器（第一个为默认）：
    .llm("openai", OpenAiConnector(apiKey = "sk-..."))
    .llm("claude", AnthropicConnector(apiKey = "sk-ant-..."))
    .llm("gemini", GeminiConnector(apiKey = "AIza..."))
    // 或使用 OpenAI 兼容的第三方 API：
    // .llm("deepseek", OpenAiConnector(
    //     apiKey = "sk-...",
    //     modelId = "deepseek-chat",
    //     baseUrl = "https://api.deepseek.com/v1"
    // ))
    .systemPrompt("你是一个智能助手。")
    .tools {
        tool("get_battery") {
            description = "获取设备电量"
            execute { ToolResult.text("${getBatteryLevel()}%") }
        }

        tool("search_contacts") {
            description = "搜索通讯录联系人"
            parameter("query", StringType, "搜索关键词")
            execute { params ->
                val results = contactsRepo.search(params.getString("query"))
                ToolResult.text(results.joinToString("\n"))
            }
        }

        tool("send_message") {
            description = "发送消息"
            parameter("to", StringType, "收件人")
            parameter("content", StringType, "消息内容")
            permission = Permission.REQUIRES_CONFIRMATION
            execute { params ->
                messagingService.send(
                    params.getString("to"),
                    params.getString("content")
                )
                ToolResult.text("发送成功")
            }
        }
    }
    .onConfirmationRequired { toolCall ->
        showConfirmDialog(toolCall)  // 敏感操作弹窗确认
    }
    .build()
```

### 3. 对话

```kotlin
// 单轮对话（自动处理 Tool Calling 循环）
val result = engine.chat("帮我查一下张三的联系方式")
println(result)

// 带会话上下文
val session = engine.createSession()
engine.chat(session, "你好")
engine.chat(session, "我刚才说了什么？")  // 保持上下文

// 流式对话
engine.chatStream(session, "查一下最近的订单").collect { event ->
    when (event) {
        is AgentEvent.TextDelta -> print(event.text)
        is AgentEvent.ToolCallStart -> println("\n[调用工具: ${event.toolName}]")
        is AgentEvent.ToolCallComplete -> println("[结果: ${event.result}]")
        is AgentEvent.ConfirmationRequired -> event.onResult(true)
        is AgentEvent.Done -> println("\n--- 完成 ---")
        is AgentEvent.Error -> println("错误: ${event.throwable.message}")
    }
}
```

### 4. 运行时切换 LLM

```kotlin
// 查看可用连接器
engine.availableLlms  // ["openai", "claude", "gemini"]
engine.currentLlmName // "openai"（第一个注册的为默认）

// 切换到其他模型
engine.switchLlm("claude")

// 动态添加/移除连接器
engine.addLlm("qwen", OpenAiConnector(
    apiKey = "sk-...",
    modelId = "qwen-max-latest",
    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
))
engine.removeLlm("openai")
```

---

## Tool 注册方式

### 方式一：DSL

```kotlin
engine.tools {
    tool("calculate") {
        description = "计算数学表达式"
        parameter("expression", StringType, "数学表达式，如 '2 + 3 * 4'")
        execute { params ->
            val result = eval(params.getString("expression"))
            ToolResult.text("$result")
        }
    }
}
```

### 方式二：@AgentTool 注解 (KSP)

```kotlin
class OrderService {
    @AgentTool(name = "search_orders", description = "搜索历史订单")
    suspend fun searchOrders(
        @Param("搜索关键词") query: String,
        @Param("订单状态", required = false) status: String? = null
    ): List<Order> {
        return orderRepo.search(query, status)
    }
}

// KSP 自动生成 OrderServiceToolProvider : ToolProvider
val engine = AgentEngine.builder()
    .tools { register(OrderServiceToolProvider(orderService)) }
    .build()
```

### 方式三：ToolProvider 接口

```kotlin
class PaymentToolProvider : ToolProvider {
    override fun provideTools(): List<ToolDefinition> = listOf(
        tool("check_balance") {
            description = "查询账户余额"
            execute { ToolResult.text("${wallet.balance} 元") }
        },
        tool("transfer") {
            description = "转账"
            parameter("to", StringType, "收款人")
            parameter("amount", NumberType, "金额")
            permission = Permission.REQUIRES_CONFIRMATION
            execute { params -> /* ... */ }
        }
    )
}

engine.registerProvider(PaymentToolProvider())
```

---

## LLM 连接器

### Anthropic Claude

```kotlin
AnthropicConnector(
    apiKey = "sk-ant-...",
    modelId = "claude-sonnet-4-6",          // 默认模型
    baseUrl = "https://api.anthropic.com",   // 可自定义端点
    apiVersion = "2023-06-01",
    enableLogging = false                    // 调试日志
)
```

### Google Gemini

```kotlin
GeminiConnector(
    apiKey = "AIza...",
    modelId = "gemini-2.5-flash",                           // 默认模型
    baseUrl = "https://generativelanguage.googleapis.com",   // 可自定义端点
    apiVersion = "v1beta",
    enableLogging = true
)
```

### OpenAI 及兼容 API

```kotlin
// OpenAI 官方
OpenAiConnector(
    apiKey = "sk-...",
    modelId = "gpt-4o",
    baseUrl = "https://api.openai.com/v1"
)

// DeepSeek
OpenAiConnector(apiKey = "sk-...", modelId = "deepseek-chat",
    baseUrl = "https://api.deepseek.com/v1")

// 通义千问 (Qwen)
OpenAiConnector(apiKey = "sk-...", modelId = "qwen-max-latest",
    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1")

// 智谱 GLM
OpenAiConnector(apiKey = "...", modelId = "glm-4",
    baseUrl = "https://open.bigmodel.cn/api/paas/v4/")

// Moonshot (Kimi)
OpenAiConnector(apiKey = "sk-...", modelId = "kimi-k2.5",
    baseUrl = "https://api.moonshot.cn/v1")
```

---

## 平台能力集成

`agent-engine-platform` 提供跨平台设备/系统能力，自动注册为 AI 可调用的 Tool：

```kotlin
val engine = AgentEngine.builder()
    .tools {
        register(PlatformToolProvider())  // 自动检测当前平台
    }
    .build()

// AI 现在可以调用以下工具：
// get_device_info, get_screen_info, get_app_info,
// get_battery_level, get_network_status,
// get_memory_info, get_storage_info,
// get_clipboard_text, set_clipboard_text,
// open_url, get_locale, get_timezone
```

| 工具 | 说明 |
|------|------|
| `get_device_info` | 设备型号、制造商、系统版本、CPU 架构 |
| `get_screen_info` | 屏幕分辨率、像素密度、逻辑尺寸 |
| `get_app_info` | 包名、版本号 |
| `get_battery_level` | 电池电量及充电状态 |
| `get_network_status` | WiFi / 蜂窝 / 以太网 / 断开 |
| `get_memory_info` | 已用/可用/总内存 (MB) |
| `get_storage_info` | 已用/可用/总存储 (MB) |
| `get_clipboard_text` | 读取剪贴板内容 |
| `set_clipboard_text` | 写入剪贴板 |
| `open_url` | 打开浏览器或深度链接 |
| `get_locale` | 语言、国家/地区 |
| `get_timezone` | 当前时区 |

---

## MCP 协议

### 作为 MCP Server（暴露 App 工具给外部客户端）

```kotlin
val transport = StdioTransport()
val server = McpServer.builder()
    .name("my-agent")
    .version("1.0.0")
    .toolRegistry(engine.toolRegistry)
    .transport(transport)
    .build()
server.start(coroutineScope)

// SSE Transport -- 参见 server/ 模块
```

### 作为 MCP Client（连接外部 MCP Server 获取远程工具）

```kotlin
val transport = StdioTransport()
val client = McpClient(transport)
client.connect(coroutineScope)

val mcpProvider = McpToolProvider(client, toolNamePrefix = "mcp")
mcpProvider.refresh()  // 获取远程工具列表

engine.registerProvider(mcpProvider)  // 远程工具合并到本地 ToolRegistry
```

---

## 运行演示

Demo App 内置模拟 LLM（无需 API Key），包含 5 个示例工具，同时可配置真实 LLM 连接器进行实际测试。

```bash
# Desktop (JVM)
./gradlew :composeApp:run

# Android
./gradlew :composeApp:assembleDebug

# iOS -- 在 Xcode 中打开 iosApp/ 目录
```

## 运行 MCP Server

```bash
./gradlew :server:run
```

服务端点：
- `GET /mcp/sse` -- SSE 连接
- `POST /mcp/message?sessionId=xxx` -- JSON-RPC 消息

---

## 技术栈

| 层 | 技术 |
|----|------|
| 网络 | Ktor Client / Server (KMP) |
| 序列化 | kotlinx.serialization |
| 异步 | kotlinx.coroutines + Flow |
| MCP 协议 | JSON-RPC 2.0（自研实现） |
| 注解处理 | KSP |
| UI (Demo) | Compose Multiplatform |

## 项目结构

```
AgentEngine/
+-- agent-engine-core/          # 核心模块：Tool 系统、Agent Runtime、MCP、LLM、Session
|   +-- tool/                   #   Tool 定义、注册、DSL、执行
|   +-- agent/                  #   Agent Runtime（对话循环 + Tool Calling）
|   +-- llm/                    #   LlmConnector 接口、请求/响应模型
|   +-- mcp/                    #   MCP 协议（Server / Client / Transport / JSON-RPC）
|   +-- session/                #   会话管理
+-- agent-engine-llm-anthropic/ # Anthropic Claude 连接器
+-- agent-engine-llm-gemini/    # Google Gemini 连接器
+-- agent-engine-llm-openai/    # OpenAI 连接器（兼容第三方）
+-- agent-engine-platform/      # 平台能力（expect/actual）
+-- agent-engine-ksp/           # @AgentTool 注解处理器
+-- composeApp/                 # 演示 App（Compose Multiplatform）
+-- server/                     # MCP Server（Ktor）
+-- shared/                     # 共享模块
```

---

## License

TBD
