package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.guardrails.GuardrailsProperties;
import com.xmu.ShopAssistant.guardrails.GuardrailsService;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.response.GetChatMessagesResponse;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvolutionTraceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCaptureSanitizedLowScoreTraceWithToolAndSkillUsage() {
        EvolutionProperties properties = new EvolutionProperties();
        properties.setStorageDir(tempDir.toString());
        properties.setLowScoreThreshold(3.0);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvolutionTraceStore store = new EvolutionTraceStore(objectMapper, properties);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        GuardrailsService guardrailsService = new GuardrailsService(new GuardrailsProperties());

        ChatMessageVO user = ChatMessageVO.builder()
                .id("user-1").role(ChatMessageDTO.RoleType.USER).content("查询 13800138000 的退款").build();
        ChatMessageVO toolMessage = ChatMessageVO.builder()
                .id("assistant-tool")
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("call-1", "function", "useSkill",
                                        "{\"skillName\":\"order-refund\"}"),
                                new AssistantMessage.ToolCall("call-2", "function", "queryOrder", "{}")
                        ))
                        .build())
                .build();
        ChatMessageVO target = ChatMessageVO.builder()
                .id("assistant-final").role(ChatMessageDTO.RoleType.ASSISTANT).content("已处理").build();
        when(messageService.getChatMessagesBySessionId("session-1"))
                .thenReturn(GetChatMessagesResponse.builder()
                        .chatMessages(new ChatMessageVO[]{user, toolMessage, target})
                        .build());

        EvolutionTraceService service = new EvolutionTraceService(
                properties, store, messageService, guardrailsService, objectMapper);
        EvolutionTrace trace = service.capture(
                "assistant-final",
                "session-1",
                user.getContent(),
                "请联系 buyer@example.com",
                ChatMessageDTO.Usage.builder().totalTokens(42L).model("deepseek-chat").build(),
                ChatMessageDTO.EvalScore.builder()
                        .correctness(2).completeness(3).relevance(3).judgeModel("deepseek-chat").build());

        assertThat(trace.isLowScore()).isTrue();
        assertThat(trace.getAverageScore()).isEqualTo(8.0 / 3.0);
        assertThat(trace.getQuestion()).doesNotContain("13800138000").contains("***");
        assertThat(trace.getAnswer()).doesNotContain("buyer@example.com").contains("***");
        assertThat(trace.getToolsUsed()).containsExactly("useSkill", "queryOrder");
        assertThat(trace.getSkillsUsed()).containsExactly("order-refund");
        assertThat(store.find(trace.getId())).isNotNull();
    }
}
