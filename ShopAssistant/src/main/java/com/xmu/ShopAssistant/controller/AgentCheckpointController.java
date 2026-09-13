package com.xmu.ShopAssistant.controller;

import com.xmu.ShopAssistant.checkpoint.AgentCheckpointService;
import com.xmu.ShopAssistant.checkpoint.AgentCheckpointView;
import com.xmu.ShopAssistant.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/checkpoints")
@RequiredArgsConstructor
public class AgentCheckpointController {
    private final AgentCheckpointService checkpointService;

    @GetMapping("/session/{sessionId}/pending")
    public ApiResponse<List<AgentCheckpointView>> pending(@PathVariable String sessionId) {
        return ApiResponse.success(checkpointService.pendingBySession(sessionId));
    }

    @PostMapping("/{checkpointId}/approve")
    public ApiResponse<AgentCheckpointView> approve(@PathVariable String checkpointId) {
        return ApiResponse.success(checkpointService.approve(checkpointId));
    }

    @PostMapping("/{checkpointId}/reject")
    public ApiResponse<AgentCheckpointView> reject(@PathVariable String checkpointId) {
        return ApiResponse.success(checkpointService.reject(checkpointId));
    }
}
