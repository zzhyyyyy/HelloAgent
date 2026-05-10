# ShopAssistant - 智能购物助手

基于 **Spring AI + DeepSeek** 构建的智能助手平台，支持单 Agent 对话与多 Agent 协作两种模式，集成知识库检索（RAG）、工具调用、跨会话记忆等功能。

---

## 技术栈

### 后端

| 框架/技术 | 版本 | 用途 |
|-----------|------|------|
| Spring Boot | 3.5.8 | 应用框架 |
| Spring AI | 1.1.0 | AI 模型接入（DeepSeek、智谱） |
| MyBatis | 3.0.3 | 数据库 ORM |
| PostgreSQL | — | 主数据库（含 pgvector 扩展） |
| Apache PDFBox | 3.0.3 | PDF 生成与解析 |
| Flexmark | 0.64.8 | Markdown 解析 |
| Lombok | — | 代码简化 |

### 前端

| 框架/技术 | 版本 | 用途 |
|-----------|------|------|
| React | 19.2 | UI 框架 |
| TypeScript | 5.9 | 类型安全 |
| Vite (Rolldown) | 7.2 | 构建工具 |
| Ant Design X | 2.0 | AI 对话组件库 |
| Ant Design | 6.0 | UI 组件库 |
| Tailwind CSS | 4.1 | 样式方案 |

---

## 项目结构

```
ShopAssistant/
├── ShopAssistant/                    # 后端模块
│   ├── src/main/java/com/xmu/ShopAssistant/
│   │   ├── agent/                    # 核心 Agent 运行时
│   │   │   ├── ShopAi.java           # 单 Agent ReAct 循环实现
│   │   │   ├── ShopAiFactory.java    # Agent 实例工厂
│   │   │   ├── AgentState.java       # Agent 状态枚举
│   │   │   ├── multi/                # 多 Agent 相关
│   │   │   │   └── DelegateToSpecialistTool.java
│   │   │   ├── tools/                # 工具实现
│   │   │   │   ├── KnowledgeTools.java       # 知识库检索
│   │   │   │   ├── DataBaseTools.java        # 数据库查询
│   │   │   │   ├── FileSystemTools.java      # 文件系统操作
│   │   │   │   ├── EmailTools.java           # 邮件发送
│   │   │   │   ├── TerminateTool.java        # 终止工具
│   │   │   │   └── test/                    # 测试用工具
│   │   │   └── examples/             # 演化原型
│   │   ├── config/                   # 配置类
│   │   │   ├── MultiChatClientConfig.java   # 多模型 ChatClient 配置
│   │   │   ├── ChatClientRegistry.java      # ChatClient 注册表
│   │   │   ├── CorsConfig.java              # 跨域配置
│   │   │   └── AsyncConfig.java             # 异步线程池配置
│   │   ├── controller/               # REST 控制器
│   │   │   ├── AgentController.java         # Agent CRUD
│   │   │   ├── ChatSessionController.java   # 会话管理
│   │   │   ├── ChatMessageController.java   # 消息管理 + PDF 下载
│   │   │   ├── DocumentController.java      # 文档管理
│   │   │   ├── KnowledgeBaseController.java # 知识库管理
│   │   │   ├── ToolController.java          # 工具查询
│   │   │   ├── SseController.java           # SSE 连接
│   │   │   └── RagEvaluationController.java # RAG 评估
│   │   ├── converter/                # DTO/Entity/VO 转换器
│   │   ├── event/                    # 事件系统
│   │   │   ├── ChatEvent.java               # 聊天事件
│   │   │   └── listener/ChatEventListener.java  # 事件监听（路由 Agent）
│   │   ├── mapper/                   # MyBatis Mapper
│   │   ├── message/                  # SSE 消息模型
│   │   │   └── SseMessage.java
│   │   ├── model/                    # 数据模型
│   │   │   ├── entity/               # 数据库实体
│   │   │   ├── dto/                  # 数据传输对象
│   │   │   ├── vo/                   # 前端视图对象
│   │   │   ├── request/              # 请求参数
│   │   │   ├── response/             # 响应封装
│   │   │   └── common/ApiResponse.java  # 统一响应
│   │   ├── service/                  # 业务服务层
│   │   │   ├── impl/
│   │   │   │   ├── RagServiceImpl.java         # RAG 检索（BM25+向量混合）
│   │   │   │   ├── SpecialistRunner.java       # 多 Agent Specialist 执行器
│   │   │   │   ├── AgentMemoryServiceImpl.java # 跨会话记忆
│   │   │   │   └── SessionSummaryServiceImpl.java  # 会话摘要
│   │   │   ├── PdfService.java                # PDF 生成
│   │   │   └── ...
│   │   ├── typehandler/              # MyBatis 类型处理器
│   │   │   └── PgVectorTypeHandler.java  # pgvector 向量处理
│   │   └── ShopAssistantApplication.java
│   └── src/main/resources/
│       ├── application.yaml          # 主配置文件
│       ├── mapper/                   # MyBatis XML 映射
│       └── sql/                      # SQL 脚本
│           └── chunk_hybrid_retrieval.sql  # 混合检索索引
│
├── ui/                               # 前端模块
│   └── src/
│       ├── api/                      # API 请求层
│       ├── components/
│       │   ├── views/
│       │   │   ├── AgentChatView.tsx           # 聊天主视图
│       │   │   └── agentChatView/
│       │   │       ├── AgentChatHistory.tsx     # 消息历史（含角色标签）
│       │   │       ├── AgentChatInput.tsx       # 输入框
│       │   │       └── EmptyAgentChatView.tsx   # 空状态（含模式选择）
│       │   ├── tabs/                          # 标签页
│       │   ├── modals/                        # 弹窗
│       │   └── SideMenu.tsx                   # 侧边栏菜单
│       ├── contexts/                 # React Context
│       ├── hooks/                    # 自定义 Hooks
│       ├── types/                    # TypeScript 类型定义
│       └── layout/                   # 布局组件
│
└── data/documents/                   # 文档存储目录（运行时生成）
```

---

## 核心功能

### 1. 单 Agent 对话模式
用户选择一个已配置的 Agent，发送消息后触发 ReAct（推理-行动）循环：

```
用户消息 → Agent 加载记忆 → LLM 推理（think）
    ├── 需要调用工具 → 执行工具（execute） → 继续推理
    └── 无需工具 → 返回最终回答
```

Agent 支持的工具包括知识库检索、数据库查询、文件操作、邮件发送等。

### 2. 多 Agent 协作模式（Supervisor + Specialist）
用户可开启多 Agent 协作模式，Supervisor 协调多个专业 Agent 分工合作：

```
用户消息 → Supervisor（协调员）
    ├── 分析问题，拆解子任务
    ├── 调用 DelegateToSpecialist 工具委派给 Specialist
    │   └── Specialist 独立执行自己的 ReAct 循环（含自身工具集）
    └── 汇总所有结果，给出最终回答
```

- Supervisor 和 Specialist 的消息均通过 SSE 实时推送前端
- 每条消息带 `agentRole` 标签（如"协调员"、"专家：数据分析师"）
- Specialist 之间不直接通信，全部通过 Supervisor 中转

### 3. 知识库检索（RAG）
- 支持 Markdown、TXT、PDF 文档上传与解析
- 使用 **BGE-M3** 模型生成文本嵌入向量（通过 Ollama）
- 混合检索：**BM25 全文检索 + 向量相似度检索**，通过 RRF 算法融合排序
- PostgreSQL pgvector 扩展存储和检索向量

### 4. 工具系统
| 工具 | 类型 | 功能 |
|------|------|------|
| KnowledgeTool | 固定 | 知识库语义检索 |
| DataBaseTools | 可选 | 执行 SQL 查询 |
| FileSystemTools | 可选 | 文件读写管理 |
| EmailTools | 可选 | 发送邮件 |
| DelegateToSpecialist | 可选 | 委派任务给 Specialist（多 Agent） |

### 5. 跨会话记忆
- 自动提取用户画像信息（身份、偏好等）
- 基于置信度的记忆评分与衰减机制
- 在后续会话中注入记忆到系统提示词

### 6. 会话摘要
- 消息数量超过阈值后自动触发 LLM 摘要
- 增量更新，避免上下文窗口溢出

### 7. PDF 导出
- 支持将 AI 回复内容导出为 PDF 文件下载
- 自动检测系统中文字体，支持中文排版

---

## 数据库表结构

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| agent | Agent 配置 | id, name, description, system_prompt, model, allowed_tools(jsonb), allowed_kbs(jsonb), chat_options(jsonb) |
| chat_session | 会话 | id, agent_id, title, metadata(jsonb) |
| chat_message | 消息 | id, session_id, role, content, metadata(jsonb) |
| knowledge_base | 知识库 | id, name, description, metadata(jsonb) |
| chunk_bge_m3 | 知识库向量块 | id, kb_id, doc_id, content, metadata(jsonb), embedding(vector) |
| document | 文档 | id, kb_id, filename, filetype, size, metadata(jsonb) |
| agent_memory | Agent 记忆 | id, agent_id, fact_key, fact_value, confidence, status |

---

## 快速开始

### 环境要求
- JDK 17+
- Node.js 20+
- PostgreSQL 15+（含 pgvector 扩展）
- Ollama（本地运行 BGE-M3 嵌入模型）
- DeepSeek API Key（或其他兼容模型）

### 配置文件

修改 `src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://your-host:5432/your-db
    username: your-username
    password: your-password
  ai:
    deepseek:
      api-key: your-deepseek-api-key
      base-url: https://api.deepseek.com
```

Ollama 嵌入服务默认地址 `http://localhost:11434`，如需修改可在 `RagServiceImpl` 中调整。

### 启动后端

```bash
cd ShopAssistant
./mvnw spring-boot:run
```

### 启动前端

```bash
cd ui
npm install
npm run dev
```

前端默认运行在 `http://localhost:5173`，通过 Vite 代理转发 `/api` 和 `/sse` 请求到后端。

### 新建一个 Agent

向 `agent` 表插入一条记录：

```sql
INSERT INTO agent (id, name, description, system_prompt, model, allowed_tools, allowed_kbs, chat_options)
VALUES (
  gen_random_uuid()::text,
  '数据分析师',
  '擅长数据库查询、数据分析和报表生成',
  '你是数据分析专家，使用 DataBaseTools 查询和分析数据。',
  'deepseek-chat',
  '["DataBaseTools"]',
  '[]',
  '{"temperature": 0.3, "topP": 1.0, "messageLength": 10}'
);
```

### 前端切换多 Agent 模式

1. 在聊天页面的 Agent 选择器右侧，点击模式切换开关切换到"多 Agent 协作"
2. 发送一个复杂问题（如"查询数据后保存为文件并发送邮件"）
3. Supervisor 会自动拆解任务并委派给对应的 Specialist Agent

---

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/agents | 获取所有 Agent |
| POST | /api/agents | 创建 Agent |
| GET | /api/chat-sessions | 获取所有会话 |
| POST | /api/chat-sessions | 创建会话（支持 mode 参数：SINGLE/MULTI）|
| GET | /api/chat-messages/session/{id} | 获取会话消息 |
| POST | /api/chat-messages | 发送消息（触发 Agent 执行）|
| GET | /api/chat-messages/{id}/pdf | 下载消息为 PDF |
| GET | /api/knowledge-bases | 获取知识库列表 |
| POST | /api/documents/upload | 上传文档 |
| GET | /sse/connect/{sessionId} | SSE 连接 |
| POST | /api/rag/evaluate | RAG 检索评估 |
