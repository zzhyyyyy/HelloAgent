package com.xmu.ShopAssistant.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MemoryEvalServiceImpl implements MemoryEvalService {

    private final MemoryRetrievalService retrievalService;

    @Override
    public MemoryEvalReport evaluate(List<MemoryEvalCase> cases, int retrievalLimit) {
        List<MemoryEvalCase> safeCases = cases == null ? List.of() : cases;
        List<MemoryEvalResult> results = new ArrayList<>();
        int expectedTotal = 0;
        int matchedTotal = 0;
        int forbiddenTotal = 0;
        int leakedTotal = 0;

        for (MemoryEvalCase evalCase : safeCases) {
            validate(evalCase);
            List<MemorySearchResult> retrieved = retrievalService.search(
                    evalCase.getTenantId(), evalCase.getOwnerId(), evalCase.getAgentId(),
                    evalCase.getQuery(), retrievalLimit);
            String corpus = retrieved.stream()
                    .map(item -> nullToEmpty(item.title()) + "\n" + nullToEmpty(item.content()))
                    .reduce("", (left, right) -> left + "\n" + right)
                    .toLowerCase(Locale.ROOT);

            List<String> missing = new ArrayList<>();
            for (String fact : safe(evalCase.getExpectedFacts())) {
                expectedTotal++;
                if (contains(corpus, fact)) matchedTotal++;
                else missing.add(fact);
            }

            List<String> leaked = new ArrayList<>();
            for (String fact : safe(evalCase.getForbiddenFacts())) {
                forbiddenTotal++;
                if (contains(corpus, fact)) {
                    leakedTotal++;
                    leaked.add(fact);
                }
            }

            boolean passed = missing.isEmpty() && leaked.isEmpty();
            results.add(MemoryEvalResult.builder()
                    .caseId(evalCase.getId())
                    .passed(passed)
                    .expectedCount(safe(evalCase.getExpectedFacts()).size())
                    .matchedCount(safe(evalCase.getExpectedFacts()).size() - missing.size())
                    .missingFacts(missing)
                    .leakedFacts(leaked)
                    .retrievedSourceIds(retrieved.stream().map(MemorySearchResult::sourceId).toList())
                    .build());
        }

        int passed = (int) results.stream().filter(MemoryEvalResult::isPassed).count();
        return MemoryEvalReport.builder()
                .totalCases(results.size())
                .passedCases(passed)
                .casePassRate(ratio(passed, results.size()))
                .factRecall(ratio(matchedTotal, expectedTotal))
                .leakageRate(ratio(leakedTotal, forbiddenTotal))
                .results(results)
                .build();
    }

    private void validate(MemoryEvalCase evalCase) {
        if (evalCase == null
                || !StringUtils.hasText(evalCase.getTenantId())
                || !StringUtils.hasText(evalCase.getOwnerId())
                || !StringUtils.hasText(evalCase.getQuery())) {
            throw new IllegalArgumentException("Memory Eval case 缺少 tenantId、ownerId 或 query");
        }
    }

    private boolean contains(String corpus, String fact) {
        return StringUtils.hasText(fact) && corpus.contains(fact.toLowerCase(Locale.ROOT));
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator / (double) denominator;
    }
}
