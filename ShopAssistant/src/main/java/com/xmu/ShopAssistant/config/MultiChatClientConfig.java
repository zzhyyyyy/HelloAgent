package com.xmu.ShopAssistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MultiChatClientConfig {
    // deepseek
    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.create(deepSeekChatModel);
    }

    // zhipuai
    @Bean("glm-4.6")
    public ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        return ChatClient.create(zhiPuAiChatModel);
    }

    /**
     * Spring AI 1.1 的 ChatClientAutoConfiguration / McpToolCallbackAutoConfiguration 等
     * 想自动注入一个 ChatModel 来构造默认 ChatClient.Builder,但容器里同时有 DeepSeek 与 ZhiPuAi
     * 两个 ChatModel candidate,会触发 NoUniqueBeanDefinitionException。
     * 这里把 DeepSeek 显式标记 @Primary,作为默认主力模型(其它代码仍通过 ChatClientRegistry 按名取)。
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(DeepSeekChatModel deepSeekChatModel) {
        return deepSeekChatModel;
    }
}
