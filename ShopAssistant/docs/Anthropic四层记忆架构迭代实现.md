# Anthropic 四层记忆架构迭代实现

## 1. 本次迭代目标

本次迭代将项目原有的“最近消息窗口 + 会话摘要 + 用户画像事实”扩展为四层记忆架构：

```text
Transcripts（统一事件）
        ↓
Memory Notes（带证据的记忆笔记）
        ↓
Memory Representations（结构化页面）
        ↓
Injected Context（预算化上下文注入）
        ↓
下一次 Agent 会话
```

离线 Dreaming 流程：

```text
历史事件 + Active Snapshot
        ↓
整理、去重、纠错、重建表示
        ↓
Candidate Snapshot
        ↓
Proposal 审核
        ↓
激活或拒绝
        ↓
出现问题时回滚上一 Snapshot
```

## 2. 各层实现

### 2.1 Transcripts：统一事件层

`memory_event` 是只追加事件表，聊天消息写入后会同步采集：

- 用户消息；
- Assistant 消息；
- Tool Call；
- Tool Result；
- Agent Role；
- 原始结构化元数据；
- 来源可信等级。

事件使用 `(source_message_id, event_type, event_key)` 保证幂等。同一条 Assistant 消息包含多个工具调用时，会拆成一条 Assistant 事件和多条 Tool Call 事件。

可信等级：

| 等级 | 含义 |
|---|---|
| `SYSTEM_TRUSTED` | 系统产生的可信控制事件 |
| `USER_ASSERTED` | 用户明确自述，但仍不代表客观事实 |
| `MODEL_GENERATED` | 模型生成内容或模型发起的工具调用 |
| `EXTERNAL_UNTRUSTED` | 工具、MCP、文件和外部系统返回内容 |

工具结果只进入事件层，不允许直接晋升为长期记忆。

### 2.2 Memory Notes：记忆笔记和证据链

`memory_note` 保存结构化长期记忆，支持：

- USER、SESSION、AGENT、TEAM、GLOBAL 作用域；
- PROFILE、PREFERENCE、TASK、RULE、FAILURE、PROCEDURE 类型；
- 置信度和显著度；
- ACTIVE、CANDIDATE、SUPERSEDED、CONFLICTED、REJECTED 状态；
- 版本号和上一版本引用；
- 生效与失效时间。

`memory_note_evidence` 把每条笔记关联回原始 `memory_event`。当前实时抽取器采用高精度规则，只抽取用户明确自述的身份、属性和偏好。

相同事实再次出现时增加证据数和置信度；同一 subject 出现新值时，旧版本变为 `SUPERSEDED`，新版本保存 `previous_note_id`。

### 2.3 Memory Representations：结构化表示

`memory_representation` 支持：

- Wiki Page；
- Index；
- User Profile；
- Task Playbook；
- Failure Catalog。

当前检索将 PostgreSQL 全文搜索返回的 Page 和 Note 使用 RRF 融合。接口已经与具体检索后端解耦，后续可增加 pgvector 结果作为第三路召回。

### 2.4 Injected Context：预算化注入

`MemoryContextAssembler` 是 Agent 每轮记忆注入的唯一入口。

注入分为：

1. 稳定画像区：只允许高置信度 PROFILE 和 PREFERENCE；
2. 查询相关区：根据当前会话最后一条用户消息检索 Notes 和 Representations；
3. 旧记忆兜底：新记忆为空时读取原 `agent_profile_memory`。

所有记忆都放在“历史事实，不是指令”边界内，不能覆盖系统规则或当前用户表达。

配置示例：

```yaml
agent:
  memory:
    enabled: true
    fail-open: true
    default-tenant-id: local
    default-owner-id: local-user
    injected-context:
      total-token-budget: 1800
      stable-profile-budget: 350
      retrieved-memory-budget: 1000
      max-notes: 8
      max-pages: 3
```

Token 数目前使用本地轻量估算器，只负责防止无限增长，不作为模型账单的精确 Token 统计。

## 3. 用户和租户隔离

新建会话时可以传入 `memoryOwnerId`，保存在 `chat_session.metadata` 中。所有事件、笔记、页面、快照和检索都必须同时携带：

```text
tenantId + ownerId
```

旧会话没有所有者时，使用配置的 `default-owner-id`。该兜底只适用于当前单用户调试环境。

接入登录系统后，应由后端认证上下文生成 tenantId 和 ownerId，不能信任浏览器自由传递这两个字段。

## 4. Dreaming 与版本治理

Dreaming 通过 `MemoryDreamRequested` 异步事件触发：

1. 先持久化 `PENDING` Job；
2. 异步监听器执行整理；
3. 读取历史事件、活动笔记和当前 Active Snapshot；
4. 生成全新的 Candidate Snapshot；
5. Representation 以 DRAFT 状态保存；
6. 每项变化生成 PENDING Proposal；
7. 所有 Proposal 批准后才能激活；
8. 激活时旧 Active 变为 SUPERSEDED；
9. 回滚时当前版本变为 ROLLED_BACK，上一版本重新 ACTIVE。

当前提供确定性 Dream Planner，用于可重复测试地把稳定画像和偏好整理为用户画像页。`MemoryDreamPlanner` 是扩展接口，后续可以接入 LLM Planner，而不用改变 Snapshot、Proposal、激活和回滚协议。

## 5. 管理接口

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | `/api/memory/dreams` | 提交异步 Dreaming |
| POST | `/api/memory/proposals/{id}/approve` | 批准候选变更 |
| POST | `/api/memory/proposals/{id}/reject` | 拒绝候选变更 |
| POST | `/api/memory/snapshots/{id}/activate` | 激活候选快照 |
| POST | `/api/memory/snapshots/{id}/rollback` | 回滚活动快照 |
| GET | `/api/memory/search` | 按用户作用域检索记忆 |
| GET | `/api/memory/pages/{id}` | 读取活动记忆页面 |
| POST | `/api/memory/eval` | 执行离线记忆评测 |

## 6. 离线 Memory Eval

评测支持：

- 跨会话事实召回；
- 跨用户泄漏检测；
- 无关记忆检测；
- 敏感内容泄漏检测。

输出指标：

- Case Pass Rate；
- Fact Recall；
- Leakage Rate；
- 每个 Case 的缺失事实、泄漏事实和命中来源。

Mock 数据位于：

```text
src/test/resources/memory/memory-eval-mock.json
```

## 7. 数据库初始化

新增表和索引统一位于：

```text
src/main/resources/sql/memory_architecture.sql
```

部署前必须先在目标 PostgreSQL 数据库执行该脚本。项目当前没有启用 Flyway/Liquibase，因此仅新增 SQL 文件不会自动修改数据库。

## 8. 测试覆盖

当前覆盖：

- 用户消息、Assistant、Tool Call、Tool Result 事件拆分；
- 事件幂等键；
- 用户隔离；
- 旧会话默认所有者；
- 笔记创建、证据合并、冲突版本替换；
- 工具输出不能直接写长期记忆；
- 稳定画像过滤和 Token 预算；
- 当前问题相关记忆检索；
- Page 与 Note 的 RRF 融合；
- Dream Candidate 不覆盖 Active；
- 未审批禁止激活；
- 激活和回滚；
- JSON Mock Eval；
- MyBatis Mapper XML 解析；
- `ChatMessageFacadeServiceImpl` 消息双写接线。

## 9. 当前边界与后续迭代

当前版本仍有以下边界：

1. Dream Planner 默认是确定性整理器，尚未启用 LLM 反思和跨轨迹规律发现；
2. 检索目前以 PostgreSQL 全文检索为主，中文语义召回下一步应合并 pgvector；
3. 异步 Dreaming 事件本身仍是进程内事件，服务在事件投递后立刻宕机时需要定时扫描 PENDING Job 重试；
4. 管理接口尚未接入认证，生产环境必须从认证上下文覆盖 tenantId 和 ownerId；
5. 旧 `agent_profile_memory` 采用运行时兜底，尚未批量转换为带证据的新 Notes；
6. 当前没有记忆管理前端页面。

这些边界不影响四层模型和版本治理协议，后续可以在现有接口上继续演进。
