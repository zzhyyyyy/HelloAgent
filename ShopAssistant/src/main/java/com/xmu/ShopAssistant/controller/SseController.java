package com.xmu.ShopAssistant.controller;

import com.xmu.ShopAssistant.service.SseService;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sse")
@AllArgsConstructor
public class SseController {

    private final SseService sseService;

    // 处理 sse 连接
    @RequestMapping(value = "/connect/{chatSessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(
            @PathVariable String chatSessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return sseService.connect(chatSessionId, lastEventId);
    }
}
