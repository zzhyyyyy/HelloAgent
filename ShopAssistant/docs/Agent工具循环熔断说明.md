# Agent 工具循环熔断说明

## 规则

- 同一 `toolName + canonicalJson(arguments)` 最多连续真实执行 3 次，第 4 次返回拦截结果，不调用真实工具。
- 同一个工具连续 3 次得到相同的规范化结果，第 3 次完成后立即熔断。
- 熔断后的下一轮不再向模型暴露任何工具，只允许模型基于已有结果生成最终回答。
- 单 Agent、Supervisor 和 Specialist 的总循环上限统一为 20 轮。

配置位于 `application.yaml`：

```yaml
agent:
  tools:
    loop-guard:
      enabled: true
      max-consecutive-same-signature: 3
      max-consecutive-same-result: 3
```

## 调用签名

调用签名由工具名和规范化参数组成：

```text
signature = toolName + canonicalJson(arguments)
```

JSON 对象字段会递归按名称排序，数组顺序保留。因此下面两组参数签名相同：

```json
{"query":"AI","page":1}
{"page":1,"query":"AI"}
```

数组顺序通常表达排名或执行顺序，所以 `[1,2]` 和 `[2,1]` 不会被视为相同。

## 结果如何判断相同

结果先规范化，再计算 SHA-256：

1. 如果结果是 JSON，对象字段递归排序后重新序列化；
2. 如果不是 JSON，统一 CRLF/LF、去掉首尾空白、把连续空白压缩为一个空格；
3. 计算规范化文本的 SHA-256；
4. 比较 `toolName + resultHash`，只保存摘要，不重复保存大段工具结果。

因此 JSON 字段顺序和普通文本排版差异不会影响判断。但时间戳、随机 ID、结果排序或正文内容发生变化时，会被视为不同结果。这是有意采用的严格相等策略，避免语义相似度造成误熔断；后续若某个工具有明确的易变字段，可以为该工具增加专用结果规范化器。

## 协议安全

熔断器包装的是 Spring AI `ToolCallback`。超过次数时不会直接抛异常，而是返回一条普通 ToolResponse，所以 Tool Call ID 仍由 `ToolCallingManager` 正确配对，不会形成孤立的工具协议消息。

本地工具与 MCP 工具在合并成 ToolCallback 集合后统一包装，因此不依赖具体工具实现是否主动调用 `ToolGuard`。
