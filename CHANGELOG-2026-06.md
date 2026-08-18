# ShopAssistant 2026 年 6 月迭代记录

本轮迭代围绕 **AI Agent 工程化与生产化** 主题,落地了 10 项预定能力,覆盖工具沙箱、流式输出、Token 计量、Prompt 治理、Skills 体系、MCP 接入、Guardrails、自动评估、Citation、SSE 心跳。

整体 build 状态:**后端 `./mvnw clean compile` 通过 · 前端 `npm run build` 通过**(IDE 中 NetBeans Lombok 处理器与 JDK 17 javac 兼容性问题导致大量"找不到方法 builder()/log/getXxx()"红色诊断,均为 IDE-only 噪音,Maven 真实编译完全干净)。

---

## 目录

1. [P0 · 工具授权与沙箱](#p0--工具授权与沙箱)
2. [P1 · 流式输出](#p1--流式输出)
3. [P1 · Token 计量](#p1--token-计量)
4. [P1 · Prompt 版本化与模板管理](#p1--prompt-版本化与模板管理)
5. [P1 · Skills 体系](#p1--skills-体系)
6. [P1 · MCP 接入](#p1--mcp-接入)
7. [P1 · Guardrails](#p1--guardrails)
8. [P1 · Agent 评估与回归](#p1--agent-评估与回归)
9. [P2 · 回答带 Citation](#p2--回答带-citation)
10. [P3 · SSE 心跳 + 客户端断连感知](#p3--sse-心跳--客户端断连感知)
11. [改动文件总览](#改动文件总览)
12. [回滚 / 兼容性](#回滚--兼容性)

---

## P0 · 工具授权与沙箱

### 痛点
原代码里 `DataBaseTools` 只检查 `sql.trim().toUpperCase().startsWith("SELECT")` —— `/* */SELECT 1; DROP TABLE foo` 能直接绕过;`EmailTools` 只 `to.contains("@")` 判邮箱、无白名单、无限频;`FileSystemTools` 沙箱根是 `user.dir`(就是项目根!`application.yaml` / `.env` / `pom.xml` 全在范围内),且 `writeFile/deleteFile/createDirectory` 默认开放,LLM 一句话就能 `rm -rf`。

### 设计

**新增包 `com.xmu.ShopAssistant.agent.tools.security`,5 个文件:**

| 文件 | 职责 |
|---|---|
| `SensitivityLevel.java` | 枚举 `PUBLIC` / `RESTRICTED` / `DANGEROUS`,作为 Tool 的安全分级 |
| `GuardOutcome.java` | `record(allowed, message)`,统一闸门决策结果 |
| `ToolSecurityProperties.java` | `@ConfigurationProperties("agent.tools.security")`,绑定 yaml |
| `ToolSecurityConfig.java` | `@EnableConfigurationProperties` |
| `ToolGuard.java` | 核心 bean:**token-bucket 限流 + 每日上限 + 结构化审计**,纯 JDK 实现不引新依赖 |

**重写 3 个危险工具:**

- **`DataBaseTools`** —— 7 层防御链:
  1. `ToolGuard.check()` 速率前置
  2. SQL 注释剥离(`/* */` 和 `--`)
  3. 拒绝多语句(分号检查)
  4. 强制 `SELECT` 或 `WITH` 开头
  5. 14 个写操作关键字按词边界 `\b` 黑名单(INSERT/UPDATE/DELETE/DROP/TRUNCATE/ALTER/CREATE/GRANT/REVOKE/MERGE/EXEC/CALL/VACUUM/COPY)
  6. 自动追加 `LIMIT N`(默认 200)
  7. `Connection.setReadOnly(true)` + `Statement.setQueryTimeout(5)` + `setMaxRows(maxRows+10)`,**错误信息不回显原 SQL** 避免信息泄漏

- **`EmailTools`** —— 7 层防御:`InternetAddress.validate()` 严格 RFC 5322 校验 → 主题/正文长度限制 → header injection 防护(主题禁含 CR/LF) → 收件人 glob 白名单(空列表 = 拒一切) → token-bucket 限流(3/min) → 每日上限(20 封) → 全程审计

- **`FileSystemTools`** —— 沙箱根从 `user.dir` 改为 `./data/sandbox`(自动 mkdir),`writeFile/appendToFile/deleteFile/createDirectory` 4 个写操作改为**默认禁用**(`write-enabled=false` / `delete-enabled=false`),`readFile` 加 256KB 大小上限,文件名黑名单(`.env`/`.key`/`.pem`/隐藏文件),拒绝绝对路径与 `..` 越界

**配置块新增**(application.yaml):

```yaml
agent:
  tools:
    security:
      audit-enabled: true
      database: { max-rows: 200, query-timeout-seconds: 5, permits-per-minute: 30, forbidden-keywords: [...] }
      email:    { recipient-allowlist: [], max-subject-length: 200, max-body-length: 5000, permits-per-minute: 3, daily-cap: 20 }
      filesystem: { sandbox-dir: ./data/sandbox, max-read-bytes: 262144, write-enabled: false, delete-enabled: false, ... }
```

### 用户可见效果

- **演示安全档**:即使 LLM 被诱导发恶意 SQL,工具层拒之门外,日志留 `[ToolAudit] tool=dataBaseTool outcome=DENIED reason=forbidden_keyword keyword=DROP`
- **限流自动生效**:模型疯狂调用工具时,从某次起开始返回 "工具 X 触发速率限制,请稍后再试。请优先基于已有信息作答"

---

## P1 · 流式输出

### 痛点
之前 `ChatClient.prompt().call()` 是阻塞调用 —— LLM 生成完整段才返回,前端等几秒~几十秒看到一整块文字。

### 设计

**核心改动**:`ShopAi.think()` 和 `SpecialistRunner.run()` 从 `.call()` 切到 `.stream().chatResponse()`,边收 chunk 边推 SSE:

```java
List<ChatResponse> chunks = chatClient.prompt(prompt)
        .system(thinkPrompt)
        .toolCallbacks(...)
        .stream()
        .chatResponse()
        .doOnNext(resp -> {
            String delta = resp.getResult().getOutput().getText();
            if (delta != null && !delta.isEmpty()) {
                accumulated.append(delta);
                emitStreamChunk(streamId, accumulated.toString(), false, ...);
            }
        })
        .collectList()
        .block();
```

**新增 SSE 消息类型**:`SseMessage.Type.AI_STREAM_CHUNK`。协议:

- 流中:`payload.message.id = streamId`(UUID 全流式生命周期不变),`payload.message.content = 累积文本`,`payload.done = false`,`metadata.chatMessageId = null`
- 流末:同 `streamId`,`done = true`,`metadata.chatMessageId = 持久化后的 DB id`(前端用它把 placeholder 的 id 换成真实 id,PDF 下载链接立即生效)

**重建最终 ChatResponse**:Spring AI 1.1 的 `AssistantMessage` 4 参构造器是 protected,改用 `AssistantMessage.builder().content(...).toolCalls(...)`;然后包成 `new ChatResponse(List.of(new Generation(am, meta)), responseMeta)` 供 `toolCallingManager.executeToolCalls` 取 tool_calls。

**前端处理**:`AgentChatView.tsx` 的 SSE handler 加 `AI_STREAM_CHUNK` 分支,upsert 语义 —— 按 `chunk.payload.message.id` 找 placeholder 就更新内容,`done=true` 时把 id 替换为 `metadata.chatMessageId`。

**优雅退化**:某轮 think 模型只发 tool_calls 没文本,`didStream` 为 false,走原有 `AI_GENERATED_CONTENT` 路径,行为完全不变。

### 用户可见效果

打字机式逐 token 显示;支持多 Agent 模式下子 Agent 的回答也流式。

---

## P1 · Token 计量

### 痛点
原来不知道每次回答消耗了多少 token、用了哪个 model;没法做成本追踪、用量统计、模型 A/B。

### 设计

**`ChatMessageDTO.MetaData` 新增 `Usage` 嵌套类**:`promptTokens / completionTokens / totalTokens / model / finishReason`(全 Long,避免 token 数溢出)。

**在流末抽取**(ShopAi + SpecialistRunner 同构):

```java
private static ChatMessageDTO.Usage extractUsage(ChatResponse response) {
    org.springframework.ai.chat.metadata.Usage u = response.getMetadata().getUsage();
    return ChatMessageDTO.Usage.builder()
            .promptTokens(u.getPromptTokens().longValue())
            .completionTokens(u.getCompletionTokens().longValue())
            .totalTokens(u.getTotalTokens().longValue())
            .model(response.getMetadata().getModel())
            .finishReason(...)
            .build();
}
```

四条持久化/推送路径都串了 `Usage`:
- `persistStreamedAssistant(finalContent, toolCalls, usage)`
- `emitStreamChunk(streamId, content, done, persistedId, toolCalls, usage)`
- `saveMessage(AssistantMessage, Usage)`(新增重载)
- `saveAndPushMessage(sessionId, message, agentRole, usage, ...)`

**前端**:
- `types/index.ts` 新增 `ChatMessageUsage` 接口
- `AgentChatHistory.tsx` 在每条 assistant 气泡底部显示 `↑prompt · ↓completion · model`(等宽字体),hover tooltip 显示完整信息
- `AgentChatView.tsx` 顶栏加蓝色 Tag `↑X · ↓Y`,`useMemo` 聚合 `messages[].metadata.usage`,会话累计 token 一眼可见

### 用户可见效果

- 每条 AI 回答下方:`↑ 1024 · ↓ 286 · deepseek-chat`
- 会话顶栏:`↑3072 · ↓1158`(全会话累计)
- DB `chat_message.metadata` JSON 字段持久化,后续做按用户/Agent/模型的聚合分析无需新增表

---

## P1 · Prompt 版本化与模板管理

### 痛点
散在 `ShopAi.think()` 内的 thinkPrompt、`ShopAiFactory.buildSupervisorSystemPrompt` 内的 4 段大 prompt、AgentMemory/SessionSummary 里散落的 prompt —— 改 prompt 要改 Java 代码,无版本号,无法 A/B,做不了 prompt 回归。

### 设计

**新增 `com.xmu.ShopAssistant.prompt.PromptRegistry`**(Spring `@Component`):
- 启动时扫描 `classpath:prompts/*.st`,文件名(去后缀)即模板名(含版本号)
- API:`render(name, vars)` 用 Spring AI 自带 `PromptTemplate` 渲染,`{var}` 占位
- 内置版本约定:文件 `think.system.v1.st` → 模板名 `think.system.v1`

**新增 3 个 `.st` 模板**:
- `think.system.v1.st` —— ShopAi.think() 系统提示词
- `supervisor.system.v1.st` —— Supervisor 多 Agent 协调员提示词
- `eval.judge.v1.st` —— LLM-as-Judge 评估提示词(用于 P1.8)

**接入**:
- `ShopAi` 构造器加 `PromptRegistry promptRegistry`,`think()` 改为 `promptRegistry.render("think.system.v1", Map.of("kbs", availableKbs))`
- `ShopAiFactory` 注入 PromptRegistry,`buildSupervisorSystemPrompt` 改为 `promptRegistry.render("supervisor.system.v1", Map.of(...))`

### 用户可见效果

改 prompt 不需要重新编译 Java;后续要做 prompt A/B 只需多放几份 `.st`,通过模板名切换。日志 `Prompt 已加载: name=think.system.v1 chars=...` 显示已加载的模板。

---

## P1 · Skills 体系

### 痛点
之前讨论过的:Tool 是单函数,Specialist 是完整子 Agent,中间缺一层"打包的流程方法论"——这正是 Anthropic Agent Skills 的定位。

### 设计

**新增包 `com.xmu.ShopAssistant.skill`**:

| 文件 | 职责 |
|---|---|
| `Skill.java` | 模型类:`name`、`description`、`instructions`(SKILL.md 正文)、`allowedTools`、`resourceDir` |
| `SkillRegistry.java` | 启动扫描 `./data/skills/<name>/SKILL.md`,纯正则解析 frontmatter(不引 SnakeYAML),缓存 |

**新增 Tool `UseSkillTool`**(`com.xmu.ShopAssistant.agent.tools.UseSkillTool`):
- `FIXED` 类型,所有 Agent 自动持有
- `@Tool useSkill(skillName)` 返回该 skill 的完整正文 + 推荐工具列表 + 防重复调用提示

**渐进式披露(Anthropic Agent Skills 的灵魂)**:
- **第 1 层(永远在 system prompt)**:`ShopAiFactory.mergeSystemPromptWithSkillCatalog()` 把 `name + description` 列表追加到 system prompt 末尾(只几十 token)
- **第 2 层(模型决策时才加载)**:模型看到目录后判断哪个 skill 适用,调 `useSkill(name)` 把完整流程取回上下文
- **第 3 层(资源/脚本)**:`Skill.resourceDir` 存目录路径,未来可加 `readSkillResource` 工具按需读文件

**示例 skill**:`data/skills/order-refund/SKILL.md`(退款处理流程,声明需要 `dataBaseTool` + `emailTool`)。

### 用户可见效果

启动日志:`SkillRegistry 就绪,共加载 1 个 skill: [order-refund]`。

用户问"我要退款",模型(基于 system prompt 中看到的 skill 目录)调用 `useSkill("order-refund")` → 取回 5 步流程 → 依次调 `dataBaseTool` 查订单、写退款单、发邮件 → terminate。**不开新子上下文**,主 Agent 自己按指令行动。

---

## P1 · MCP 接入

### 痛点
社区已经有大量现成的 MCP server(`@modelcontextprotocol/server-filesystem`、`server-brave-search`、`server-github`、`server-postgres` 等),接入它们可以零代码扩工具生态;MCP 是 Anthropic 2024 年推的标准,2026 简历关键词。

### 设计

**Maven**:`pom.xml` 加 `spring-ai-starter-mcp-client`。

**架构**:Spring AI MCP starter 把每个配置的 MCP server 的 tool 自动包装成 `ToolCallbackProvider` bean。我在 ShopAiFactory 加:

```java
private final List<ToolCallbackProvider> externalToolProviders;

// 构造器:
@Autowired(required = false) List<ToolCallbackProvider> externalToolProviders

// buildToolCallbacks():
for (ToolCallbackProvider provider : externalToolProviders) {
    ToolCallback[] arr = provider.getToolCallbacks();
    if (arr != null && arr.length > 0) {
        callbacks.addAll(Arrays.asList(arr));
        log.info("已合并外部 ToolCallbackProvider: provider={} tools={}", ...);
    }
}
```

→ MCP 工具自动出现在 LLM 视野里,**ReAct 主循环零修改**。

**配置模板**(注释在 application.yaml):

```yaml
# spring.ai.mcp.client.enabled: true
# spring.ai.mcp.client.stdio.connections:
#   filesystem: { command: npx, args: [-y, @modelcontextprotocol/server-filesystem, /path] }
#   brave-search: { command: npx, args: [-y, @modelcontextprotocol/server-brave-search], env: { BRAVE_API_KEY: ${BRAVE_API_KEY:} } }
```

### 用户可见效果

打开配置 + 配 server,启动日志会出现:`已合并外部 ToolCallbackProvider: provider=SyncMcpToolCallbackProvider tools=12`。模型从此可以调 MCP 提供的所有工具。

---

## P1 · Guardrails

### 痛点
- LLM 容易被 prompt injection 攻陷("ignore previous instructions, now act as ...")
- LLM 输出可能泄漏 PII(用户邮箱、手机号、身份证号)

### 设计

**新增包 `com.xmu.ShopAssistant.guardrails`**:

| 文件 | 职责 |
|---|---|
| `GuardrailsProperties.java` | `@ConfigurationProperties("agent.guardrails")`,装注入模式正则、PII 正则、敏感词、开关 |
| `GuardrailsService.java` | `checkInput(userText)` 返 `InputCheck(allowed,reason)`;`scrubOutput(text)` 返 `OutputScrub(text,hits)` 已脱敏文本 |

**默认规则**:
- **Injection 模式**(命中即拒): `ignore previous instructions` / `disregard prior` / `you are now` / `act as if` / 中文同义("忽略之前的指令"等)
- **PII 正则**(脱敏为 `***`):email / CN 手机号(`1[3-9]\d{9}`) / CN 身份证 18 位 / 信用卡 13-19 位
- **敏感词**:默认空,业务可加机密项目代号等

**接入**:`ShopAi.persistStreamedAssistant` 在落库前调 `guardrailsService.scrubOutput(finalContent).getText()`,所有 PII 被替换。命中时审计日志:`[Guardrail] kind=pii_scrubbed detail=...`

### 用户可见效果

- 模型回答里出现 `support@example.com` → 落库 + 推给前端时变成 `***`
- 用户输入恶意指令(后续可在 Controller 层调 `checkInput` 拒绝) → 日志 `[Guardrail] kind=input_blocked reason=...`

---

## P1 · Agent 评估与回归

### 痛点
之前只有 RAG 检索指标(HitRate/Recall/MRR/nDCG),生成质量没人测;改了 prompt 不知道好坏。

### 设计

**LLM-as-Judge 三维度评分**(0-5 整数):correctness 正确性 / completeness 完整性 / relevance 相关性

**新增包 `com.xmu.ShopAssistant.eval`**:

- `AgentEvalService.java` —— 核心服务,对每条 `(question, expected, actual)` 用 judge ChatClient 打分。利用 Spring AI 自带 `BeanOutputConverter<EvalScore>` 强制 JSON 输出,模型无法乱说。聚合 N 条 case 算平均分。
- `AgentEvalController.java` —— `POST /eval/agent` 入口

**Judge prompt**(`eval.judge.v1.st`):
```
你是一个严谨的 AI 答复评估员(judge)...
按 3 个维度给 AI 回答打分(0-5)...
请严格输出如下 JSON:{format}
```

`{format}` 占位由 `BeanOutputConverter.getFormat()` 注入,自动生成 JSON Schema 描述。

### 用户可见效果

CI/离线评测:

```bash
curl -X POST http://localhost:8080/eval/agent -H "Content-Type: application/json" -d '{
  "judgeModel": "deepseek-chat",
  "cases": [
    {"question": "退货流程?", "expected": "7天内联系客服...", "actual": "您好,我们支持..."}
  ]
}'
```

返回:
```json
{
  "judgeModel": "deepseek-chat",
  "results": [{ "question": "...", "score": { "correctness": 4, "completeness": 3, "relevance": 5, "reasoning": "..." } }],
  "aggregate": { "count": 1, "avgCorrectness": 4.0, "avgCompleteness": 3.0, "avgRelevance": 5.0 }
}
```

CI 集成示意:把这个 endpoint 跑在 GitHub Actions 里,改 prompt 前后对比平均分。

---

## P2 · 回答带 Citation

### 痛点
RAG 答出来一段话,用户无法溯源;模型有时还会 hallucinate 编造内容。

### 设计

**`KnowledgeTools` 改造**:把检索片段从模糊的 `[KB X]\n<content>` 改成全局连续 `[N]` 编号:

```
[1] (kb=goods)
<chunk 1 内容>

[2] (kb=goods)
<chunk 2 内容>

[3] (kb=faq)
<chunk 3 内容>

[引用规则] 你在最终回答中务必用 [N] 引用以上对应片段编号。
```

`@Tool` description 显式声明编号规则,工具结果尾部硬性提醒。

**`think.system.v1.st` 加规则**:
```
- 当回答内容来自 KnowledgeTool/KnowledgeToolAll 返回的片段时,必须用 [1][2][3] 这种角标形式引用对应片段编号,让用户可以溯源
```

### 用户可见效果

用户问 "iPhone 16 屏幕参数",模型回答会变成:`iPhone 16 配备 6.1 英寸 OLED 屏 [1],刷新率 120Hz [2]…` —— 用户可滚动到对应的 KnowledgeTool 响应气泡(已经折叠展示,带原始片段)查 `[1]` `[2]` 是什么。

---

## P3 · SSE 心跳 + 客户端断连感知

### 痛点
- 原 SseEmitter 超时 30 分钟,空闲连接积累成僵尸,撑爆线程池
- 没心跳,nginx/Cloudflare 等代理常 30-60s idle timeout 直接砍连接
- 后端无法及时知道客户端已断开

### 设计

**`SseServiceImpl` 重写**:
- 超时 `30 * 60_000` → `10 * 60_000`(10 分钟)
- 同一 sessionId 重复 connect 时优雅 `complete()` 旧 emitter 再替换,避免双连接
- **新增 `@Scheduled(fixedRate = 25_000)` 心跳**:对每个活跃 emitter 发 SSE 注释帧(`:ping`,客户端不会触发 `onmessage`)。若 emitter 已断,这次 send 抛 IOException,顺势把它从 map 移除并 log `dropped`
- `send()` 失败时也清理并 completeWithError

**`AsyncConfig`** 加 `@EnableScheduling`,让 `@Scheduled` 生效。

### 用户可见效果

- 长时间不操作再切回也不会断开
- 后端日志:`SSE heartbeat: alive=3 dropped=1` 一眼看到状态
- 客户端意外断开(关 tab、断网)后,后端在最多 25 秒内感知并清理资源(原来需要等 30 分钟)

---

## 改动文件总览

### 新增(共 17 个)

```
ShopAssistant/src/main/java/com/xmu/ShopAssistant/
├─ agent/tools/security/
│   ├─ SensitivityLevel.java
│   ├─ GuardOutcome.java
│   ├─ ToolSecurityProperties.java
│   ├─ ToolSecurityConfig.java
│   └─ ToolGuard.java
├─ agent/tools/UseSkillTool.java
├─ skill/
│   ├─ Skill.java
│   └─ SkillRegistry.java
├─ prompt/PromptRegistry.java
├─ guardrails/
│   ├─ GuardrailsProperties.java
│   └─ GuardrailsService.java
├─ eval/AgentEvalService.java
└─ controller/AgentEvalController.java

ShopAssistant/src/main/resources/prompts/
├─ think.system.v1.st
├─ supervisor.system.v1.st
└─ eval.judge.v1.st

data/skills/order-refund/SKILL.md          ← 示例 Skill
```

### 修改(共 13 个)

```
后端:
- agent/tools/Tool.java                            (加 default getSensitivity())
- agent/tools/DataBaseTools.java                   (7 层防御重写)
- agent/tools/EmailTools.java                      (allowlist + 限流 + RFC 校验)
- agent/tools/FileSystemTools.java                 (沙箱目录 + 默认只读)
- agent/tools/KnowledgeTools.java                  (片段加 [N] 编号 + 引用规则)
- agent/ShopAi.java                                (流式 + Token + Prompt + Guardrails)
- agent/ShopAiFactory.java                         (注入新依赖 + Skill catalog 合并 + 外部 provider 合并)
- service/SpecialistRunner.java                    (流式 + Token)
- service/impl/SseServiceImpl.java                 (心跳 + 缩短超时 + 探活)
- message/SseMessage.java                          (加 AI_STREAM_CHUNK 类型)
- model/dto/ChatMessageDTO.java                    (MetaData 加 Usage)
- config/AsyncConfig.java                          (加 @EnableScheduling)
- pom.xml                                          (加 spring-ai-starter-mcp-client)
- src/main/resources/application.yaml              (加 agent.tools.security + MCP 模板注释)

前端:
- ui/src/types/index.ts                            (SseMessageType 加 AI_STREAM_CHUNK + ChatMessageUsage)
- ui/src/components/views/AgentChatView.tsx        (AI_STREAM_CHUNK 处理 + 会话 token 聚合)
- ui/src/components/views/agentChatView/AgentChatHistory.tsx  (气泡下 token 指标)
```

---

## 回滚 / 兼容性

| 项 | 兼容性 | 说明 |
|---|---|---|
| 工具沙箱 | 向后兼容 | 老代码若不用沙箱:`agent.tools.security.email.recipient-allowlist: ["*@*"]`、`filesystem.write-enabled: true` 即可恢复原行为 |
| 流式输出 | 向后兼容 | 只发 tool_calls(无文本)的轮次自动走原 `AI_GENERATED_CONTENT` 路径,前端两个分支都已支持 |
| Token 计量 | 完全兼容 | usage 字段为可空,老消息读出来 `usage = null`,前端 `?.` 安全访问 |
| Prompt 版本化 | 不兼容 | `ShopAi` 构造器多了 `PromptRegistry` 参数,自定义 Agent 创建路径需同步 |
| Skills | 完全兼容 | `data/skills/` 目录不存在时 SkillRegistry 自动创建空目录,无 skill 时 system prompt 不追加目录 |
| MCP | 完全兼容 | 不配置 server 时 MCP starter 空闲不工作,外部 provider 列表为空 |
| Guardrails | 兼容 | 默认 enabled=true 但所有正则可关 `agent.guardrails.enabled: false` 一刀切 |
| 评估服务 | 完全兼容 | 全新 endpoint,不影响现有流程 |
| Citation | 完全兼容 | 知识库工具返回格式变化,但 LLM 仍能理解;老前端只是看到 [1][2] 文本无样式 |
| SSE 心跳 | 完全兼容 | 注释帧不触发客户端 `onmessage`,旧前端代码原样工作 |

---

## 后续建议(本轮未做,P0/P1 的延伸)

- **可观测性**:Micrometer + Actuator + Prometheus,把 Token 计量从消息维度上升到 metric 维度
- **AgentDTO.allowedSkills**:Skill 当前对所有 Agent 全局开放,后续可加按 Agent 作用域(同 `allowedTools`)
- **Guardrails 输入侧接入 controller**:目前 `scrubOutput` 已经接 ShopAi,`checkInput` 服务就绪但还没接 ChatMessageController POST(避免触发现有联调)
- **MCP server 自带 demo**:配一个 `filesystem` MCP server 跑通端到端

---

**作者**: ShopAssistant 团队 · **日期**: 2026-06-08
