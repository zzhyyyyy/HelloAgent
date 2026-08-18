package com.xmu.ShopAssistant.evolution;

import com.xmu.ShopAssistant.config.ChatClientRegistry;
import com.xmu.ShopAssistant.eval.AgentEvalService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class LlmEvolutionReplayEvaluator implements EvolutionReplayEvaluator {

    private final ChatClientRegistry chatClientRegistry;
    private final AgentEvalService agentEvalService;
    private final EvolutionProperties properties;

    public LlmEvolutionReplayEvaluator(ChatClientRegistry chatClientRegistry,
                                       AgentEvalService agentEvalService,
                                       EvolutionProperties properties) {
        this.chatClientRegistry = chatClientRegistry;
        this.agentEvalService = agentEvalService;
        this.properties = properties;
    }

    @Override
    public EvolutionEvaluation evaluate(EvolutionCandidate candidate, List<EvolutionTrace> traces) {
        ChatClient model = chatClientRegistry.get(properties.getCandidateModel());
        if (model == null) throw new IllegalStateException("未找到回放模型: " + properties.getCandidateModel());
        List<AgentEvalService.EvalCase> cases = new ArrayList<>();
        double baseline = 0;
        for (EvolutionTrace trace : traces) {
            String system = candidate.getType() == EvolutionArtifactType.SKILL
                    ? "请严格遵循以下 Skill 完成用户任务。\n\n" + candidate.getContent()
                    : candidate.getContent();
            String answer = model.prompt().system(system).user(trace.getQuestion()).call().content();
            AgentEvalService.EvalCase evalCase = new AgentEvalService.EvalCase();
            evalCase.setQuestion(trace.getQuestion());
            evalCase.setExpected("");
            evalCase.setActual(answer);
            cases.add(evalCase);
            baseline += trace.getAverageScore();
        }
        AgentEvalService.EvalRequest request = new AgentEvalService.EvalRequest();
        request.setJudgeModel(properties.getJudgeModel());
        request.setCases(cases);
        AgentEvalService.EvalReport report = agentEvalService.evaluate(request);
        if (report.getAggregate().getCount() != traces.size()) {
            throw new IllegalStateException("部分回放样本评估失败，拒绝进入审批");
        }
        double baselineAvg = baseline / traces.size();
        double candidateAvg = (report.getAggregate().getAvgCorrectness()
                + report.getAggregate().getAvgCompleteness()
                + report.getAggregate().getAvgRelevance()) / 3.0;
        double improvement = candidateAvg - baselineAvg;
        boolean passed = candidateAvg >= properties.getMinCandidateScore()
                && improvement >= properties.getMinImprovement();
        return EvolutionEvaluation.builder()
                .sampleCount(traces.size())
                .baselineScore(baselineAvg)
                .candidateScore(candidateAvg)
                .improvement(improvement)
                .passed(passed)
                .judgeModel(properties.getJudgeModel())
                .summary(passed ? "候选达到发布门槛" : "候选未达到发布门槛")
                .evaluatedAt(Instant.now())
                .build();
    }
}
