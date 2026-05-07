# ShopAssistant 项目代码文档

## 一、项目概述

**ShopAssistant** 是一个基于 **Spring Boot 3.5 + Spring AI 1.1** 的智能助手平台，支持多模型（DeepSeek、智谱 GLM）、Agent 自动规划与工具调用、RAG 知识库检索、会话记忆与摘要、SSE 实时推送等核心能力。

| 项目信息 | 内容 |
|----------|------|
| 项目坐标 | `com.xmu:ShopAssistant:0.0.1-SNAPSHOT` |
| Java 版本 | 17 |
| 数据库 | PostgreSQL（pgvector 向量扩展） |
| ORM | MyBatis 3 |
| AI 模型 | DeepSeek Chat、智谱 GLM-4.6 |
| Embedding 模型 | bge-m3（Ollama 本地部署，localhost:11434） |

---

## 二、项目结构

```
com.xmu.ShopAssistant
│
├── ShopAssistantApplication.java          # Spring Boot 启动类
│
├── agent/                                 # Agent 引擎层
│   ├── AgentState.java                    # Agent 状态枚举 (IDLE/PLANNING/THINKING/EXECUTING/FINISHED/ERROR)
│   ├── ShopAi.java                        # Agent 核心 —— ReAct 循环实现
│   ├── ShopAiFactory.java                 # Agent 工厂 —— 装配依赖、加载记忆与配置
│   │
│   ├── examples/                          # Agent 演化原型
│   │   ├── JChatMindV1.java               # V1：基础聊天功能
│   │   └── JChatMindV2.java               # V2：引入 ReAct 工具调用
│   │
│   └── tools/                             # 工具层
│       ├── Tool.java                      # 工具接口
│       ├── ToolType.java                  # 工具类型枚举 (FIXED / OPTIONAL)
│       ├── DirectAnswerTool.java          # 直接回答（已禁用）
│       ├── TerminateTool.java             # 终止 Agent 循环
│       ├── KnowledgeTools.java            # 知识库混合检索工具
│       ├── DataBaseTools.java             # 数据库只读查询工具
│       ├── EmailTools.java                # 异步邮件发送工具
│       ├── FileSystemTools.java           # 文件系统操作工具
│       └── test/                          # 测试用工具
│           ├── CityTool.java
│           ├── DateTool.java
│           └── WeatherTool.java
│
├── config/                                # 配置层
│   ├── AsyncConfig.java                   # 异步线程池配置
│   ├── ChatClientRegistry.java            # ChatClient 注册表 (多模型)
│   ├── CorsConfig.java                    # CORS 跨域配置
│   └── MultiChatClientConfig.java         # 多模型 ChatClient Bean 定义
│
├── controller/                            # REST 控制器
│   ├── AgentController.java               # Agent CRUD
│   ├── ChatSessionController.java         # 会话 CRUD
│   ├── ChatMessageController.java         # 消息 CRUD
│   ├── DocumentController.java            # 文档管理 (含上传)
│   ├── KnowledgeBaseController.java       # 知识库 CRUD
│   ├── RagEvaluationController.java       # RAG 检索评估
│   ├── SseController.java                 # SSE 实时推送连接
│   ├── ToolController.java                # 工具列表查询
│   └── TestController.java                # 健康检查
│
├── converter/                             # 转换器 (DTO <-> Entity <-> VO)
│   ├── AgentConverter.java
│   ├── ChatMessageConverter.java
│   ├── ChatSessionConverter.java
│   ├── ChunkBgeM3Converter.java
│   ├── DocumentConverter.java
│   └── KnowledgeBaseConverter.java
│
├── event/                                 # 事件驱动
│   ├── ChatEvent.java                     # 聊天事件 (agentId, sessionId, userInput)
│   └── listener/ChatEventListener.java    # 异步事件监听 (@Async 创建 Agent 并执行)
│
├── exception/                             # 异常处理
│   ├── BizException.java                  # 业务异常 (code=400)
│   └── GlobalExceptionHandler.java        # 全局异常处理器 (统一响应格式)
│
├── mapper/                                # MyBatis Mapper 接口
│   ├── AgentMapper.java
│   ├── AgentMemoryMapper.java
│   ├── ChatMessageMapper.java
│   ├── ChatSessionMapper.java
│   ├── ChunkBgeM3Mapper.java
│   ├── DocumentMapper.java
│   └── KnowledgeBaseMapper.java
│
├── message/
│   └── SseMessage.java                    # SSE 消息模型 (Type/Payload/Metadata)
│
├── model/
│   ├── common/
│   │   └── ApiResponse.java               # 统一 API 响应 (code/message/data)
│   │
│   ├── entity/                            # 数据库实体
│   │   ├── Agent.java                     # Agent 配置表
│   │   ├── AgentMemory.java               # 长期记忆表
│   │   ├── ChatMessage.java               # 聊天消息表
│   │   ├── ChatSession.java               # 聊天会话表
│   │   ├── ChunkBgeM3.java                # 向量块表 (含embedding)
│   │   ├── Document.java                  # 文档表
│   │   └── KnowledgeBase.java             # 知识库表
│   │
│   ├── dto/                               # 数据传输对象
│   │   ├── AgentDTO.java                  # 含 ModelType 枚举、ChatOptions 内部类
│   │   ├── ChatMessageDTO.java            # 含 RoleType 枚举、MetaData (toolCalls/toolResponse)
│   │   ├── ChatSessionDTO.java            # 含 MetaData (sessionSummary)
│   │   ├── ChunkBgeM3DTO.java
│   │   ├── DocumentDTO.java               # 含 MetaData (filePath)
│   │   └── KnowledgeBaseDTO.java          # 含 MetaData (version)
│   │
│   ├── vo/                                # 视图对象 (返回给前端)
│   │   ├── AgentVO.java
│   │   ├── ChatMessageVO.java
│   │   ├── ChatSessionVO.java
│   │   ├── DocumentVO.java
│   │   └── KnowledgeBaseVO.java
│   │
│   ├── request/                           # 请求体
│   │   ├── CreateAgentRequest.java
│   │   ├── UpdateAgentRequest.java
│   │   ├── CreateChatSessionRequest.java
│   │   ├── UpdateChatSessionRequest.java
│   │   ├── CreateChatMessageRequest.java
│   │   ├── UpdateChatMessageRequest.java
│   │   ├── CreateDocumentRequest.java
│   │   ├── UpdateDocumentRequest.java
│   │   ├── CreateKnowledgeBaseRequest.java
│   │   ├── UpdateKnowledgeBaseRequest.java
│   │   └── RagEvaluationRequest.java
│   │
│   └── response/                          # 响应体
│       ├── CreateAgentResponse.java
│       ├── CreateChatSessionResponse.java
│       ├── CreateChatMessageResponse.java
│       ├── CreateDocumentResponse.java
│       ├── CreateKnowledgeBaseResponse.java
│       ├── GetAgentsResponse.java
│       ├── GetChatSessionResponse.java
│       ├── GetChatSessionsResponse.java
│       ├── GetChatMessagesResponse.java
│       ├── GetDocumentsResponse.java
│       ├── GetKnowledgeBasesResponse.java
│       └── RagEvaluationResponse.java
│
├── service/                               # 服务接口与实现
│   ├── AgentFacadeService                 # Agent CRUD 门面
│   ├── impl/AgentFacadeServiceImpl.java
│   ├── AgentMemoryService                 # 用户画像提取、记忆管理
│   ├── impl/AgentMemoryServiceImpl.java
│   ├── ChatMessageFacadeService           # 消息持久化、事件发布
│   ├── impl/ChatMessageFacadeServiceImpl.java
│   ├── ChatSessionFacadeService           # 会话 CRUD
│   ├── impl/ChatSessionFacadeServiceImpl.java
│   ├── DocumentFacadeService             # 文档上传、解析、分块
│   ├── impl/DocumentFacadeServiceImpl.java
│   ├── DocumentStorageService            # 文件磁盘存储
│   ├── impl/DocumentStorageServiceImpl.java
│   ├── EmailService                      # 邮件发送
│   ├── impl/EmailServiceImpl.java
│   ├── KnowledgeBaseFacadeService        # 知识库 CRUD
│   ├── impl/KnowledgeBaseFacadeServiceImpl.java
│   ├── MarkdownParserService             # Markdown 按标题分块解析
│   ├── impl/MarkdownParserServiceImpl.java
│   ├── PdfService                        # PDF 生成（LLM 回复导出）
│   ├── impl/PdfServiceImpl.java
│   ├── RagService                        # Embedding、向量检索、BM25、RRF 混合
│   ├── impl/RagServiceImpl.java
│   ├── RagEvaluationService              # RAG 评估 (HitRate/Recall/MRR/NDCG)
│   ├── impl/RagEvaluationServiceImpl.java
│   ├── SessionSummaryService             # 会话滚动摘要
│   ├── impl/SessionSummaryServiceImpl.java
│   ├── SseService                        # SSE 连接管理与消息推送
│   ├── impl/SseServiceImpl.java
│   ├── ToolFacadeService                 # 工具注册表
│   └── impl/ToolFacadeServiceImpl.java
│
└── typehandler/
    └── PgVectorTypeHandler.java           # pgvector float[] <-> PostgreSQL vector 类型转换
```

---

## 三、核心业务流程

### 3.1 完整聊天流程

```
┌─────────────────────────────────────────────────────────────────────┐
│  用户发送消息                                                        │
│  POST /api/chat-messages (ChatMessageController)                    │
└──────────────────────────┬──────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ChatMessageFacadeServiceImpl.createChatMessage()                   │
│                                                                     │
│  1. 持久化用户消息 (role=user) 到 chat_message 表                    │
│  2. maybeRememberAgentProfile()                                     │
│     └─ 提取用户画像 → AgentMemoryService.rememberProfileFacts()     │
│  3. maybeRefreshSessionSummary()                                    │
│     └─ 触发会话摘要刷新 → SessionSummaryService                     │
│  4. publishEvent(new ChatEvent(...))                                │
│     └─ 发布异步事件触发 Agent                                       │
└──────────────────────────┬──────────────────────────────────────────┘
                           ▼  (异步 @Async)
┌─────────────────────────────────────────────────────────────────────┐
│  ChatEventListener.handle(ChatEvent)                                │
│                                                                     │
│  shopAiFactory.create(agentId, sessionId)                           │
│    ├─ 加载 Agent 配置 (模型、提示词、工具列表、知识库列表)           │
│    ├─ 加载历史消息 → rebuildProtocolSafeMessages()                  │
│    ├─ 注入会话摘要 → SystemMessage                                  │
│    ├─ 注入长期记忆 → mergeSystemPromptWithLongTermMemory()          │
│    ├─ 解析允许的知识库 → resolveRuntimeKnowledgeBases()             │
│    └─ 解析允许的工具 → buildToolCallbacks()                         │
│                                                                     │
│  shopAi.run() → ReAct Loop                                          │
└──────────────────────────┬──────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ReAct Loop (最多 20 步)                                            │
│                                                                     │
│  while (step < MAX_STEPS && state != FINISHED) {                   │
│      ┌─────────────────────────────────────────┐                   │
│      │  think()                                 │                   │
│      │  LLM 判断：是否需要调用工具？             │                   │
│      │  ┌─ 无工具调用 → state = FINISHED        │                   │
│      │  └─ 有工具调用 → 进入 execute()          │                   │
│      └─────────────────┬───────────────────────┘                   │
│                        ▼                                           │
│      ┌─────────────────────────────────────────┐                   │
│      │  execute()                               │                   │
│      │  1. toolCallingManager.executeToolCalls()│                   │
│      │  2. 持久化工具调用结果到 DB               │                   │
│      │  3. SSE 推送给前端                        │                   │
│      │  4. 检测 terminate → state = FINISHED    │                   │
│      └─────────────────────────────────────────┘                   │
│  }                                                                  │
└──────────────────────────┬──────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  通过 SSE 实时推送所有 pending 消息给前端                            │
│  SseService.send(sessionId, SseMessage)                             │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 RAG 文档上传与检索流程

```
┌─────────────────────────────────────────────────────────────────────┐
│  上传文档 POST /api/documents/upload                                │
│  multipart: kbId + file                                             │
└──────────────────────────┬──────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  DocumentFacadeServiceImpl.uploadDocument()                         │
│                                                                     │
│  1. 创建文档记录到 document 表                                       │
│  2. 保存文件到磁盘 (DocumentStorageService)                          │
│     └─ basePath/kbId/documentId/uuid-filename.ext                   │
│  3. 根据文件类型处理：                                               │
│     ├─ .md / .markdown → MarkdownParserServiceImpl                  │
│     │   └─ flexmark 解析 AST → 按标题分块                           │
│     ├─ .txt → splitTextToSections()                                 │
│     │   └─ 800字符/块, 120字符重叠                                  │
│     └─ .pdf → PDFBox 提取文本 → splitTextToSections()               │
│         └─ 1000字符/块, 150字符重叠                                 │
│  4. 对每个块：                                                      │
│     ├─ ragService.embed(text) → Ollama bge-m3 → float[]            │
│     └─ 写入 chunk_bge_m3 表 (含向量 + kbId + docId + content)      │
└──────────────────────────┬──────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  检索流程 (KnowledgeTools)                                          │
│                                                                     │
│  hybridSearch(kbId, query, limit)                                   │
│    │                                                                │
│    ├─ similaritySearchChunks()  ← 向量检索                          │
│    │   └─ pgvector: embedding <-> #{vectorLiteral}::vector          │
│    │      ORDER BY distance LIMIT (limit * 4)                       │
│    │                                                                │
│    ├─ bm25Search()  ← 全文检索                                      │
│    │   └─ PostgreSQL to_tsvector(content || metadata)               │
│    │      @@ plainto_tsquery(query)                                 │
│    │      ORDER BY ts_rank DESC LIMIT (limit * 4)                   │
│    │                                                                │
│    └─ fuseByRrf()  ← RRF 融合                                      │
│        └─ Reciprocal Rank Fusion (k=60)                             │
│           按 RRF 得分排序取 top limit                                │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 四、数据模型（数据库表）

### 4.1 agent —— Agent 配置表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) PK | UUID 主键 |
| name | VARCHAR(100) | Agent 名称 |
| description | TEXT | Agent 描述 |
| system_prompt | TEXT | 系统提示词 |
| model | VARCHAR(50) | 模型标识 (deepseek-chat / glm-4.6) |
| allowed_tools | JSON/TEXT | 允许的工具名称列表 |
| allowed_kbs | JSON/TEXT | 允许的知识库 ID 列表 |
| chat_options | JSON/TEXT | 聊天参数 (temperature, topP, messageLength) |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

### 4.2 agent_memory —— 长期记忆表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) PK | UUID 主键 |
| agent_id | VARCHAR(32) | 关联 agent |
| memory_key | VARCHAR(128) | 记忆键 (如 profile.identity, preference.篮球) |
| memory_value | VARCHAR(64) | 记忆值 |
| fact | TEXT | 原始事实句子 |
| confidence | DOUBLE | 置信度 [0.50, 0.98] |
| status | VARCHAR(20) | ACTIVE / SUPERSEDED / CONFLICTED |
| superseded_by_memory_id | VARCHAR(32) | 取代该记忆的新记忆 ID |
| source_session_id | VARCHAR(32) | 来源会话 |
| evidence_count | INT | 证据次数 |
| last_confirmed_at | TIMESTAMP | 最后确认时间 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

### 4.3 chat_session —— 聊天会话表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) PK | UUID 主键 |
| agent_id | VARCHAR(32) | 关联 agent |
| title | VARCHAR(200) | 会话标题 |
| metadata | JSON/TEXT | 元数据（sessionSummary, summarizedMessageCount, summaryUpdatedAt） |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

### 4.4 chat_message —— 聊天消息表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) PK | UUID 主键 |
| session_id | VARCHAR(32) | 关联会话 |
| role | VARCHAR(20) | user / assistant / tool / system |
| content | TEXT | 消息内容 |
| metadata | JSON/TEXT | 元数据（toolCalls, toolResponse） |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

### 4.5 document —— 文档表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) PK | UUID 主键 |
| kb_id | VARCHAR(32) | 关联知识库 |
| filename | VARCHAR(255) | 文件名 |
| filetype | VARCHAR(20) | 文件类型 (md/pdf/txt) |
| size | BIGINT | 文件大小（字节） |
| metadata | JSON/TEXT | 元数据（filePath） |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

### 4.6 chunk_bge_m3 —— 向量块表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) PK | UUID 主键 |
| kb_id | UUID | 关联知识库 |
| doc_id | UUID | 关联文档 |
| content | TEXT | 块内容 |
| metadata | JSON/TEXT | 元数据（markdown 标题等） |
| embedding | vector | bge-m3 向量嵌入 (pgvector) |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

### 4.7 knowledge_base —— 知识库表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) PK | UUID 主键 |
| name | VARCHAR(100) | 知识库名称 |
| description | TEXT | 知识库描述 |
| metadata | JSON/TEXT | 元数据（version） |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

---

## 五、API 接口文档

### 5.1 Agent 管理

| 方法 | 路径 | 功能 | 请求体 |
|------|------|------|--------|
| GET | `/api/agents` | 查询所有 Agent | - |
| POST | `/api/agents` | 创建 Agent | `CreateAgentRequest` |
| DELETE | `/api/agents/{agentId}` | 删除 Agent | - |
| PATCH | `/api/agents/{agentId}` | 更新 Agent | `UpdateAgentRequest` |

### 5.2 聊天会话

| 方法 | 路径 | 功能 | 请求体 |
|------|------|------|--------|
| GET | `/api/chat-sessions` | 查询所有会话 | - |
| GET | `/api/chat-sessions/{id}` | 查询单个会话 | - |
| GET | `/api/chat-sessions/agent/{agentId}` | 按 Agent 查询会话 | - |
| POST | `/api/chat-sessions` | 创建会话 | `CreateChatSessionRequest` |
| DELETE | `/api/chat-sessions/{id}` | 删除会话 | - |
| PATCH | `/api/chat-sessions/{id}` | 更新会话 | `UpdateChatSessionRequest` |

### 5.3 聊天消息

| 方法 | 路径 | 功能 | 请求体 |
|------|------|------|--------|
| GET | `/api/chat-messages/session/{sessionId}` | 查询会话的所有消息 | - |
| POST | `/api/chat-messages` | 创建消息（触发 Agent） | `CreateChatMessageRequest` |
| GET | `/api/chat-messages/{id}/pdf` | 下载 AI 回复为 PDF | - |
| DELETE | `/api/chat-messages/{id}` | 删除消息 | - |
| PATCH | `/api/chat-messages/{id}` | 更新消息 | `UpdateChatMessageRequest` |

### 5.4 文档管理

| 方法 | 路径 | 功能 | 请求体 |
|------|------|------|--------|
| GET | `/api/documents` | 查询所有文档 | - |
| GET | `/api/documents/kb/{kbId}` | 按知识库查询文档 | - |
| POST | `/api/documents` | 创建文档记录 | `CreateDocumentRequest` |
| POST | `/api/documents/upload` | 上传并解析文件 | multipart: kbId + file |
| DELETE | `/api/documents/{id}` | 删除文档 | - |
| PATCH | `/api/documents/{id}` | 更新文档 | `UpdateDocumentRequest` |

### 5.5 知识库

| 方法 | 路径 | 功能 | 请求体 |
|------|------|------|--------|
| GET | `/api/knowledge-bases` | 查询所有知识库 | - |
| POST | `/api/knowledge-bases` | 创建知识库 | `CreateKnowledgeBaseRequest` |
| DELETE | `/api/knowledge-bases/{id}` | 删除知识库 | - |
| PATCH | `/api/knowledge-bases/{id}` | 更新知识库 | `UpdateKnowledgeBaseRequest` |

### 5.6 其他

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/tools` | 获取可选工具列表 |
| POST | `/api/rag/evaluate` | RAG 检索评估 |
| GET | `/sse/connect/{sessionId}` | SSE 实时连接（text/event-stream） |
| GET | `/health` | 健康检查 |

---

## 六、服务层详解

### 6.1 AgentMemoryService —— 用户画像记忆

通过正则匹配从用户对话中提取结构化记忆，支持以下模式：

| 模式 | 正则 | 记忆键 | 示例 |
|------|------|--------|------|
| 身份 | `^我(?:是\|是一名\|是一位)...` | `profile.identity` | "我是厦门大学的学生" |
| 教育 | `^我在读...` | `profile.education.current` | "我在读计算机科学" |
| 属性 | `^我的...是...` | `profile.attr.{key}` | "我的专业是AI" |
| 喜欢 | `^我(?:最)?(?:喜欢\|偏好\|爱)...` | `preference.{target}`=like | "我喜欢打篮球" |
| 讨厌 | `^我(?:最)?(?:讨厌\|不喜欢\|厌恶)...` | `preference.{target}`=dislike | "我不喜欢吃辣" |

**置信度计算**：
- 基础分：身份 0.70 / 教育 0.70 / 属性 0.65 / 偏好 0.75
- 证据加成：每次 +0.03（上限 +0.18）
- 跨会话：+0.10
- 强断言：+0.05
- 近期确认：+0.05
- 不确定语气：-0.10
- 冲突：-0.20
- 时间衰减：每30天 -0.03（上限 -0.20）
- 最终 clamp 至 [0.50, 0.98]

### 6.2 SessionSummaryService —— 会话滚动摘要

- 触发条件：消息总数 >= 12，且上次摘要后有 >= 6 条新消息
- 增量方式：将"历史摘要 + 新增对话片段"发送给 LLM 做融合压缩
- 输出限制：摘要 <= 220 字，每条消息截断至 280 字符
- 结果存储于 `chat_session.metadata.sessionSummary`

### 6.3 RagService —— 混合检索

| 检索方式 | 实现 | 说明 |
|----------|------|------|
| 向量检索 | `embedding <-> query_vector` | 余弦距离，pgvector 原生算子 |
| BM25 检索 | `to_tsvector(content \|\| metadata) @@ plainto_tsquery(query)` | PostgreSQL 全文检索 |
| 混合融合 | RRF (k=60) | 各自取 limit×4 候选，按倒数排序融合 |

### 6.4 RagEvaluationService —— 评估指标

| 指标 | 公式 | 含义 |
|------|------|------|
| HitRate@K | 是否有相关文档在 Top-K 中 | 命中率 |
| Recall@K | 命中相关文档数 / 总相关文档数 | 召回率 |
| MRR@K | 第一个相关文档的排名倒数 | 平均倒数排名 |
| NDCG@K | DCG / IDCG | 归一化折损累计增益 |

---

## 七、工具系统

所有工具实现 `Tool` 接口，通过 Spring AI 的 `@Tool` 注解注册为 LLM 可调用的函数。

| 工具名 | 类型 | 功能 | 安全措施 |
|--------|------|------|----------|
| `terminate` | FIXED | 终止 Agent 循环 | - |
| `KnowledgeTool` | FIXED | 单库/全库混合检索 | - |
| `KnowledgeToolAll` | FIXED | 全知识库检索 | - |
| `directAnswer` | FIXED(禁用) | 直接回答 | - |
| `databaseQuery` | OPTIONAL | 执行 SELECT 查询 | 仅允许 SELECT，拒绝写入语句 |
| `sendEmail` | OPTIONAL | 异步发送邮件 | 邮箱格式校验 |
| `readFile` | OPTIONAL | 读取文件 | 路径穿越防护 |
| `writeFile` | OPTIONAL | 写入文件 | 路径穿越防护 |
| `appendToFile` | OPTIONAL | 追加文件 | 路径穿越防护 |
| `listFiles` | OPTIONAL | 列出目录 | 路径穿越防护 |
| `deleteFile` | OPTIONAL | 删除文件或目录 | 路径穿越防护 |
| `createDirectory` | OPTIONAL | 创建目录 | 路径穿越防护 |
| `getCity` / `getDate` / `weather` | FIXED(测试) | 测试用工具 | - |

---

## 八、关键技术决策

### 8.1 手动控制工具执行

关闭 SpringAI 的 `internalToolExecutionEnabled`，由 `ShopAi` 自行管理 think-execute 循环。目的是：
- 在每次工具调用前后持久化消息到数据库
- 通过 SSE 实时推送每个步骤给前端
- 精确控制循环次数和终止条件

### 8.2 协议安全的消息重建

`rebuildProtocolSafeMessages()` 严格重建 Message 序列：
- ASSISTANT(tool_calls) 消息后必须紧跟对应的 TOOL 响应
- 孤立 TOOL 响应直接丢弃
- 不完整的 tool_calls 序列跳过（不传给 LLM）

避免向模型发送协议不完整的消息导致异常。

### 8.3 异步事件驱动

```
用户请求 → 持久化消息 → 发布 ChatEvent → 异步监听 → 创建 Agent → 执行
              ↑                                    |
              └──── 立即返回 chatMessageId 给前端 ←─┘
```

HTTP 请求不阻塞等待 Agent 完成，而是通过 SSE 异步推送结果。

### 8.4 增量式会话摘要

- 不每次重新压缩全部历史
- 仅当新增消息 >= 6 条且总消息 >= 12 条时触发
- LLM 将"历史摘要 + 增量对话"融合为新摘要
- 避免 Token 浪费，适合长对话场景

### 8.5 RRF 混合检索

- 向量检索和 BM25 各取 `limit × 4` 候选
- RRF(k=60)：`score = Σ 1/(k + rank)`
- 兼顾语义相似度和关键词精确匹配

### 8.6 路径穿越防护

`FileSystemTools.validateAndResolvePath()` 实现：
- 基础目录：`System.getProperty("user.dir")`
- 将用户输入解析为绝对路径后校验是否以基础目录为前缀
- 防止 `../../etc/passwd` 类攻击

---

## 九、配置说明

### 9.1 application.yaml 关键配置

```yaml
spring:
  datasource:
    url: jdbc:postgresql://8.163.27.12:5432/jchatmind
    username: muzhiyuan
    password: ****
  ai:
    deepseek:
      api-key: sk-****
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
    zhipuai:
      api-key: your-api-key
      base-url: https://open.bigmodel.cn/api/paas
      chat:
        options:
          model: glm-4.6
  mail:
    host: smtp.qq.com
    port: 587
    username: 2685922758@qq.com

document:
  storage:
    base-path: ./data/documents
```

### 9.2 数据库索引

`chunk_hybrid_retrieval.sql` 提供性能优化脚本：

```sql
-- 全文检索表达式索引
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_fts
    ON chunk_bge_m3 USING GIN
    (to_tsvector('simple', COALESCE(content, '') || ' ' || COALESCE(metadata::text, '')));

-- 向量检索索引 (IVFFlat, cosine distance)
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_embedding_ivfflat
    ON chunk_bge_m3 USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
```

---

## 十、Agent 演化历程

```
JChatMindV1 (基础聊天)
│
│  + 集成 ChatClient 与 LLM 交互
│  + ChatMemory 管理对话历史
│  + 简单的 question → answer 流程
│
▼
JChatMindV2 (ReAct 工具调用)
│
│  + 引入 ToolCallback 与 ToolCallingManager
│  + 实现 think-execute 循环
│  + 关闭自动工具执行，手工控制流程
│
▼
ShopAi (生产级 Agent)
│
│  + 持久化消息到数据库
│  + SSE 实时推送每个步骤
│  + 事件驱动异步执行
│  + 会话摘要注入
│  + 长期记忆注入
│  + 知识库注入
│  + 论文推荐自动追加
│
▼
ShopAiFactory (工厂装配)
│
│  + 统一的 Agent 创建入口
│  + 按需加载配置 / 记忆 / 工具 / 知识库
│  + 系统提示词与长期记忆合并
│  + 工具注册与 Spring AOP 代理处理
```

---

## 十一、异常处理体系

| 异常类 | 说明 | HTTP 状态码 |
|--------|------|-------------|
| `BizException` | 业务异常（参数校验、资源不存在等） | 400 |
| `NoResourceFoundException` | 404 资源不存在 | 404 |
| `AsyncRequestTimeoutException` | SSE 等异步请求超时 | 503 |
| 其他 `Exception` | 未预期服务器错误 | 500 |

全局通过 `GlobalExceptionHandler`（`@RestControllerAdvice`）统一处理，返回标准 `ApiResponse` 格式。
