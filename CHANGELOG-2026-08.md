# ShopAssistant 2026 年 8 月迭代记录

本轮迭代围绕 **Agent Skills 渐进式披露、执行轨迹治理与受控 Auto-Evolution** 展开，在既有 ReAct、MCP、Guardrails、Token 计量和 LLM-as-Judge 基础上，补齐了从低分样本发现到候选生成、回放评估、人工审批、发布与回滚的工程闭环。

整体状态：**后端完整测试 18/18 通过 · 真实 DeepSeek 对话与工具调用通过 · MCP 握手并发现 4 个工具 · Auto-Evolution HTTP 端到端闭环通过 · 前端 lint/build 通过**。

---

## 目录

1. [Skills 渐进式披露落地](#1-skills-渐进式披露落地)
2. [执行轨迹与低分样本挖掘](#2-执行轨迹与低分样本挖掘)
3. [Prompt/Skill 候选版本生成](#3-promptskill-候选版本生成)
4. [回放评估与发布门禁](#4-回放评估与发布门禁)
5. [热加载、持久化与回滚](#5-热加载持久化与回滚)
6. [模型配置与凭据治理](#6-模型配置与凭据治理)
7. [接口清单](#7-接口清单)
8. [测试结果](#8-测试结果)
9. [提交记录](#9-提交记录)
10. [简历表述建议](#10-简历表述建议)

---

## 1. Skills 渐进式披露落地

### 目标

避免将所有 Skill 全文一次性塞进系统提示词，降低上下文成本，并让模型只在真正需要时加载流程知识。

### 实现

- `SkillRegistry` 启动时扫描 `data/skills/<name>/SKILL.md`。
- 第一层只向系统提示词披露 `name + description` 目录。
- 第二层由模型调用 `useSkill(skillName)` 按需获取完整正文与推荐工具。
- Skill 正文不会在目录阶段泄漏，已通过单元测试验证。
- 示例 `order-refund` Skill 已放入后端实际运行目录。
- 运行目录通过 `AGENT_SKILLS_DIR` 配置，可在不同环境中切换。

### 效果

Skills 从静态 Demo 变成了可被主 Agent 发现、选择、加载和执行的运行时能力，同时为后续 Skill 候选演化提供了明确的版本载体。

---

## 2. 执行轨迹与低分样本挖掘

### 数据链路

每条 Assistant 消息完成异步 LLM-as-Judge 评分后，系统自动采集：

- 用户问题与模型回答；
- Prompt 版本；
- 本轮调用过的工具；
- 通过 `useSkill` 加载过的 Skill；
- Prompt、Completion、Total Token 用量；
- correctness、completeness、relevance 三维评分；
- 三维平均分与低分标记。

### 安全与可靠性

- 问题和回答在落盘前复用 Guardrails 做手机号、邮箱等 PII 脱敏。
- 默认平均分不高于 `3.0` 时标记为低分样本。
- 轨迹使用 JSON 文件持久化，采用临时文件加原子替换，避免进程中断产生半文件。
- 查询时隔离损坏文件，不因单条异常阻断全部样本。
- ID 经过 UUID 格式校验，避免路径穿越。

---

## 3. Prompt/Skill 候选版本生成

### 候选生成

新增 `evolution.candidate.v1` 模板，由 DeepSeek 根据一批低分轨迹分析重复缺陷，生成：

- 完整 Prompt 候选；或
- 包含 YAML frontmatter 与正文的完整 `SKILL.md` 候选。

### 版本治理

- 每个候选包含独立 UUID、目标名称、基线版本、候选版本、生成理由和来源轨迹 ID。
- 候选初始状态固定为 `DRAFT`，生成后不会自动发布。
- 单次最多吸收 20 条低分轨迹，防止上下文无限膨胀。
- 只接受低分样本；普通样本、缺失样本和非法目标名称会被拒绝。
- 候选同样通过原子 JSON 文件持久化，支持列表与单条查询。

---

## 4. 回放评估与发布门禁

### 受控状态机

```text
DRAFT → EVALUATING → EVALUATED → APPROVED → ACTIVE → ROLLED_BACK
                         │
                         └── 未达门槛：禁止审批
```

### 回放机制

- 使用候选 Prompt/Skill 对来源低分问题重新生成回答。
- 回放过程不挂载业务工具，避免数据库、邮件、文件等副作用。
- 使用 LLM-as-Judge 对候选回答重新进行三维评分。
- 将候选平均分与历史轨迹基线分直接比较。
- 默认发布门槛：候选平均分至少 `3.2`，且相对基线至少提升 `0.2`。
- 任一样本评分失败时拒绝进入审批，避免用不完整数据作发布决策。

### 安全门禁

审批和发布前都会检查候选内容：

- 最大内容长度；
- API Key、密码等疑似凭据；
- 绕过审批、权限、安全或 Guardrails 的指令；
- Skill 是否包含 frontmatter，且 Skill 名称是否与目标一致。

只有回放达标的 `EVALUATED` 候选才能被显式审批，只有 `APPROVED` 候选才能发布。

---

## 5. 热加载、持久化与回滚

### Prompt

- `PromptRegistry` 增加活动覆盖层。
- 发布时只替换运行时活动版本，classpath 内的基线模板保持不变。
- 原有 `render/get` 调用方无需修改即可读取新活动版本。

### Skill

- `SkillRegistry` 支持校验并热加载完整候选 `SKILL.md`。
- 发布不会直接覆盖仓库中的 Skill 源文件。
- 目录披露和 `useSkill` 会立即读取新活动版本。

### 持久化与恢复

- 活动制品保存在 `data/evolution/active`。
- 应用重启后自动恢复活动 Prompt/Skill。
- 发布时保存上一版完整内容，回滚后恢复上一版本并持久化。
- 同一目标同时只允许一个活动候选，避免多版本状态歧义。

---

## 6. 模型配置与凭据治理

- 数据库、邮件、DeepSeek、智谱等凭据全部改为环境变量读取。
- 本轮测试使用的 DeepSeek Key 未写入任何源码、配置、测试数据或 Git 提交。
- 增加 `spring.ai.model.chat` 主模型选择，默认只自动装配 DeepSeek。
- 显式关闭未使用的 embedding/image 自动装配，避免缺少智谱 Key 时阻断启动。
- 智谱 `ChatClient` 仅在对应模型 Bean 存在时创建。
- 修复后仅配置 DeepSeek Key 即可启动后端并完成 MCP 初始化。

主要配置：

```yaml
spring:
  ai:
    model:
      chat: ${PRIMARY_CHAT_MODEL:deepseek}
      embedding: none
      image: none

agent:
  evolution:
    enabled: true
    storage-dir: ${AGENT_EVOLUTION_DIR:./data/evolution}
    low-score-threshold: 3.0
    prompt-version: think.system.v1
    candidate-model: deepseek-chat
    judge-model: deepseek-chat
    max-candidate-traces: 20
    min-candidate-score: 3.2
    min-improvement: 0.2
```

---

## 7. 接口清单

| 方法 | 路径 | 作用 |
|---|---|---|
| `GET` | `/api/evolution/traces` | 查询执行轨迹，可按低分过滤与限制数量 |
| `POST` | `/api/evolution/candidates/generate` | 从低分轨迹生成 Prompt/Skill 草稿候选 |
| `GET` | `/api/evolution/candidates` | 查询候选版本列表 |
| `POST` | `/api/evolution/candidates/{id}/evaluate` | 对候选执行无副作用回放与 Judge 评分 |
| `POST` | `/api/evolution/candidates/{id}/approve` | 显式审批已达门槛候选 |
| `POST` | `/api/evolution/candidates/{id}/activate` | 热加载并持久化活动版本 |
| `POST` | `/api/evolution/candidates/{id}/rollback` | 恢复发布前版本 |

---

## 8. 测试结果

### 后端完整回归

执行 `mvn test`：

- Tests run：18
- Failures：0
- Errors：0
- Skipped：0
- 结果：`BUILD SUCCESS`

覆盖内容：

- 真实 DeepSeek 基础对话、多轮记忆与重置；
- 真实 DeepSeek ReAct 单工具、多工具与无工具对话；
- Spring Boot 完整上下文启动；
- AnySearch MCP 握手、工具发现，成功注册 `search`、`batch_search`、`extract`、`get_sub_domains`；
- Skills 两层渐进式披露；
- 轨迹脱敏、工具/Skill 归因、低分判断、原子落盘与坏文件隔离；
- 候选生成约束和版本持久化；
- 安全拦截、状态机、评分门禁、审批、发布、重启恢复与回滚。

### HTTP 端到端验证

使用隔离演化目录启动真实后端，依次调用完整接口链路：

1. 读取 1 条低分轨迹成功；
2. DeepSeek 生成 Prompt 候选，状态为 `DRAFT`；
3. 回放评估成功，基线平均分 `1.33`，候选平均分 `5.00`，提升 `3.67`；
4. 审批后状态为 `APPROVED`；
5. 发布后状态为 `ACTIVE`，并保存 321 字基线 Prompt；
6. 回滚后状态为 `ROLLED_BACK`；
7. 测试服务已停止，临时 Key 未长期保留在进程中。

### 前端与构建

- `npm run lint`：通过；
- `npm run build`：通过；
- `mvn -DskipTests package`：通过；
- 当前仍有 Vite 约 1.3 MB 大 chunk 的性能警告，不影响功能与构建结果。

### 测试边界

本轮没有拿到当前数据库密码，因此没有执行真实业务库读写用例；Spring 上下文、后端启动及不依赖数据库的演化接口均已验证。数据库密码仍通过 `DB_PASSWORD` 环境变量配置。

---

## 9. 提交记录

| Commit | 内容 |
|---|---|
| `7f49006` | 建立 Agent 工程化基线，落地 Skills 运行目录、凭据外置与前后端检查 |
| `ada1a02` | 执行轨迹采集、PII 脱敏与低分样本挖掘 |
| `e1469b0` | 基于低分轨迹生成版本化 Prompt/Skill 候选 |
| `bdb5a69` | 回放评估、发布门禁、审批、热加载与回滚闭环 |
| `f822cac` | 修复 DeepSeek 单模型本地启动与未配置供应商阻塞问题 |

每个功能均在自测通过后独立提交，便于按提交审阅和回退。

---

## 10. 简历表述建议

推荐将原项目最后一点整理为：

> 构建基于执行轨迹与 LLM-as-Judge 的受控 Auto-Evolution 闭环，自动采集并脱敏低分样本，生成版本化 Prompt/Skill 候选；通过无副作用回放、质量阈值、安全校验与人工审批控制发布，支持运行时热加载、重启恢复及一键回滚。

Skills 可单独补充为：

> 设计 Skills 渐进式披露机制，系统提示词仅注入 Skill 元数据目录，由 Agent 按任务动态调用 `useSkill` 加载完整流程指令，在控制上下文成本的同时实现流程能力复用。

---

**日期**：2026-08-19
