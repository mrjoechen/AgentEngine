# AgentEngine 架构设计文档

## 1. 项目愿景

AgentEngine 是一个基于 Kotlin Multiplatform 的 AI Agent 框架，让移动端和桌面端 App 能够将已有的业务功能、系统能力以标准化的方式暴露给 AI 大模型调用。对外支持 MCP（Model Context Protocol）协议，对内提供极简的 Tool 注册 API。

**一句话定位**：App 的 AI 能力中间层 —— 连接 App 功能与 AI 大模型的桥梁。

```
┌─────────────────────────────────────────────────────────────┐
│                      Host App                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   AgentEngine                         │  │
│  │                                                       │  │
│  │  ┌─────────┐  ┌──────────┐  ┌──────────────────────┐ │  │
│  │  │  Tool   │  │  Agent   │  │    MCP Server        │ │  │
│  │  │Registry │→ │  Runtime │← │  (stdio / SSE)       │ │  │
│  │  └─────────┘  └──────────┘  └──────────────────────┘ │  │
│  │       ↑            ↑                                  │  │
│  │  ┌─────────┐  ┌──────────┐                            │  │
│  │  │Platform │  │  LLM     │                            │  │
│  │  │Provider │  │ Connector│                            │  │
│  │  └─────────┘  └──────────┘                            │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 设计原则

| 原则 | 说明 |
|------|------|
| **KMP-First** | 核心逻辑 100% 在 commonMain，平台相关能力通过 `expect/actual` 注入 |
| **零侵入接入** | 开发者只需注册 Tool，无需修改已有业务代码 |
| **协议标准化** | 对外完整支持 MCP 协议，也可作为 MCP Client 连接外部 MCP Server |
| **模型无关** | 不绑定特定 LLM，通过 Connector 抽象适配 Claude / OpenAI / 本地模型等 |
| **类型安全** | 利用 Kotlin DSL + 注解，Tool 定义编译期可检查 |
| **权限可控** | 内置权限管控层，敏感操作需用户确认 |

---

## 3. 模块划分

```
AgentEngine/
├── agent-engine-core/          # 核心模块（commonMain）
│   ├── tool/                   # Tool 定义、注册、调度
│   ├── agent/                  # Agent 运行时（对话循环、tool 调度）
│   ├── mcp/                    # MCP 协议实现（Server + Client）
│   ├── llm/                    # LLM Connector 抽象
│   ├── permission/             # 权限管控
│   └── session/                # 会话管理
│
├── agent-engine-platform/      # 平台能力抽象（expect/actual）
│   ├── commonMain/             # 平台能力接口定义
│   ├── androidMain/            # Android 系统能力实现
│   ├── iosMain/                # iOS 系统能力实现
│   └── jvmMain/                # Desktop 系统能力实现
│
├── agent-engine-llm-anthropic/ # Claude/Anthropic Connector
├── agent-engine-llm-gemini/    # Google Gemini Connector
├── agent-engine-llm-openai/    # OpenAI Connector（可选）
│
├── composeApp/                 # Demo App（Compose Multiplatform）
├── server/                     # MCP Server 独立部署模式（Ktor）
└── shared/                     # 原有 shared 模块
```

### 3.1 模块依赖关系

```
agent-engine-llm-anthropic ──→ agent-engine-core
agent-engine-llm-gemini    ──→ agent-engine-core
agent-engine-llm-openai    ──→ agent-engine-core
agent-engine-platform      ──→ agent-engine-core
composeApp                 ──→ agent-engine-core + platform + llm-anthropic
server                     ──→ agent-engine-core + llm-anthropic
```

---

## 4. 核心 API 设计

### 4.1 Tool 注册（开发者面向的 API）

目标：让开发者用最少的代码注册一个 Tool。

#### 方式一：DSL 注册

```kotlin
val engine = AgentEngine.builder()
    .llm(AnthropicConnector(apiKey = "..."))
    .tools {
        // 最简注册方式
        tool("search_contacts") {
            description = "搜索通讯录联系人"
            parameter("query", StringType, "搜索关键词", required = true)
            execute { params ->
                val query = params.getString("query")
                contactsRepo.search(query).toToolResult()
            }
        }

        tool("get_battery_level") {
            description = "获取当前设备电量百分比"
            // 无参数的 tool
            execute {
                ToolResult.text("${platform.batteryLevel}%")
            }
        }

        tool("send_message") {
            description = "向指定联系人发送消息"
            parameter("contact_id", StringType, "联系人ID", required = true)
            parameter("content", StringType, "消息内容", required = true)
            permission = Permission.REQUIRES_CONFIRMATION  // 敏感操作
            execute { params ->
                messagingService.send(
                    params.getString("contact_id"),
                    params.getString("content")
                )
                ToolResult.text("消息发送成功")
            }
        }
    }
    .build()
```

#### 方式二：注解注册（编译期生成 Tool 定义）

```kotlin
@AgentTool(
    name = "search_orders",
    description = "搜索用户的历史订单"
)
suspend fun searchOrders(
    @Param("搜索关键词") query: String,
    @Param("订单状态筛选", required = false) status: OrderStatus? = null
): List<Order> {
    return orderRepo.search(query, status)
}

// 批量注册带注解的方法
val engine = AgentEngine.builder()
    .llm(connector)
    .tools {
        register(OrderService())   // 自动扫描 @AgentTool 方法
        register(ContactService())
    }
    .build()
```

#### 方式三：ToolProvider 接口（适合模块化 App）

```kotlin
// 每个业务模块实现自己的 ToolProvider
class PaymentToolProvider : ToolProvider {
    override fun provideTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "check_balance",
            description = "查询账户余额",
            parameters = emptyList(),
            permission = Permission.ALLOWED,
            handler = { ToolResult.text("${wallet.balance} 元") }
        ),
        ToolDefinition(
            name = "transfer",
            description = "转账给指定用户",
            parameters = listOf(
                ParamDef("to_user", StringType, "收款人", required = true),
                ParamDef("amount", NumberType, "金额(元)", required = true),
            ),
            permission = Permission.REQUIRES_CONFIRMATION,
            handler = { params -> /* ... */ }
        )
    )
}

// App 初始化时注册
engine.registerProvider(PaymentToolProvider())
engine.registerProvider(SocialToolProvider())
```

### 4.2 Agent 对话接口

```kotlin
// 单轮对话（自动处理 tool calling 循环）
val response = engine.chat("帮我查一下最近的外卖订单")
// response.text = "您最近的外卖订单是：..."

// 流式对话
engine.chatStream("帮我设个明天早上8点的闹钟") { event ->
    when (event) {
        is AgentEvent.TextDelta    -> updateUI(event.text)
        is AgentEvent.ToolCall     -> showToolCallIndicator(event.toolName)
        is AgentEvent.ToolResult   -> hideToolCallIndicator()
        is AgentEvent.Confirmation -> showConfirmDialog(event.tool, event.params)
        is AgentEvent.Done         -> onComplete(event.fullText)
        is AgentEvent.Error        -> onError(event.throwable)
    }
}

// 会话管理
val session = engine.createSession()  // 新建对话
session.chat("你好")
session.chat("我刚才说了什么？")      // 保持上下文
session.close()
```

---

## 5. 核心组件详细设计

### 5.1 Tool 系统

```
┌─────────────────────────────────────────┐
│              ToolRegistry               │
│  ┌───────────┐  ┌───────────────────┐   │
│  │ToolDef Map│  │SchemaGenerator    │   │
│  │name → def │  │Kotlin → JSONSchema│   │
│  └───────────┘  └───────────────────┘   │
│  ┌───────────────────────────────────┐  │
│  │         ToolDispatcher            │  │
│  │  name → validate → permission     │  │
│  │       → execute → serialize       │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

**关键接口：**

```kotlin
// Tool 定义
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ParamDef>,
    val permission: Permission = Permission.ALLOWED,
    val handler: ToolHandler
)

// Tool 参数
data class ParamDef(
    val name: String,
    val type: ParamType,          // StringType, NumberType, BooleanType, ObjectType, ArrayType, EnumType
    val description: String,
    val required: Boolean = true,
    val default: Any? = null
)

// Tool 执行结果
sealed class ToolResult {
    data class Text(val content: String) : ToolResult()
    data class Image(val base64: String, val mimeType: String) : ToolResult()
    data class Json(val data: JsonObject) : ToolResult()
    data class Error(val message: String, val isRetryable: Boolean = false) : ToolResult()

    companion object {
        fun text(content: String) = Text(content)
        fun json(data: JsonObject) = Json(data)
        fun error(message: String) = Error(message)
    }
}

// Tool 执行器
fun interface ToolHandler {
    suspend fun execute(params: ToolParams): ToolResult
}

// 权限级别
enum class Permission {
    ALLOWED,                // 静默执行（只读查询类）
    REQUIRES_CONFIRMATION,  // 需用户确认（写入/发送类）
    BLOCKED                 // 禁止执行
}
```

### 5.2 LLM Connector 抽象

```kotlin
interface LlmConnector {
    /** 模型标识 */
    val modelId: String

    /** 单轮调用（含 tool definitions） */
    suspend fun createMessage(request: LlmRequest): LlmResponse

    /** 流式调用 */
    fun createMessageStream(request: LlmRequest): Flow<LlmStreamEvent>
}

data class LlmRequest(
    val messages: List<Message>,
    val tools: List<ToolSchema>,          // 从 ToolRegistry 自动生成
    val systemPrompt: String? = null,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7
)

sealed class LlmResponse {
    data class Text(val content: String) : LlmResponse()
    data class ToolUse(val calls: List<ToolCall>) : LlmResponse()
    data class Mixed(val blocks: List<ContentBlock>) : LlmResponse()
}

data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject
)
```

**Anthropic 实现示例：**

```kotlin
class AnthropicConnector(
    private val apiKey: String,
    override val modelId: String = "claude-sonnet-4-6"
) : LlmConnector {

    private val client = HttpClient { /* Ktor config */ }

    override suspend fun createMessage(request: LlmRequest): LlmResponse {
        // 将 LlmRequest 转换为 Anthropic Messages API 格式
        // POST https://api.anthropic.com/v1/messages
    }

    override fun createMessageStream(request: LlmRequest): Flow<LlmStreamEvent> {
        // SSE stream 处理
    }
}
```

### 5.3 Agent Runtime（核心调度循环）

```kotlin
class AgentRuntime(
    private val connector: LlmConnector,
    private val toolRegistry: ToolRegistry,
    private val permissionHandler: PermissionHandler
) {
    /**
     * 执行完整的 Agent 对话循环：
     * User → LLM → [ToolUse → Execute → Result → LLM]* → FinalText
     */
    suspend fun run(session: Session, userMessage: String): AgentResponse {
        session.addUserMessage(userMessage)

        while (true) {
            val llmResponse = connector.createMessage(
                LlmRequest(
                    messages = session.messages,
                    tools = toolRegistry.schemas(),
                    systemPrompt = session.systemPrompt
                )
            )

            when (llmResponse) {
                is LlmResponse.Text -> {
                    session.addAssistantMessage(llmResponse.content)
                    return AgentResponse.Text(llmResponse.content)
                }
                is LlmResponse.ToolUse -> {
                    session.addAssistantToolUse(llmResponse)

                    val results = llmResponse.calls.map { call ->
                        val tool = toolRegistry.get(call.name)
                            ?: return AgentResponse.Error("Unknown tool: ${call.name}")

                        // 权限检查
                        if (tool.permission == Permission.REQUIRES_CONFIRMATION) {
                            val approved = permissionHandler.requestConfirmation(call)
                            if (!approved) {
                                return@map ToolCallResult(call.id, ToolResult.error("用户拒绝执行"))
                            }
                        }

                        // 执行 tool
                        val result = try {
                            tool.handler.execute(ToolParams(call.input))
                        } catch (e: Exception) {
                            ToolResult.error("Tool 执行失败: ${e.message}")
                        }
                        ToolCallResult(call.id, result)
                    }

                    session.addToolResults(results)
                    // 继续循环，把结果送回 LLM
                }
                is LlmResponse.Mixed -> { /* 处理混合内容 */ }
            }
        }
    }
}
```

### 5.4 MCP 协议层

AgentEngine 同时扮演 **MCP Server**（暴露 App 能力）和 **MCP Client**（连接外部工具）。

```
                    MCP Client (外部)
                         │
                    JSON-RPC / SSE
                         │
┌────────────────────────▼─────────────────────────┐
│                 MCP Server Layer                  │
│  ┌──────────────┐  ┌─────────────────────────┐   │
│  │ Transport    │  │ Protocol Handler         │   │
│  │ ├─ Stdio     │  │ ├─ initialize            │   │
│  │ ├─ SSE       │  │ ├─ tools/list            │   │
│  │ └─ WebSocket │  │ ├─ tools/call            │   │
│  └──────────────┘  │ ├─ resources/list        │   │
│                    │ ├─ resources/read         │   │
│                    │ └─ prompts/list           │   │
│                    └─────────────────────────┘   │
│                          │                       │
│                    ToolRegistry                   │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│                 MCP Client Layer                  │
│  连接外部 MCP Server，将远程 Tool 合并到            │
│  ToolRegistry，对 Agent Runtime 透明              │
└──────────────────────────────────────────────────┘
```

**MCP Server 关键实现：**

```kotlin
class McpServer(
    private val toolRegistry: ToolRegistry,
    private val transport: McpTransport
) {
    suspend fun start() {
        transport.onRequest { request ->
            when (request.method) {
                "initialize" -> handleInitialize(request)
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(request)
                "resources/list" -> handleResourcesList()
                "resources/read" -> handleResourcesRead(request)
                else -> McpError.methodNotFound(request.method)
            }
        }
    }

    private fun handleToolsList(): McpResponse {
        // 直接从 ToolRegistry 转换为 MCP tools/list 响应格式
        val tools = toolRegistry.all().map { it.toMcpToolSchema() }
        return McpResponse.success(tools)
    }

    private suspend fun handleToolsCall(request: McpRequest): McpResponse {
        val name = request.params["name"] as String
        val arguments = request.params["arguments"] as JsonObject
        val result = toolRegistry.execute(name, arguments)
        return result.toMcpResponse()
    }
}
```

**支持的 Transport：**

| Transport | 场景 | 平台 |
|-----------|------|------|
| **Stdio** | App 内嵌，进程内通信 | All |
| **SSE** | Web/远程连接 | Server / Desktop |
| **WebSocket** | 双向实时通信 | All |

### 5.5 Platform Provider（平台能力抽象）

```kotlin
// commonMain - 接口定义
interface PlatformCapabilities {
    /** 设备信息 */
    suspend fun getDeviceInfo(): DeviceInfo
    suspend fun getBatteryLevel(): Int
    suspend fun getNetworkStatus(): NetworkStatus

    /** 系统功能 */
    suspend fun setAlarm(time: Instant, label: String): Boolean
    suspend fun openUrl(url: String): Boolean
    suspend fun shareContent(text: String, mimeType: String): Boolean

    /** 剪贴板 */
    suspend fun getClipboardText(): String?
    suspend fun setClipboardText(text: String)

    /** 通知 */
    suspend fun showNotification(title: String, body: String)
}

// androidMain
class AndroidPlatformCapabilities(
    private val context: Context
) : PlatformCapabilities {
    override suspend fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    // ...
}

// iosMain
class IosPlatformCapabilities : PlatformCapabilities {
    override suspend fun getBatteryLevel(): Int {
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        return (UIDevice.currentDevice.batteryLevel * 100).toInt()
    }
    // ...
}
```

**自动注册为 Tool：**

```kotlin
// PlatformToolProvider 将 PlatformCapabilities 自动转为 Tool
class PlatformToolProvider(
    private val capabilities: PlatformCapabilities
) : ToolProvider {
    override fun provideTools() = listOf(
        tool("get_device_info") {
            description = "获取设备信息（型号、系统版本等）"
            execute { capabilities.getDeviceInfo().toToolResult() }
        },
        tool("get_battery_level") {
            description = "获取设备电量百分比"
            execute { ToolResult.text("${capabilities.getBatteryLevel()}%") }
        },
        // ... 更多系统能力自动映射
    )
}
```

---

## 6. 完整接入示例

### Android App 接入

```kotlin
class MyApplication : Application() {
    lateinit var agentEngine: AgentEngine

    override fun onCreate() {
        super.onCreate()

        agentEngine = AgentEngine.builder()
            // 配置 LLM
            .llm(AnthropicConnector(apiKey = BuildConfig.CLAUDE_API_KEY))
            // 注册平台能力（自动转为 Tool）
            .platformCapabilities(AndroidPlatformCapabilities(this))
            // 注册业务功能
            .tools {
                register(OrderToolProvider())
                register(ContactToolProvider())
                register(NavigationToolProvider(navController))
            }
            // 权限确认回调
            .onConfirmationRequired { toolCall ->
                // 弹出确认对话框，返回用户是否同意
                showConfirmationDialog(toolCall)
            }
            // 系统提示词
            .systemPrompt("你是一个购物助手，帮助用户管理订单和查询商品。")
            // 启用 MCP Server（可选）
            .enableMcpServer(McpServerConfig(transport = Transport.SSE, port = 8080))
            .build()
    }
}

// 在 ViewModel 中使用
class ChatViewModel(private val engine: AgentEngine) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    fun sendMessage(text: String) {
        viewModelScope.launch {
            _messages.update { it + ChatMessage.User(text) }

            engine.chatStream(text) { event ->
                when (event) {
                    is AgentEvent.TextDelta -> {
                        _messages.update { msgs ->
                            val last = msgs.lastOrNull()
                            if (last is ChatMessage.Assistant) {
                                msgs.dropLast(1) + last.copy(text = last.text + event.text)
                            } else {
                                msgs + ChatMessage.Assistant(event.text)
                            }
                        }
                    }
                    is AgentEvent.ToolCall -> {
                        _messages.update { it + ChatMessage.ToolCall(event.toolName) }
                    }
                    is AgentEvent.Error -> { /* handle error */ }
                    else -> {}
                }
            }
        }
    }
}
```

### iOS App 接入

```swift
// Swift 侧通过 KMP 导出的框架调用
let engine = AgentEngineBuilder()
    .llm(connector: AnthropicConnector(apiKey: apiKey))
    .platformCapabilities(IosPlatformCapabilities())
    .tools { registry in
        registry.register(OrderToolProvider())
    }
    .build()

// SwiftUI 中使用
Task {
    let response = try await engine.chat("查一下我最近的订单")
    self.responseText = response.text
}
```

---

## 7. 数据流时序图

### 用户对话 → Tool 调用完整流程

```
User        App UI      AgentRuntime    LlmConnector    ToolRegistry     App Logic
 │            │              │               │               │               │
 │  "查订单"  │              │               │               │               │
 │──────────→│              │               │               │               │
 │            │  chat()      │               │               │               │
 │            │─────────────→│               │               │               │
 │            │              │ createMessage()│               │               │
 │            │              │──────────────→│               │               │
 │            │              │               │──── HTTP ───→ LLM API        │
 │            │              │               │←── tool_use ──│               │
 │            │              │←─ ToolUse ────│               │               │
 │            │              │               │               │               │
 │            │              │ execute("search_orders")      │               │
 │            │              │──────────────────────────────→│               │
 │            │              │               │               │  search()     │
 │            │              │               │               │──────────────→│
 │            │              │               │               │←── results ───│
 │            │              │←───── ToolResult ─────────────│               │
 │            │              │               │               │               │
 │            │              │ createMessage(with results)   │               │
 │            │              │──────────────→│               │               │
 │            │              │               │──── HTTP ───→ LLM API        │
 │            │              │               │←── text ──────│               │
 │            │              │←─ Text ───────│               │               │
 │            │←── response ─│               │               │               │
 │←── 显示 ──│              │               │               │               │
```

---

## 8. MCP 外部接入流程

```
External MCP Client          AgentEngine (MCP Server)          ToolRegistry
       │                              │                             │
       │── initialize ───────────────→│                             │
       │←─ capabilities ─────────────│                             │
       │                              │                             │
       │── tools/list ───────────────→│                             │
       │                              │── getAll() ────────────────→│
       │                              │←─ List<ToolDef> ───────────│
       │←─ tools[] ──────────────────│                             │
       │                              │                             │
       │── tools/call ───────────────→│                             │
       │   {name, arguments}          │── execute(name, args) ────→│
       │                              │                             │──→ App Logic
       │                              │←─ ToolResult ──────────────│←──
       │←─ result ───────────────────│                             │
```

---

## 9. 技术选型

| 层 | 技术 | 说明 |
|----|------|------|
| 网络 | Ktor Client (KMP) | HTTP 请求、SSE 流式、WebSocket |
| 序列化 | kotlinx.serialization | JSON 序列化，跨平台 |
| 异步 | kotlinx.coroutines | 协程，Flow 流式处理 |
| MCP 协议 | 自研 (基于 JSON-RPC 2.0) | 也可考虑集成官方 MCP Kotlin SDK |
| 注解处理 | KSP | 编译期生成 Tool Schema（注解模式） |
| 日志 | Kermit / Napier | KMP 日志库 |
| 测试 | kotlin.test + Turbine | 单元测试 + Flow 测试 |

---

## 10. 开发路线图

### Phase 1：核心框架（MVP） ✅
- [x] Tool 定义和注册（DSL 模式）
- [x] ToolRegistry 实现
- [x] LlmConnector 抽象 + Anthropic 实现
- [x] AgentRuntime 对话循环
- [x] Session 管理
- [x] 基础权限控制

### Phase 2：平台能力 + MCP ✅
- [x] PlatformCapabilities expect/actual
- [x] Android / iOS / Desktop 平台实现
- [x] MCP Server（Stdio + SSE Transport）
- [x] MCP 协议完整实现（tools/list, tools/call, resources, prompts）
- [x] MCP Client + McpToolProvider（连接外部 MCP Server，自动合并远程 Tool）
- [x] JSON-RPC 2.0 完整实现
- [x] InMemoryTransport（测试/进程内通信）

### Phase 3：增强功能 ✅
- [x] 注解模式 @AgentTool/@Param + KSP 代码生成（agent-engine-ksp）
- [x] 流式对话支持（Anthropic SSE streaming + AgentRuntime.runStream + AgentEngine.chatStream）
- [x] Google Gemini Connector（Function Calling + SSE streaming）
- [ ] 多模型 Connector（OpenAI 等）

### Phase 4：生态完善 ✅
- [x] Demo App（Compose Multiplatform，内置模拟 LLM + 5 个示例 Tool）
- [x] README 文档（快速开始、API 示例、模块说明）
- [ ] 发布到 Maven Central
- [ ] 常用系统能力 Tool 预置包
