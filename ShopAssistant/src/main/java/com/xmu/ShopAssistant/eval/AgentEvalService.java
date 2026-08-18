package com.xmu.ShopAssistant.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.config.ChatClientRegistry;
import com.xmu.ShopAssistant.message.SseMessage;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.response.GetChatMessagesResponse;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import com.xmu.ShopAssistant.prompt.PromptRegistry;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import com.xmu.ShopAssistant.service.SseService;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM-as-Judge 评估服务:
 * - 输入 N 条 case (question, expected, actual)
 * - 让 judge 模型按 correctness/completeness/relevance 三维度 0-5 打分
 * - 输出每条 case 的得分 + 聚合统计
 *
 * 用法:CI / 离线评测集 / 回归测试。
 */
@Slf4j
@Service
public class AgentEvalService {

    private static final String DEFAULT_JUDGE_MODEL = "deepseek-chat";

    private final ChatClientRegistry chatClientRegistry;
    private final PromptRegistry promptRegistry;
    private final ObjectMapper objectMapper;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final SseService sseService;

    public AgentEvalService(ChatClientRegistry chatClientRegistry,
                            PromptRegistry promptRegistry,
                            ObjectMapper objectMapper,
                            ChatMessageFacadeService chatMessageFacadeService,
                            SseService sseService) {
        this.chatClientRegistry = chatClientRegistry;
        this.promptRegistry = promptRegistry;
        this.objectMapper = objectMapper;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.sseService = sseService;
    }

    // =================== Live 自动评估(每条 assistant 消息持久化后异步触发) ===================

    /**
     * 异步对一条 assistant 消息做 LLM-as-Judge 评分,落库 + 通过 SSE 推送给前端。
     * 失败不抛(只记日志),不影响 Agent 主流程。
     */
    @Async
    public void evaluateAndPushAsync(String messageId, String sessionId) {
        try {
            // 1. 取消息本体
            ChatMessageDTO target = chatMessageFacadeService.getChatMessageById(messageId);
            if (target == null || target.getRole() != ChatMessageDTO.RoleType.ASSISTANT) {
                return;
            }
            String actual = target.getContent();
            if (actual == null || actual.isBlank()) {
                return;
            }

            // 2. 在同会话里找该消息之前最近一条 USER 消息当作 question
            String question = findPreviousUserMessage(sessionId, messageId);
            if (question == null) {
                log.debug("Live eval 跳过:未找到 question, messageId={}", messageId);
                return;
            }

            // 3. 调 judge
            ChatClient judge = chatClientRegistry.get(DEFAULT_JUDGE_MODEL);
            if (judge == null) {
                log.warn("Live eval 跳过:未找到 judge 模型 {}", DEFAULT_JUDGE_MODEL);
                return;
            }
            BeanOutputConverter<EvalScore> converter = new BeanOutputConverter<>(EvalScore.class);
            String prompt = promptRegistry.render("eval.live.v1", Map.of(
                    "question", question,
                    "actual", actual,
                    "format", converter.getFormat()
            ));
            String raw = judge.prompt().user(prompt).call().content();
            EvalScore score = converter.convert(raw);
            if (score == null) {
                log.warn("Live eval 解析失败 messageId={}", messageId);
                return;
            }

            // 4. 转 DTO,落库
            ChatMessageDTO.EvalScore dtoScore = ChatMessageDTO.EvalScore.builder()
                    .correctness(score.getCorrectness())
                    .completeness(score.getCompleteness())
                    .relevance(score.getRelevance())
                    .reasoning(score.getReasoning())
                    .judgeModel(DEFAULT_JUDGE_MODEL)
                    .build();
            chatMessageFacadeService.updateMessageEvalScore(messageId, dtoScore);

            // 5. 推 SSE
            pushEvalSse(sessionId, messageId, dtoScore);

            log.info("[LiveEval] messageId={} c={}/comp={}/rel={}",
                    messageId, dtoScore.getCorrectness(), dtoScore.getCompleteness(), dtoScore.getRelevance());
        } catch (Exception e) {
            log.warn("Live eval 失败 messageId={} err={}", messageId, e.getMessage());
        }
    }

    private String findPreviousUserMessage(String sessionId, String targetMessageId) {
        GetChatMessagesResponse resp = chatMessageFacadeService.getChatMessagesBySessionId(sessionId);
        ChatMessageVO[] all = resp.getChatMessages();
        if (all == null) return null;
        int targetIdx = -1;
        for (int i = 0; i < all.length; i++) {
            if (targetMessageId.equals(all[i].getId())) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx <= 0) return null;
        for (int j = targetIdx - 1; j >= 0; j--) {
            if (all[j].getRole() == ChatMessageDTO.RoleType.USER) {
                return all[j].getContent();
            }
        }
        return null;
    }

    private void pushEvalSse(String sessionId, String messageId, ChatMessageDTO.EvalScore score) {
        try {
            ChatMessageDTO.MetaData md = ChatMessageDTO.MetaData.builder()
                    .evalScore(score)
                    .build();
            ChatMessageVO carrier = ChatMessageVO.builder()
                    .id(messageId)
                    .sessionId(sessionId)
                    .metadata(md)
                    .build();
            SseMessage sse = SseMessage.builder()
                    .type(SseMessage.Type.AI_EVAL_SCORE)
                    .payload(SseMessage.Payload.builder().message(carrier).build())
                    .metadata(SseMessage.Metadata.builder().chatMessageId(messageId).build())
                    .build();
            sseService.send(sessionId, sse);
        } catch (Exception e) {
            // SSE 连接可能已断,忽略
            log.debug("Live eval SSE push 失败 messageId={} err={}", messageId, e.getMessage());
        }
    }

    public EvalReport evaluate(EvalRequest req) {
        String judgeModel = req.getJudgeModel() == null ? DEFAULT_JUDGE_MODEL : req.getJudgeModel();
        ChatClient judge = chatClientRegistry.get(judgeModel);
        if (judge == null) {
            throw new IllegalArgumentException("未找到 judge 模型: " + judgeModel);
        }
        BeanOutputConverter<EvalScore> converter = new BeanOutputConverter<>(EvalScore.class);
        String format = converter.getFormat();

        List<EvalCaseResult> results = new ArrayList<>();
        int sumCorr = 0, sumComp = 0, sumRel = 0, n = 0;

        for (EvalCase c : req.getCases()) {
            try {
                String prompt = promptRegistry.render("eval.judge.v1", Map.of(
                        "question", safe(c.getQuestion()),
                        "expected", safe(c.getExpected()),
                        "actual", safe(c.getActual()),
                        "format", format
                ));
                String raw = judge.prompt()
                        .user(prompt)
                        .call()
                        .content();
                EvalScore score = converter.convert(raw);
                if (score != null) {
                    sumCorr += score.getCorrectness();
                    sumComp += score.getCompleteness();
                    sumRel += score.getRelevance();
                    n++;
                }
                results.add(EvalCaseResult.builder()
                        .question(c.getQuestion())
                        .expected(c.getExpected())
                        .actual(c.getActual())
                        .score(score)
                        .build());
            } catch (Exception e) {
                log.warn("eval 单条失败 question={} err={}", c.getQuestion(), e.getMessage());
                results.add(EvalCaseResult.builder()
                        .question(c.getQuestion())
                        .expected(c.getExpected())
                        .actual(c.getActual())
                        .error(e.getMessage())
                        .build());
            }
        }

        Aggregate avg = Aggregate.builder()
                .count(n)
                .avgCorrectness(n == 0 ? 0 : sumCorr * 1.0 / n)
                .avgCompleteness(n == 0 ? 0 : sumComp * 1.0 / n)
                .avgRelevance(n == 0 ? 0 : sumRel * 1.0 / n)
                .build();

        return EvalReport.builder()
                .judgeModel(judgeModel)
                .results(results)
                .aggregate(avg)
                .build();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ---------- DTO ----------

    @Data
    public static class EvalRequest {
        private String judgeModel;
        private List<EvalCase> cases = new ArrayList<>();
    }

    @Data
    public static class EvalCase {
        private String question;
        private String expected;
        private String actual;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EvalCaseResult {
        private String question;
        private String expected;
        private String actual;
        private EvalScore score;
        private String error;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EvalScore {
        private int correctness;
        private int completeness;
        private int relevance;
        private String reasoning;
    }

    @Data
    @Builder
    public static class Aggregate {
        private int count;
        private double avgCorrectness;
        private double avgCompleteness;
        private double avgRelevance;
    }

    @Data
    @Builder
    public static class EvalReport {
        private String judgeModel;
        private List<EvalCaseResult> results;
        private Aggregate aggregate;
    }
}
