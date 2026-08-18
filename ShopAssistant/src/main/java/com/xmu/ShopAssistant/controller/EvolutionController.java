package com.xmu.ShopAssistant.controller;

import com.xmu.ShopAssistant.evolution.EvolutionTrace;
import com.xmu.ShopAssistant.evolution.EvolutionTraceService;
import com.xmu.ShopAssistant.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/evolution")
@RequiredArgsConstructor
public class EvolutionController {

    private final EvolutionTraceService traceService;

    @GetMapping("/traces")
    public ApiResponse<List<EvolutionTrace>> traces(
            @RequestParam(defaultValue = "false") boolean lowScoreOnly,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(traceService.list(lowScoreOnly, limit));
    }
}
