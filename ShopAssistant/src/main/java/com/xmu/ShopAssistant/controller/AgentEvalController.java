package com.xmu.ShopAssistant.controller;

import com.xmu.ShopAssistant.eval.AgentEvalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/eval/agent")
public class AgentEvalController {

    private final AgentEvalService agentEvalService;

    public AgentEvalController(AgentEvalService agentEvalService) {
        this.agentEvalService = agentEvalService;
    }

    /**
     * 离线评测入口:输入测试集 → 让 judge 模型按 correctness/completeness/relevance 打分。
     *
     * 示例请求:
     * POST /eval/agent
     * {
     *   "judgeModel": "deepseek-chat",
     *   "cases": [
     *     { "question": "...", "expected": "...", "actual": "..." }
     *   ]
     * }
     */
    @PostMapping
    public AgentEvalService.EvalReport evaluate(@RequestBody AgentEvalService.EvalRequest req) {
        return agentEvalService.evaluate(req);
    }
}
