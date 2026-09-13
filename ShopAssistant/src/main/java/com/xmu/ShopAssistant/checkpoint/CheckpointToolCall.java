package com.xmu.ShopAssistant.checkpoint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Tool Call 的不可变业务快照；审批后必须执行这里保存的原始参数。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointToolCall {
    private String id;
    private String type;
    private String name;
    private String arguments;
}
