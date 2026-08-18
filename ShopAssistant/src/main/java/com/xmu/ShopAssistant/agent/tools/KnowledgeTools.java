package com.xmu.ShopAssistant.agent.tools;

import com.xmu.ShopAssistant.mapper.KnowledgeBaseMapper;
import com.xmu.ShopAssistant.model.entity.KnowledgeBase;
import com.xmu.ShopAssistant.service.RagService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeTools(RagService ragService, KnowledgeBaseMapper knowledgeBaseMapper) {
        this.ragService = ragService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从知识库执行混合检索(BM25+向量)。参数:kbsId(知识库ID;若传ALL或留空则自动检索所有知识库)和query(查询文本)。" +
                    "返回的每个片段会带 [N] 数字编号(全局唯一),你在最终回答中必须用 [N] 形式引用对应片段。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        if (!StringUtils.hasText(query)) {
            return "查询为空";
        }
        boolean searchAll = !StringUtils.hasText(kbsId) || "ALL".equalsIgnoreCase(kbsId.trim());
        if (searchAll) {
            return searchAllKnowledgeBases(query);
        }

        List<String> strings = ragService.hybridSearch(kbsId, query, 3);
        if (strings == null || strings.isEmpty()) {
            return "[KB " + kbsId + "] 无匹配结果(混合检索)";
        }
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (String chunk : strings) {
            sb.append("[").append(idx++).append("] (kb=").append(kbsId).append(")\n")
                    .append(chunk).append("\n\n");
        }
        sb.append("[引用规则] 你在最终回答中务必用 [N] 引用以上对应片段编号。");
        return sb.toString().trim();
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeToolAll",
            description = "对所有知识库执行语义检索。参数：query。返回带知识库ID的候选片段，用于避免漏检。"
    )
    public String knowledgeQueryAll(String query) {
        if (!StringUtils.hasText(query)) {
            return "查询为空";
        }
        return searchAllKnowledgeBases(query);
    }

    private String searchAllKnowledgeBases(String query) {
        List<KnowledgeBase> allKbs = knowledgeBaseMapper.selectAll();
        if (allKbs == null || allKbs.isEmpty()) {
            return "当前没有可检索的知识库";
        }

        Set<String> kbIds = new LinkedHashSet<>();
        for (KnowledgeBase kb : allKbs) {
            if (kb != null && StringUtils.hasText(kb.getId())) {
                kbIds.add(kb.getId());
            }
        }

        StringBuilder sb = new StringBuilder();
        int idx = 1;
        boolean any = false;
        for (String id : kbIds) {
            List<String> hits = ragService.hybridSearch(id, query, 3);
            if (hits == null || hits.isEmpty()) {
                continue;
            }
            any = true;
            for (String chunk : hits) {
                sb.append("[").append(idx++).append("] (kb=").append(id).append(")\n")
                        .append(chunk).append("\n\n");
            }
        }
        if (!any) {
            return "所有知识库均未检索到相关结果";
        }
        sb.append("[引用规则] 你在最终回答中务必用 [N] 引用以上对应片段编号。");
        return sb.toString().trim();
    }
}
