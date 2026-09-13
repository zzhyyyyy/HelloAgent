# Agent 检查点与危险操作人工审批

## 1. 目标

当 Agent 计划调用数据库、邮件、文件系统等危险工具时，系统不会立即执行，而是：

1. 持久化模型本轮生成的原始 Tool Call；
2. 暂停当前 Agent Loop；
3. 通过 SSE 向前端发送审批请求；
4. 用户批准后执行保存的原始调用，拒绝则明确写入“未执行”的工具响应；
5. 恢复原来的单 Agent、Supervisor 或 Specialist Harness。

这里保存的是业务存档点，不是 Java 线程快照。等待用户期间没有线程阻塞，所以即使用户数小时后才处理，服务线程也不会一直占用。

## 2. 检查点存放位置与命名

默认目录：

```text
./data/checkpoints/
```

文件名：

```text
{checkpointId}.checkpoint.json
```

`checkpointId` 是 UUID，例如：

```text
0d227e84-c12e-48ff-95a7-c87f48881a24.checkpoint.json
```

JSON 中保存会话 ID、Agent ID、Harness 类型、状态、原始工具名、Tool Call ID 和完整 arguments。前端只收到经过截断和敏感字段遮盖的参数预览。

## 3. 危险工具如何识别

项目内置工具通过 `Tool.getSensitivity()` 声明敏感级别。返回 `DANGEROUS` 的工具，其暴露出来的所有 ToolCallback 都自动加入人工审批集合。

MCP 工具不实现项目内的 `Tool` 接口，因此需要在 `application.yaml` 中按 ToolCallback 的实际名称显式配置：

```yaml
agent:
  checkpoint:
    external-approval-required-tools:
      - deleteRemoteFile
      - executeProductionSql
```

## 4. 状态流转

批准路径：

```text
WAITING_APPROVAL -> APPROVED -> EXECUTING -> TOOL_COMPLETED -> RESUMING -> COMPLETED
```

拒绝路径：

```text
WAITING_APPROVAL -> REJECTED -> REJECTION_EXECUTING -> REJECTION_COMPLETED -> RESUMING -> COMPLETED
```

异常路径：

```text
EXECUTING / REJECTION_EXECUTING / RESUMING --服务重启--> UNKNOWN
EXECUTING --调用异常--> FAILED
```

审批状态推进使用同步临界区，重复点击批准不会重复领取同一个检查点。工具执行成功后，结果以原 Tool Call ID 对应的 `ToolResponseMessage` 持久化，恢复时仍满足大模型的工具调用协议。

## 5. 重启恢复与副作用边界

- `WAITING_APPROVAL`：继续等待，前端重新进入会话后可通过接口重新加载。
- `APPROVED`：说明还没有消费者领取，应用启动后可以安全继续。
- `TOOL_COMPLETED` / `REJECTION_COMPLETED`：工具协议响应已经落库，但 Agent 尚未恢复，应用启动后只恢复 Agent，不会重复执行工具。
- `EXECUTING`：进程退出前工具可能已经产生副作用，系统无法可靠判断，因此转为 `UNKNOWN`，不会自动重试。
- `FAILED` / `UNKNOWN`：前端展示给用户人工核实，不再提供直接重复批准按钮。

该实现避免了应用内的正常重复消费，但无法单独保证外部系统“严格只执行一次”。若邮件、支付、远程数据库等系统支持幂等键，还应把 `checkpointId` 传给外部系统作为幂等键，才能覆盖“外部操作成功、应用尚未来得及写 COMPLETED 就崩溃”的情况。

## 6. 接口与 SSE

接口：

- `GET /api/checkpoints/session/{sessionId}/pending`：查询待审批及待人工核实的检查点；
- `POST /api/checkpoints/{checkpointId}/approve`：批准；
- `POST /api/checkpoints/{checkpointId}/reject`：拒绝。

SSE 事件：

- `AI_APPROVAL_REQUIRED`：显示危险操作审批卡片；
- `AI_APPROVAL_RESOLVED`：审批完成、拒绝完成或执行失败后更新卡片。

## 7. 三类 Harness 的恢复方式

- 单 Agent：工具响应落库后重新创建单 Agent，从会话协议消息继续运行。
- Supervisor：以 `supervisor` 角色过滤历史，避免混入 Specialist 的工具协议，再继续运行。
- Specialist：检查点额外保存原委派任务；审批后恢复 Specialist 的独立历史，得到结果后再交回 Supervisor 继续组织最终回答。

Supervisor 与 Specialist 共用消息表，但恢复时按 `metadata.agentRole` 隔离协议消息，防止一个 Agent 的 Tool Call 与另一个 Agent 的 Tool Response 错配。
