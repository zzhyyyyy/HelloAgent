package com.xmu.ShopAssistant.memory;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 记忆治理接口。当前项目没有统一登录体系，因此暂时显式接收作用域；
 * 接入认证后必须从服务端身份上下文解析 tenantId/ownerId，不能继续信任请求参数。
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryManagementController {

    private final MemoryDreamService dreamService;
    private final MemoryRetrievalService retrievalService;
    private final MemoryEvalService evalService;

    @PostMapping("/dreams")
    public Map<String, String> submitDream(@RequestBody DreamRequest request) {
        String jobId = dreamService.submit(
                request.getTenantId(), request.getOwnerId(), request.getAgentId(), request.getInstructions());
        return Map.of("jobId", jobId);
    }

    @PostMapping("/proposals/{proposalId}/approve")
    public ResponseEntity<Void> approve(@PathVariable String proposalId) {
        dreamService.approveProposal(proposalId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public ResponseEntity<Void> reject(@PathVariable String proposalId) {
        dreamService.rejectProposal(proposalId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/snapshots/{snapshotId}/activate")
    public ResponseEntity<Void> activate(@PathVariable String snapshotId) {
        dreamService.activate(snapshotId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/snapshots/{snapshotId}/rollback")
    public MemorySnapshot rollback(@PathVariable String snapshotId) {
        return dreamService.rollback(snapshotId);
    }

    @GetMapping("/search")
    public List<MemorySearchResult> search(
            @RequestParam String tenantId,
            @RequestParam String ownerId,
            @RequestParam(required = false) String agentId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit
    ) {
        return retrievalService.search(tenantId, ownerId, agentId, query, limit);
    }

    @GetMapping("/pages/{pageId}")
    public MemoryRepresentation readPage(
            @PathVariable String pageId,
            @RequestParam String tenantId,
            @RequestParam String ownerId
    ) {
        return retrievalService.readPage(tenantId, ownerId, pageId);
    }

    @PostMapping("/eval")
    public MemoryEvalReport evaluate(@RequestBody EvalRequest request) {
        return evalService.evaluate(request.getCases(), request.getRetrievalLimit());
    }

    @Data
    public static class DreamRequest {
        private String tenantId;
        private String ownerId;
        private String agentId;
        private String instructions;
    }

    @Data
    public static class EvalRequest {
        private List<MemoryEvalCase> cases;
        private int retrievalLimit = 5;
    }
}
