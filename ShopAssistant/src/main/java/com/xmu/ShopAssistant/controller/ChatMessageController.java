package com.xmu.ShopAssistant.controller;

import com.xmu.ShopAssistant.model.common.ApiResponse;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.request.CreateChatMessageRequest;
import com.xmu.ShopAssistant.model.request.UpdateChatMessageRequest;
import com.xmu.ShopAssistant.model.response.CreateChatMessageResponse;
import com.xmu.ShopAssistant.model.response.GetChatMessagesResponse;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import com.xmu.ShopAssistant.service.ChatSessionFacadeService;
import com.xmu.ShopAssistant.service.PdfService;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChatMessageController {

    private final ChatMessageFacadeService chatMessageFacadeService;
    private final PdfService pdfService;
    private final ChatSessionFacadeService chatSessionFacadeService;

    // 根据 sessionId 查询聊天消息
    @GetMapping("/chat-messages/session/{sessionId}")
    public ApiResponse<GetChatMessagesResponse> getChatMessagesBySessionId(@PathVariable String sessionId) {
        return ApiResponse.success(chatMessageFacadeService.getChatMessagesBySessionId(sessionId));
    }

    // 创建聊天消息
    @PostMapping("/chat-messages")
    public ApiResponse<CreateChatMessageResponse> createChatMessage(@RequestBody CreateChatMessageRequest request) {
        return ApiResponse.success(chatMessageFacadeService.createChatMessage(request));
    }

    // 删除聊天消息
    @DeleteMapping("/chat-messages/{chatMessageId}")
    public ApiResponse<Void> deleteChatMessage(@PathVariable String chatMessageId) {
        chatMessageFacadeService.deleteChatMessage(chatMessageId);
        return ApiResponse.success();
    }

    // 更新聊天消息
    @PatchMapping("/chat-messages/{chatMessageId}")
    public ApiResponse<Void> updateChatMessage(@PathVariable String chatMessageId, @RequestBody UpdateChatMessageRequest request) {
        chatMessageFacadeService.updateChatMessage(chatMessageId, request);
        return ApiResponse.success();
    }

    // 下载聊天消息为 PDF
    @GetMapping("/chat-messages/{chatMessageId}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable String chatMessageId) {
        ChatMessageDTO message = chatMessageFacadeService.getChatMessageById(chatMessageId);

        if (message.getRole() != ChatMessageDTO.RoleType.ASSISTANT) {
            return ResponseEntity.badRequest()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"code\":400,\"message\":\"仅支持下载 AI 回复消息\"}".getBytes());
        }

        String sessionTitle = null;
        try {
            sessionTitle = chatSessionFacadeService.getChatSession(message.getSessionId()).getChatSession().getTitle();
        } catch (Exception e) {
            // 获取会话标题失败不影响 PDF 生成
        }

        String createdAt = message.getCreatedAt() != null
                ? message.getCreatedAt().toString()
                : null;

        byte[] pdfContent = pdfService.generatePdf(
                "ShopAssistant - AI 回答",
                message.getContent(),
                sessionTitle,
                createdAt
        );

        String filename = "AI-response-" + chatMessageId + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdfContent);
    }
}
