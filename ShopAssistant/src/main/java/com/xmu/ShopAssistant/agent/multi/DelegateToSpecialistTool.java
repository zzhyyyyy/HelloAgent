package com.xmu.ShopAssistant.agent.multi;

import com.xmu.ShopAssistant.agent.tools.Tool;
import com.xmu.ShopAssistant.agent.tools.ToolType;
import com.xmu.ShopAssistant.converter.ChatMessageConverter;
import com.xmu.ShopAssistant.message.SseMessage;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.response.CreateChatMessageResponse;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import com.xmu.ShopAssistant.service.SpecialistRunner;
import com.xmu.ShopAssistant.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * Supervisor 用于委派任务给 Specialist Agent 的工具。
 * 非 Spring Bean，由 ShopAiFactory 在创建 Supervisor 时实例化并注入 sessionId。
 */
@Slf4j
public class DelegateToSpecialistTool implements Tool {

    private final String sessionId;
    private final SpecialistRunner specialistRunner;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final SseService sseService;

    public DelegateToSpecialistTool(String sessionId,
                                    SpecialistRunner specialistRunner,
                                    ChatMessageFacadeService chatMessageFacadeService,
                                    ChatMessageConverter chatMessageConverter,
                                    SseService sseService) {
        this.sessionId = sessionId;
        this.specialistRunner = specialistRunner;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.sseService = sseService;
    }

    @Override
    public String getName() {
        return "DelegateToSpecialist";
    }

    @Override
    public String getDescription() {
        return "将子任务委派给一个专业 Specialist Agent 去独立完成。适用于需要特定领域知识或专门工具的场景。参数：specialistAgentId（专业Agent的ID），task（要委派的具体任务描述）。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "DelegateToSpecialist",
            description = "将子任务委派给专业 Specialist Agent 独立处理。参数：specialistAgentId（要调用的专业Agent ID），task（清晰的任务描述，包含上下文）。"
    )
    public String delegate(String specialistAgentId, String task) {
        if (!StringUtils.hasText(specialistAgentId)) {
            return "错误：specialistAgentId 不能为空";
        }
        if (!StringUtils.hasText(task)) {
            return "错误：task 不能为空";
        }

        String agentRole = "specialist:" + specialistAgentId;
        log.info("Supervisor 委派任务: specialistAgentId={}, task={}", specialistAgentId, task);

        // 保存 Supervisor 发出的 tool call 消息（已由 ShopAi.saveMessage 处理）
        // 由 SpecialistRunner 运行 Specialist，内部会保存消息并推送 SSE
        String result = specialistRunner.run(specialistAgentId, task, sessionId, agentRole);

        log.info("Supervisor 收到 Specialist 结果: specialistAgentId={}, resultLength={}",
                specialistAgentId, result != null ? result.length() : 0);

        // 将 Specialist 的最终结果以 tool response 形式返回给 Supervisor
        return result != null ? result : "Specialist 未返回结果";
    }
}
