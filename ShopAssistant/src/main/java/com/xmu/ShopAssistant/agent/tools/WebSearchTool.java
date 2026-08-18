package com.xmu.ShopAssistant.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0 Safari/537.36";
    private static final int CACHE_MAX = 128;
    private static final long CACHE_TTL_MS = 60_000L;

    private final WebClient duckClient;
    private final WebClient bingClient;
    private final int maxResults;
    private final Duration timeout;

    private final ConcurrentMap<String, CachedHit> cache = new ConcurrentHashMap<>();

    public WebSearchTool(
            WebClient.Builder builder,
            @Value("${websearch.max-results:5}") int maxResults,
            @Value("${websearch.timeout-seconds:8}") int timeoutSeconds,
            @Value("${websearch.duckduckgo-url:https://html.duckduckgo.com}") String ddgUrl,
            @Value("${websearch.bing-url:https://cn.bing.com}") String bingUrl
    ) {
        this.duckClient = builder.clone().baseUrl(ddgUrl).build();
        this.bingClient = builder.clone().baseUrl(bingUrl).build();
        this.maxResults = maxResults > 0 ? maxResults : 5;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public String getName() {
        return "WebSearchTool";
    }

    @Override
    public String getDescription() {
        return "联网检索实时信息。一次调用即可获得该主题的足够结果,获得结果后应直接作答,不要对同一主题反复搜索。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "WebSearchTool",
            description = "联网搜索实时信息(新闻、价格、参数、未训练知识等)。参数:query(检索关键词,中英文均可)。" +
                    "返回最多 5 条结果(序号 + 标题/摘要/URL)。重要:本工具单次调用即可得到该主题足够信息," +
                    "拿到结果后必须基于结果直接综合作答,严禁对同一或近似主题在同一轮对话中反复调用本工具。" +
                    "若一次结果不足以作答,优先改用其它工具(知识库检索等)或基于已有知识回答。"
    )
    public String webSearch(String query) {
        if (!StringUtils.hasText(query)) {
            return "搜索关键词为空,请提供查询词。";
        }
        String q = query.trim();
        String cacheKey = normalizeKey(q);

        CachedHit cached = getCache(cacheKey);
        if (cached != null) {
            return "[提示] 你刚才已搜索过相近主题「" + q + "」,请直接基于以下结果作答,不要再次调用 WebSearchTool。\n\n"
                    + cached.body;
        }

        try {
            List<SearchHit> hits = searchWithDuckDuckGo(q);
            if (hits.isEmpty()) {
                hits = searchWithBing(q);
            }
            if (hits.isEmpty()) {
                return "未找到与「" + q + "」相关的搜索结果。请尝试更换关键词,或基于已有知识回答(不要使用相同关键词重试)。";
            }
            String body = formatResults(dedupByUrl(hits));
            putCache(cacheKey, body);
            return body + "\n\n[提示] 已为你检索互联网,请基于以上结果直接综合作答,无需再次调用 WebSearchTool 搜索同一主题。";
        } catch (Exception e) {
            log.error("WebSearchTool 失败: query={}", q, e);
            return "搜索引擎暂时不可达,请基于已有知识尽量回答,不要重复调用本工具。错误: " + e.getMessage();
        }
    }

    // ---------- 检索实现 ----------

    private List<SearchHit> searchWithDuckDuckGo(String query) {
        String enc = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = fetchHtml(duckClient, "/html/?q=" + enc);
        return parseDdgHtml(html, maxResults);
    }

    private List<SearchHit> searchWithBing(String query) {
        String enc = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = fetchHtml(bingClient, "/search?q=" + enc);
        return parseBingHtml(html, maxResults);
    }

    private String fetchHtml(WebClient client, String uri) {
        return client.get()
                .uri(uri)
                .header(HttpHeaders.USER_AGENT, UA)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .onErrorResume(e -> {
                    log.warn("HTTP 拉取失败 uri={} err={}", uri, e.getMessage());
                    return reactor.core.publisher.Mono.just("");
                })
                .block();
    }

    // ---------- 解析 ----------

    private static final Pattern DDG_BLOCK = Pattern.compile(
            "<div\\s+class=\"result(?:\\s[^\"]*)?\"[^>]*>(.*?)</div>\\s*</div>",
            Pattern.DOTALL
    );
    private static final Pattern DDG_LINK = Pattern.compile(
            "<a\\s+[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL
    );
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "<a\\s+[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.DOTALL
    );

    private List<SearchHit> parseDdgHtml(String html, int max) {
        List<SearchHit> hits = new ArrayList<>();
        if (!StringUtils.hasText(html)) return hits;

        Matcher m = DDG_BLOCK.matcher(html);
        while (m.find() && hits.size() < max) {
            String chunk = m.group(1);
            Matcher am = DDG_LINK.matcher(chunk);
            if (!am.find()) continue;

            String url = unwrapDdgUrl(am.group(1));
            String title = stripTags(am.group(2));
            if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) continue;

            String snippet = "";
            Matcher sm = DDG_SNIPPET.matcher(chunk);
            if (sm.find()) snippet = stripTags(sm.group(1));

            hits.add(new SearchHit(title, snippet, url));
        }
        return hits;
    }

    /**
     * DDG html 端点的链接通常是 {@code //duckduckgo.com/l/?uddg=<encoded-url>&...},
     * 把真实 URL 从 uddg 参数里取出。
     */
    private String unwrapDdgUrl(String raw) {
        if (raw == null) return "";
        if (raw.startsWith("//")) raw = "https:" + raw;
        try {
            int idx = raw.indexOf("uddg=");
            if (idx >= 0) {
                String tail = raw.substring(idx + 5);
                int amp = tail.indexOf('&');
                if (amp > 0) tail = tail.substring(0, amp);
                return URLDecoder.decode(tail, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return raw;
    }

    private static final Pattern BING_BLOCK = Pattern.compile(
            "<li\\s+class=\"b_algo\"[^>]*>(.*?)</li>",
            Pattern.DOTALL
    );
    private static final Pattern BING_H2 = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern BING_A_IN_H2 = Pattern.compile(
            "<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL
    );
    private static final Pattern BING_SNIPPET = Pattern.compile(
            "<div\\s+class=\"b_caption\"[^>]*>.*?<p[^>]*>(.*?)</p>",
            Pattern.DOTALL
    );

    private List<SearchHit> parseBingHtml(String html, int max) {
        List<SearchHit> hits = new ArrayList<>();
        if (!StringUtils.hasText(html)) return hits;

        Matcher liMatcher = BING_BLOCK.matcher(html);
        while (liMatcher.find() && hits.size() < max) {
            String li = liMatcher.group(1);
            if (!li.contains("<h2")) continue;

            Matcher h2m = BING_H2.matcher(li);
            if (!h2m.find()) continue;
            String h2Inner = h2m.group(1);

            Matcher am = BING_A_IN_H2.matcher(h2Inner);
            if (!am.find()) continue;

            String url = am.group(1).trim();
            if (url.startsWith("//")) continue;

            String title = stripTags(am.group(2));
            if (!StringUtils.hasText(title)) continue;

            String snippet = "";
            Matcher sm = BING_SNIPPET.matcher(li);
            if (sm.find()) snippet = stripTags(sm.group(1));

            hits.add(new SearchHit(title, snippet, url));
        }
        return hits;
    }

    // ---------- 工具方法 ----------

    private List<SearchHit> dedupByUrl(List<SearchHit> hits) {
        Map<String, SearchHit> unique = new LinkedHashMap<>();
        for (SearchHit h : hits) {
            unique.putIfAbsent(h.url, h);
        }
        return new ArrayList<>(unique.values());
    }

    private String formatResults(List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SearchHit h : hits) {
            if (i > maxResults) break;
            sb.append(i++).append(". ").append(h.title).append('\n');
            if (StringUtils.hasText(h.snippet)) {
                sb.append("   ").append(h.snippet).append('\n');
            }
            sb.append("   ").append(h.url).append('\n');
        }
        return sb.toString().trim();
    }

    private String stripTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
    }

    private String normalizeKey(String q) {
        return q.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    // ---------- 简单 TTL 缓存 ----------

    private CachedHit getCache(String key) {
        CachedHit c = cache.get(key);
        if (c == null) return null;
        if (System.currentTimeMillis() - c.createdAt > CACHE_TTL_MS) {
            cache.remove(key);
            return null;
        }
        return c;
    }

    private void putCache(String key, String body) {
        cache.put(key, new CachedHit(body, System.currentTimeMillis()));
        if (cache.size() > CACHE_MAX) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> now - e.getValue().createdAt > CACHE_TTL_MS);
            if (cache.size() > CACHE_MAX) {
                cache.entrySet().stream()
                        .sorted(Comparator.comparingLong(e -> e.getValue().createdAt))
                        .limit(cache.size() - CACHE_MAX)
                        .forEach(e -> cache.remove(e.getKey()));
            }
        }
    }

    private record SearchHit(String title, String snippet, String url) {
    }

    private record CachedHit(String body, long createdAt) {
    }
}
