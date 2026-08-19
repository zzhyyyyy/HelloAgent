# HelloAgent 面试 Q&A 学习文档

> 对应项目：`github.com/zzhyyyyy/HelloAgent`  
> 适用岗位：AI 应用开发、Agent 工程、Java 后端开发  
> 使用方式：先背“30 秒项目介绍”和每题的“短答”，再按目标岗位学习“深挖”。

## 如何使用代码溯源

每一章末尾都提供“问题 → 类/方法 → 实现职责”映射。建议先看接口入口，再顺着服务依赖进入核心实现；表中的链接可直接跳转到 GitHub 源码行。行号以本文档生成时的 `main` 分支为准，后续代码调整时可继续按类名和方法名搜索。

---

## 一、30 秒项目介绍

### Q1：请简单介绍一下 HelloAgent

**短答：**

HelloAgent 是我基于 Spring Boot 和 Spring AI 开发的智能体助手。核心不是简单封装大模型聊天接口，而是实现了一套受状态机约束的 ReAct 运行时，并接入 RAG、MCP、Multi-Agent、流式 SSE、工具安全、Skills 渐进式披露和 LLM-as-Judge。最新版还实现了受控 Auto-Evolution：系统从执行轨迹中自动发现低分样本，生成版本化 Prompt 或 Skill 候选，通过无副作用回放、质量与安全门禁、人工审批后热加载，同时支持重启恢复和回滚。

**展开顺序：**

1. Agent 如何执行：ReAct + Tool Calling。
2. Agent 能做什么：RAG、MCP、Multi-Agent、Skills。
3. 如何保证可用：流式输出、Token 计量、Guardrails、工具沙箱。
4. 如何持续优化：执行轨迹 + LLM-as-Judge + 受控 Auto-Evolution。

### Q2：这个项目与普通 AI 聊天 Demo 的区别是什么？

**短答：**

普通 Demo 通常只有一次 Prompt 到一次回答。HelloAgent 关注的是完整运行时：模型要在状态机和最大步数约束下循环决策，按需调用本地工具或 MCP 工具，执行结果重新进入上下文，直到终止；同时回答会被流式推送、记录 Token、异步评分，并进入可审计的演化链路。项目还对数据库、邮件、文件系统等高风险工具增加了权限和副作用控制。

**面试官关注点：**不要只罗列技术名词，要强调“执行、约束、观测、评估、演化”形成闭环。

**本章代码溯源：**

| 问题 | 类 / 方法 | 实现职责 |
| --- | --- | --- |
| Q1-Q2：完整 Agent 工程闭环 | [`ShopAi.run()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAi.java#L574) | 驱动 ReAct 主循环，并在完成后进入持久化与评估链路。 |
| Q1-Q2：运行时装配 | [`ShopAiFactory.create()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAiFactory.java#L368) | 从 Agent、会话、知识库和工具配置构建运行时。 |
| Q1-Q2：异步评分 | [`AgentEvalService.evaluateAndPushAsync()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/eval/AgentEvalService.java#L69) | 对最终回答执行 LLM-as-Judge，并推送评分事件。 |
| Q1-Q2：演化入口 | [`EvolutionController`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/controller/EvolutionController.java#L23) | 暴露轨迹、候选、评估、审批、激活与回滚接口。 |

---

## 二、Agent 运行时与 ReAct

### Q3：你的 ReAct 主循环是怎么工作的？

**短答：**

每一轮将系统提示词、会话上下文和可用工具交给模型。模型如果返回工具调用，运行时执行工具并把结果追加回上下文，再进入下一轮；如果模型调用终止工具或达到完成状态，就持久化最终回答。循环同时受最大步数和状态机约束，防止无限调用。

**深挖：**

- 状态至少要区分思考、执行工具、完成和失败。
- 工具调用与最终回答要走不同持久化路径。
- 达到最大步数时应安全终止，而不是继续消耗 Token。
- Tool Calling 结果必须保留 call id，才能和模型请求对应。

### Q4：为什么要设置最大步数？

**短答：**

大模型可能重复调用同一工具、在无结果时不断尝试，或因提示词冲突形成循环。最大步数是运行时最后一道成本与稳定性保护，配合终止工具、重复调用提示和审计日志，可以将不可控行为限制在明确边界内。

### Q5：终止工具和模型直接输出答案有什么区别？

**短答：**

终止工具把“任务结束”变成结构化动作，运行时能明确识别状态并结束循环；直接输出文本可能只是中间推理，也可能仍需要工具。结构化终止更适合复杂 Agent 流程，也便于记录终止原因。

### Q6：如何避免 Agent 重复调用同一个工具？

**短答：**

一方面在系统 Prompt 和 Skill 返回中提示模型复用已有结果；另一方面运行时保留工具调用轨迹，可以识别相同工具和参数的重复请求。生产化时还可以增加调用签名缓存、重复次数阈值和熔断策略。

**本章代码溯源：**

| 问题 | 类 / 方法 | 实现职责 |
| --- | --- | --- |
| Q3：思考阶段 | [`ShopAi.think()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAi.java#L320) | 将消息与工具交给模型，处理流式响应并识别 Tool Call。 |
| Q3/Q5：工具执行与终止 | [`ShopAi.execute()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAi.java#L499) | 执行工具响应；终止工具由 [`TerminateTool`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/tools/TerminateTool.java) 提供结构化结束动作。 |
| Q3-Q4：状态推进与步数限制 | [`ShopAi.step()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAi.java#L540)、[`ShopAi.run()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAi.java#L574) | 按状态推进一次循环，并在最大步数内运行。 |
| Q6：工具调用轨迹 | [`ShopAi.logToolCalls()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAi.java#L188) | 记录工具名称、参数和调用标识，为审计及重复调用治理提供数据。 |

---

## 三、MCP 与工具体系

### Q7：为什么接入 MCP？

**短答：**

MCP 将外部能力标准化为工具协议，Agent 不必为每个搜索、文件或数据库服务编写一套私有适配。项目通过 Spring AI 的 `ToolCallbackProvider` 将框架内工具和 MCP 工具合并到统一工具集合，ReAct 主循环无需感知工具来源。

### Q8：AnySearch MCP 是如何接入的？

**短答：**

应用启动时由 Spring AI MCP Client 拉起 `mcp-remote` 的 stdio 代理，与 AnySearch 的远程 MCP 服务完成 initialize、initialized 和 tools/list 握手。联调时成功发现 `search`、`batch_search`、`extract`、`get_sub_domains` 四个工具，然后统一注册到 Agent。

### Q9：MCP 工具和本地 `@Tool` 有什么区别？

**短答：**

对模型来说都是带名称、描述和 JSON 参数的工具；区别在执行边界。本地工具在应用进程内执行，MCP 工具通过协议调用外部 Server。MCP 扩展性更好，但要额外考虑网络超时、服务可信度、工具描述注入和远程副作用。

### Q10：MCP 服务不可用时怎么办？

**短答：**

启动和执行阶段都要做降级：外部 Provider 不存在时保留本地工具；调用超时要返回结构化失败，避免主循环卡死；对搜索等可选能力可以降级回答，对订单、支付等关键动作则应明确终止。生产环境还应加健康检查、重试上限和熔断。

**本章代码溯源：**

| 问题 | 类 / 方法 | 实现职责 |
| --- | --- | --- |
| Q7/Q9：统一工具装配 | [`ShopAiFactory.buildToolCallbacks()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAiFactory.java#L278) | 将运行时本地工具转换为 ToolCallback，并与 MCP Provider 暴露的回调合并。 |
| Q8：MCP 启动配置 | [`application.yaml`](ShopAssistant/src/main/resources/application.yaml) 的 `spring.ai.mcp.client` | 通过 `npx.cmd + mcp-remote` 连接 AnySearch 远端 MCP。 |
| Q8/Q10：握手诊断 | [`McpDiagnostic`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/config/McpDiagnostic.java#L21) | 启动时输出 MCP Provider 与工具发现结果，便于定位连接问题。 |
| Q9：本地工具样例 | [`WebSearchTool.webSearch()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/tools/WebSearchTool.java#L77) | 展示进程内工具的参数校验、缓存、检索与结果格式化。 |

---

## 四、Skills 渐进式披露

### Q11：Skill、Tool 和 Specialist Agent 有什么区别？

**短答：**

- Tool 是一个可执行原子动作，例如查询数据库。
- Skill 是一套流程知识，告诉主 Agent 面对某类任务应该按什么步骤、使用哪些工具。
- Specialist Agent 有独立系统提示词、上下文和工具集合，适合需要隔离认知边界的复杂子任务。

Skill 不新建上下文，所以比 Specialist 更轻；它本身不执行动作，所以又不同于 Tool。

### Q12：什么是 Skills 渐进式披露？

**短答：**

第一层只把 Skill 的名称和一句话描述放进系统提示词，让模型知道有哪些能力；模型判断某个 Skill 适用时，再调用 `useSkill` 加载完整 `SKILL.md` 正文和推荐工具。这样目录常驻只消耗很少 Token，详细流程仅在需要时进入上下文。

### Q13：如何证明不是把 Skill 全文偷偷放进 Prompt？

**短答：**

项目有单元测试分别验证两层行为：`catalogForPrompt()` 只能看到 name 和 description，不能看到正文关键内容；调用 `UseSkillTool` 后才能获取完整 instructions。运行目录中也提供了 `order-refund/SKILL.md` 做真实加载测试。

### Q14：Skill 为什么使用 Markdown + YAML frontmatter？

**短答：**

YAML frontmatter 适合保存 name、description、allowedTools 等结构化元数据，Markdown 正文适合描述步骤、判断条件和注意事项。它既方便人维护，也方便 Agent 按需加载，而且不需要引入复杂的数据库管理界面。

### Q15：Skill 会带来什么安全风险？

**短答：**

Skill 本质上会进入模型上下文，可能包含提示词注入、越权指令或错误工具名称。因此候选 Skill 发布前必须做内容安全检查、目标名称一致性校验、frontmatter 校验，并且真正的权限仍由工具层控制，不能只相信 Skill 声明。

**本章代码溯源：**

| 问题 | 类 / 方法 | 实现职责 |
| --- | --- | --- |
| Q11-Q12：Skill 目录层 | [`SkillRegistry.catalogForPrompt()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/skill/SkillRegistry.java#L167) | 只输出 name、description 和推荐工具，不把完整正文常驻 Prompt。 |
| Q12-Q13：Skill 详情层 | [`UseSkillTool.useSkill()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/tools/UseSkillTool.java#L45) | 模型明确选择后才返回完整 Skill 指令。 |
| Q13：渐进披露测试 | [`SkillRegistryTest`](ShopAssistant/src/test/java/com/xmu/ShopAssistant/skill/SkillRegistryTest.java) | 验证目录不泄露正文、调用工具后才能读取完整内容。 |
| Q14：Skill 解析 | [`SkillRegistry.tryLoadOne()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/skill/SkillRegistry.java#L62) | 读取 `SKILL.md` 并解析 YAML frontmatter 与 Markdown 正文。 |
| Q15：候选安全校验 | [`EvolutionArtifactSafetyValidator.validate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/EvolutionArtifactSafetyValidator.java#L19) | 拦截敏感信息、越权语句，并检查 Skill 结构与目标名称。 |

---

## 五、RAG 检索与评估

### Q16：为什么使用 BGE-M3 + BM25 双路召回？

**短答：**

向量检索擅长语义相似，但对编号、专有名词和精确关键词可能不稳定；BM25 对关键词匹配敏感，但不能很好处理同义表达。双路召回可以互补，再通过 RRF 融合不同排名，避免直接比较两种检索器不可比的原始分数。

### Q17：RRF 是什么？

**短答：**

RRF 根据文档在每个召回列表中的名次计算融合分数，常见形式是：

`score(d) = Σ 1 / (k + rank_i(d))`

它不依赖向量分数和 BM25 分数处于同一量纲，工程上简单稳定。

### Q18：Recall@5 提升 17% 是怎么测的？

**短答：**

先构建包含 query 和相关文档标注的评测集，分别运行单路检索与融合检索，统计前 5 个结果是否覆盖全部相关文档。简历中的 17% 是融合方案相对原检索基线的 Recall@5 提升，不代表生成答案准确率提升 17%。

### Q19：HitRate、Recall、MRR、nDCG 分别看什么？

**短答：**

- HitRate：Top-K 是否至少命中一个相关结果。
- Recall：相关结果被召回了多少。
- MRR：第一个相关结果出现得是否足够靠前。
- nDCG：综合考虑多条相关结果的相关等级和排序位置。

### Q20：如何减少 RAG 幻觉？

**短答：**

除了提升召回，还要在 Prompt 中约束模型优先基于检索片段回答，并给片段连续编号，要求最终答案用 `[1][2]` 引用对应来源。没有足够证据时应明确说明，而不是补全不存在的信息。

**本章代码溯源：**

| 问题 | 类 / 方法 | 实现职责 |
| --- | --- | --- |
| Q16-Q17：双路召回与 RRF | [`RagServiceImpl.hybridSearchChunks()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/service/impl/RagServiceImpl.java#L89)、[`fuseByRrf()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/service/impl/RagServiceImpl.java#L103) | 分别获取向量/BM25 结果，再按排名倒数融合。 |
| Q18-Q19：离线评测 | [`RagEvaluationServiceImpl.evaluate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/service/impl/RagEvaluationServiceImpl.java#L26) | 计算 HitRate、Recall、MRR、nDCG，并输出逐样本和聚合结果。 |
| Q18：评测接口 | [`RagEvaluationController.evaluate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/controller/RagEvaluationController.java#L29) | 暴露 `/api/rag/evaluate`，接收带相关文档标注的评测集。 |
| Q20：知识工具 | [`KnowledgeTools.knowledgeQuery()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/tools/KnowledgeTools.java#L45) | 调用混合检索并将知识片段组织为模型可用的工具结果。 |

---

## 六、Multi-Agent 与流式交互

### Q21：为什么使用 Supervisor/Worker？

**短答：**

Supervisor 负责理解目标和选择 Specialist，Worker 负责在独立提示词与工具集合中完成垂直任务。这样可以减少单 Agent 系统提示词膨胀，也能隔离不同领域的上下文和权限。

### Q22：为什么不是所有任务都拆成 Multi-Agent？

**短答：**

多 Agent 会增加路由调用、上下文复制、Token 和延迟。简单任务由主 Agent 或 Skill 完成更高效；只有当子任务需要独立角色、专用工具或上下文隔离时，才值得委派给 Specialist。

### Q23：流式输出是怎么实现的？

**短答：**

后端从阻塞 `.call()` 改为 `.stream().chatResponse()`，每收到一个 chunk 就累积文本并通过 SSE 推送。流开始使用临时 stream id，最终消息持久化后，再在 done 事件中附带真实 message id，前端据此替换占位消息。

### Q24：为什么 SSE 需要心跳？

**短答：**

代理和网关通常会清理长时间没有数据的连接。后端定时发送 SSE 注释帧作为心跳，客户端不会当成业务消息处理；发送失败时立即移除断开的 emitter，避免连接泄漏。

### Q25：Token 用量如何统计？

**短答：**

在流结束后从 `ChatResponse` metadata 中提取 prompt、completion 和 total token，同时记录实际模型与 finish reason，保存到消息 metadata。前端既展示单条消息用量，也聚合会话总量，为后续成本分析和模型路由提供数据。

**本章代码溯源：**

| 问题 | 类 / 方法 | 实现职责 |
| --- | --- | --- |
| Q21-Q22：Supervisor 创建 | [`ShopAiFactory.createSupervisor()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAiFactory.java#L395) | 为主 Agent 组装委派工具和 Supervisor 系统提示词。 |
| Q21：任务委派 | [`DelegateToSpecialistTool.delegate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/multi/DelegateToSpecialistTool.java#L60) | 将 specialistAgentId、任务和会话交给 Worker 运行器。 |
| Q21-Q22：Worker 执行 | [`SpecialistRunner.run()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/service/SpecialistRunner.java#L84) | 使用 Specialist 独立配置执行子任务，并把结果回传主 Agent。 |
| Q23：流式分片 | [`ShopAi.emitStreamChunk()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAi.java#L448) | 推送增量文本、临时流标识和最终消息标识。 |
| Q24：连接与心跳 | [`SseServiceImpl.connect/heartbeat`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/service/impl/SseServiceImpl.java#L33) | 管理 emitter 生命周期、心跳与断线清理。 |
| Q25：Token 提取 | [`ShopAi.extractUsage()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/ShopAi.java#L478) | 从最终 ChatResponse metadata 提取 Token 用量。 |

---

## 七、工具安全与 Guardrails

### Q26：数据库工具如何防止危险 SQL？

**短答：**

工具层不是只判断是否以 SELECT 开头，而是先剥离注释、拒绝多语句、限制 SELECT/WITH、按词边界拦截写操作关键字、自动追加 LIMIT，并设置只读连接、超时和最大返回行数。即使 Prompt 被攻击，执行层仍然有独立安全边界。

### Q27：文件系统工具如何做沙箱？

**短答：**

所有路径先相对沙箱根目录解析并 normalize，再校验结果仍以沙箱根开头；拒绝绝对路径、`..` 越界和敏感扩展名。写入与删除默认关闭，读取还有文件大小上限。

### Q28：邮件工具如何避免被滥用？

**短答：**

使用严格邮箱格式校验、收件人 allowlist、主题和正文长度限制、CR/LF header injection 防护、分钟级限流、每日上限和审计日志。默认空白名单意味着拒绝全部发送。

### Q29：Guardrails 放在模型层还是工具层？

**短答：**

两层都要有。输入/输出 Guardrails 用于拦截 Prompt Injection 和脱敏 PII；工具层负责最终权限和副作用控制。模型层规则可能被绕过，所以数据库只读、文件沙箱和邮件白名单必须在执行层强制实施。

**本章代码溯源：**

| 问题 | 类 / 方法 | 实现职责 |
| --- | --- | --- |
| Q26：SQL 安全 | [`DataBaseTools`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/tools/DataBaseTools.java) | 注释剥离、多语句/写关键字拒绝、LIMIT、超时和结果上限。 |
| Q27：文件沙箱 | [`FileSystemTools`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/tools/FileSystemTools.java) | 规范化路径并校验沙箱边界，控制读写、删除和文件大小。 |
| Q28：邮件安全 | [`EmailTools`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/tools/EmailTools.java) | 收件人白名单、长度与 Header Injection 校验，并接入限流审计。 |
| Q26-Q28：统一限流审计 | [`ToolGuard.check/audit`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/agent/tools/security/ToolGuard.java#L32) | 以工具和操作为维度执行 Token Bucket、每日配额和结构化审计。 |
| Q29：输入输出防线 | [`GuardrailsService.checkInput/scrubOutput`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/guardrails/GuardrailsService.java#L52) | 输入侧识别注入模式，输出侧脱敏 PII，并记录安全审计。 |

---

## 八、受控 Auto-Evolution

### Q30：Auto-Evolution 的完整链路是什么？

**短答：**

Assistant 消息完成后由 LLM-as-Judge 异步评分；系统将问题、回答、Prompt 版本、工具、Skill、Token 和评分脱敏后形成执行轨迹；低分轨迹用于生成版本化 Prompt/Skill 候选；候选对原问题进行无副作用回放并重新评分；达到质量阈值且通过安全校验后，还必须人工审批才能热加载，发布前内容会被保存用于回滚。

### Q31：为什么叫“受控” Auto-Evolution？

**短答：**

自动化的是低分发现、候选生成和回放评估，不是让模型直接修改生产配置。最终发布受状态机、分数阈值、安全检查和人工审批约束，并支持回滚，所以是 Human-in-the-loop 的受控演化。

### Q32：执行轨迹记录了什么？

**短答：**

包括 messageId、sessionId、问题、回答、Prompt 版本、本轮工具与 Skill、Token 用量、Judge 三维评分、平均分和低分标记。问题和回答在持久化前会复用 Guardrails 脱敏。

### Q33：低分样本如何定义？

**短答：**

当前按 correctness、completeness、relevance 三项平均分判断，默认不高于 3.0 标记为低分。阈值通过配置管理。生产环境还可以加入用户反馈、任务成功率、工具错误率和业务指标，避免只依赖 Judge。

### Q34：候选 Prompt/Skill 如何生成？

**短答：**

系统读取目标当前基线和最多 20 条低分轨迹，交给专用候选生成 Prompt，让模型输出完整候选内容与修改理由。候选有独立 UUID、基线版本、候选版本、来源轨迹和 `DRAFT` 状态，生成后不会自动生效。

### Q35：为什么回放时不挂载工具？

**短答：**

历史问题可能触发数据库、邮件或文件操作。如果评估候选时真实执行工具，会重复产生业务副作用，也会让评估不可复现。因此当前回放只验证候选对回答质量的影响，不执行真实工具。

**局限：**这意味着工具选择能力还没有被完整评估。下一步可以提供只读 Mock Tool、录制回放或沙箱环境。

### Q36：发布门槛是什么？

**短答：**

当前默认要求候选三维平均分至少 3.2，并且相对历史基线至少提升 0.2；所有样本必须成功完成评分。门槛通过配置管理，未达标候选停留在 `EVALUATED`，不能审批。

### Q37：为什么 LLM-as-Judge 可信？

**短答：**

它不是绝对真值，所以项目通过固定 JSON Schema、三维评分、基线对照和多样本聚合降低随机性，并保留人工审批。进一步可以使用与生成模型不同的 Judge、重复评分取中位数、加入人工标注集做 Judge 校准。

### Q38：如何防止候选把 API Key 或越权指令写进去？

**短答：**

候选在评估前和发布前都会检查长度、疑似 API Key/密码、绕过审批或安全边界的表达。Skill 还要检查 frontmatter 和目标 name 一致性。即使通过文本检查，底层工具权限仍然不会被候选修改。

### Q39：Prompt 和 Skill 如何热加载？

**短答：**

PromptRegistry 增加活动覆盖层，原来的 `render/get` 会优先读取活动版本；SkillRegistry 会解析并校验完整候选 `SKILL.md` 后更新内存注册表。classpath Prompt 和仓库中的 Skill 文件不直接覆盖，因此基线仍然存在。

### Q40：应用重启后活动版本会丢吗？

**短答：**

不会。活动制品会原子持久化到 `data/evolution/active`，应用启动时由 ArtifactManager 重新加载并应用。发布时还会保存 previousContent，回滚后恢复并再次持久化。

### Q41：为什么用 JSON 文件而不是数据库？

**短答：**

当前阶段重点是验证闭环，文件存储部署成本低、版本内容可直接审查，也避免引入数据库迁移。为了可靠性采用临时文件加原子替换，并隔离损坏文件。高并发或多实例环境应迁移到数据库或对象存储，增加唯一约束、事务、分布式锁和审计表。

### Q42：状态机如何避免非法操作？

**短答：**

正常路径是 `DRAFT → EVALUATING → EVALUATED → APPROVED → ACTIVE → ROLLED_BACK`。服务层对每个操作校验当前状态；评估异常会退回 DRAFT；未通过评分不能审批；未审批不能发布；只有 ACTIVE 能回滚。同一目标也只允许一个活动候选。

### Q43：Auto-Evolution 最大的工程风险是什么？

**短答：**

一是对低分样本过拟合，二是 Judge 偏差，三是候选引入权限或提示词安全问题，四是发布状态与活动制品不一致。对应措施是多样本聚合、基线对照、独立安全检查、人工审批、原子持久化和回滚。生产化还需要灰度流量、在线指标和事务型制品仓库。

### Q44：端到端测试结果能证明候选一定更好吗？

**短答：**

不能。联调中使用一条隔离测试样本验证了完整工程链路，Judge 分数从 1.33 到 5.00，只能证明生成、回放、审批、发布和回滚可以工作，不能当成统计意义上的模型提升结论。真实效果必须在更大、分层且有人类标注的评测集上验证。

**本章代码溯源：**

| 问题 | 类 / 方法 | 实现职责 |
| --- | --- | --- |
| Q30-Q33：评分与轨迹 | [`AgentEvalService.evaluateAndPushAsync()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/eval/AgentEvalService.java#L69)、[`EvolutionTraceService.capture()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/EvolutionTraceService.java#L40) | 异步评分后聚合问题、回答、工具、Skill、Token 和低分标记。 |
| Q34：候选生成 | [`EvolutionCandidateService.generate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/EvolutionCandidateService.java#L39)、[`LlmEvolutionCandidateGenerator.generate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/LlmEvolutionCandidateGenerator.java#L33) | 选取低分轨迹和当前基线，调用模型生成完整候选及理由。 |
| Q35-Q37：无副作用回放 | [`LlmEvolutionReplayEvaluator.evaluate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/LlmEvolutionReplayEvaluator.java#L28) | 不注册真实工具，对基线与候选回答执行同口径 Judge 评分。 |
| Q36/Q42：门槛与状态机 | [`EvolutionWorkflowService.evaluate/approve/activate/rollback`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/EvolutionWorkflowService.java#L29) | 校验分数门槛和合法状态迁移，阻止跳步发布。 |
| Q38：安全校验 | [`EvolutionArtifactSafetyValidator.validate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/EvolutionArtifactSafetyValidator.java#L19) | 在评估和发布前检查密钥、越权语义、长度与 Skill frontmatter。 |
| Q39：Prompt 热加载 | [`PromptRegistry.activate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/prompt/PromptRegistry.java#L70) | 写入活动覆盖层，`get/render` 优先读取活动版本。 |
| Q39：Skill 热加载 | [`SkillRegistry.activate()`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/skill/SkillRegistry.java#L145) | 解析校验完整 Skill 后原子更新内存注册表。 |
| Q40-Q41/Q43：持久化与回滚 | [`EvolutionArtifactManager.activate/restore`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/EvolutionArtifactManager.java#L54)、[`EvolutionCandidateStore`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/EvolutionCandidateStore.java#L20) | 原子落盘活动制品与候选状态，保存 previousContent 并支持重启恢复。 |
| Q44：工作流验证 | [`EvolutionWorkflowServiceTest`](ShopAssistant/src/test/java/com/xmu/ShopAssistant/evolution/EvolutionWorkflowServiceTest.java)、[`EvolutionArtifactManagerTest`](ShopAssistant/src/test/java/com/xmu/ShopAssistant/evolution/EvolutionArtifactManagerTest.java) | 覆盖回放门禁、审批、发布、活动制品恢复及回滚；完整 HTTP 链路另通过本地联调验证。 |

---

## 九、测试、排障与工程取舍

### Q45：项目如何测试？

**短答：**

后端完整测试集 18 项全部通过，包含真实 DeepSeek 基础对话、多轮记忆、ReAct 工具调用、Spring 上下文、MCP 握手，以及演化模块的轨迹、候选、状态机、发布恢复和回滚测试。还通过 HTTP 跑通完整 Auto-Evolution 链路；前端 lint 和 build、后端 package 均通过。

### Q46：开发中遇到的一个真实启动问题是什么？

**短答：**

Spring AI 1.1 在同时引入多个模型 Starter 时，会默认尝试自动装配未配置的智谱 Chat、Embedding 和 Image Model，导致只有 DeepSeek Key 时也启动失败。我通过 `spring.ai.model.chat` 明确选择主模型，并关闭未使用的 embedding/image 自动装配，同时让智谱 ChatClient 条件创建，最终实现 DeepSeek 单模型启动。

### Q47：为什么 API Key 不能写在 application.yaml？

**短答：**

配置文件会进入 Git 历史，即使后续删除也可能泄漏。项目将 DeepSeek、智谱、数据库和邮件凭据全部改为环境变量读取，并在提交前扫描疑似 Key。测试 Key 只注入临时进程，服务验证完成后立即停止。

### Q48：如果让你继续做，优先级最高的三件事是什么？

**短答：**

1. 将文件制品仓库升级为数据库/对象存储，完善多实例事务和审计。
2. 建立分层人工标注评测集，对 Judge 做校准，并支持多 Judge/重复评分。
3. 增加灰度发布、在线任务成功率、用户反馈和自动回滚，把离线回放与线上指标连接起来。

### Q49：为什么不直接微调模型？

**短答：**

当前问题主要是流程指令、工具选择和知识变化，Prompt/Skill 迭代成本更低、可解释、可快速回滚。只有当大量稳定样本表明能力缺口无法通过上下文和工具解决，而且收益能覆盖训练与部署成本时，才考虑 SFT 或偏好优化。

### Q50：这个项目最能体现你的哪项能力？

**短答：**

我不仅能调用大模型 API，还能把不确定的模型能力放进可约束、可观测、可测试、可演化的后端系统：从 ReAct 与工具协议，到安全沙箱、评测、版本治理、审批和回滚，关注的是完整 Agent 工程生命周期。

**本章代码溯源：**

| 问题 | 类 / 配置 | 实现职责 |
| --- | --- | --- |
| Q45：后端测试 | [`src/test/java`](ShopAssistant/src/test/java) | 覆盖 Agent、MCP、Skill、Guardrails、评测和演化模块。 |
| Q46：多模型装配 | [`MultiChatClientConfig`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/config/MultiChatClientConfig.java#L13)、[`ChatClientRegistry`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/config/ChatClientRegistry.java#L9) | 明确主 ChatModel，并按名称管理可用 ChatClient。 |
| Q46-Q47：环境变量配置 | [`application.yaml`](ShopAssistant/src/main/resources/application.yaml) 的 `spring.ai`、`spring.datasource` | 从环境变量读取模型与数据库凭据，关闭未使用模型自动装配。 |
| Q48：当前配置边界 | [`EvolutionProperties`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/evolution/EvolutionProperties.java#L10) | 集中管理低分阈值、候选门槛、存储目录和模型选择。 |
| Q49-Q50：可回滚优化载体 | [`PromptRegistry`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/prompt/PromptRegistry.java#L32)、[`SkillRegistry`](ShopAssistant/src/main/java/com/xmu/ShopAssistant/skill/SkillRegistry.java#L32) | 以低成本、可解释、可回滚方式迭代流程能力。 |

---

## 十、面试前速记

### 一张图记住 Auto-Evolution

```text
回答持久化
   ↓
LLM-as-Judge 三维评分
   ↓
脱敏执行轨迹 ──→ 低分样本池
                    ↓
            Prompt/Skill 候选
                    ↓
              无副作用回放
                    ↓
             质量门槛 + 安全检查
                    ↓
                 人工审批
                    ↓
           热加载 + 持久化 + 回滚
```

### 必须主动说明的边界

- Auto-Evolution 是受控闭环，不是模型直接修改生产配置。
- HTTP 联调的 1.33 → 5.00 是单条链路验证，不是统计效果结论。
- 当前回放不执行真实工具，下一步需要 Mock/录制回放覆盖工具选择质量。
- 当前制品用本地 JSON 文件，单机适用；多实例需要事务仓库与分布式协调。
- LLM-as-Judge 需要人工标注集校准，不能当作绝对真值。

### 项目数字

- AnySearch MCP：4 个工具。
- RAG：BGE-M3 + BM25 + RRF，Recall@5 相对基线提升 17%。
- 演化默认低分阈值：平均分 ≤ 3.0。
- 候选默认门槛：平均分 ≥ 3.2，且相对基线提升 ≥ 0.2。
- 单候选最多吸收：20 条低分轨迹。
- 后端完整回归：18 项测试全部通过。

---

## 十一、反问面试官

1. 团队当前 Agent 的主要瓶颈更偏向模型效果、工具稳定性，还是评测与可观测性？
2. 线上是否已经建立任务成功率、人工反馈和 Token 成本的统一指标？
3. Prompt、Skill 和工具版本目前如何做灰度、回滚与审计？
4. 团队如何处理 LLM-as-Judge 的偏差和评测集污染？
5. 对这个岗位，入职前三个月最希望解决的 Agent 工程问题是什么？

---

**最后提醒：**回答时先说结论，再讲实现，最后主动说明边界和下一步。能清楚说出取舍，比单纯堆技术名词更有说服力。
