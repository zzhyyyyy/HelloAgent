package com.xmu.ShopAssistant.agent.tools;

import com.xmu.ShopAssistant.agent.tools.security.GuardOutcome;
import com.xmu.ShopAssistant.agent.tools.security.SensitivityLevel;
import com.xmu.ShopAssistant.agent.tools.security.ToolGuard;
import com.xmu.ShopAssistant.agent.tools.security.ToolSecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@Slf4j
public class DataBaseTools implements Tool {

    private static final Pattern SQL_COMMENTS = Pattern.compile("(/\\*[\\s\\S]*?\\*/)|(--[^\\n]*)");
    private static final Pattern LIMIT_TOKEN = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);
    private static final int CELL_MAX_CHARS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ToolGuard guard;
    private final ToolSecurityProperties props;

    public DataBaseTools(JdbcTemplate jdbcTemplate, ToolGuard guard, ToolSecurityProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
        this.props = props;
    }

    @Override
    public String getName() {
        return "dataBaseTool";
    }

    @Override
    public String getDescription() {
        return "在沙箱化策略下对 PostgreSQL 执行只读 SELECT 查询。强制单语句、关键词黑名单、自动 LIMIT、查询超时、连接级 readonly。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @Override
    public SensitivityLevel getSensitivity() {
        return SensitivityLevel.DANGEROUS;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "databaseQuery",
            description = "对 PostgreSQL 执行只读 SELECT 查询。参数:sql(单条 SELECT/WITH 语句)。" +
                    "限制:仅允许单语句、禁止任何写操作关键字、自动追加 LIMIT、查询有超时上限、" +
                    "连接级强制只读。错误信息不包含原始 SQL。"
    )
    public String query(String sql) {
        if (sql == null || sql.isBlank()) {
            return "错误:SQL 不能为空。";
        }

        // —— 1. 前置闸门:速率
        GuardOutcome outcome = guard.check(getName(), "databaseQuery");
        if (!outcome.allowed()) {
            return outcome.message();
        }

        // —— 2. 剥注释、规范化
        String stripped = SQL_COMMENTS.matcher(sql).replaceAll(" ").trim();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).trim();
        }

        // —— 3. 拒绝多语句(分号已剥尾,中间还有就是多语句)
        if (stripped.contains(";")) {
            guard.audit(getName(), "databaseQuery",
                    Map.of("reason", "multi_statement"), "DENIED", "");
            return "错误:不允许多语句执行。";
        }

        // —— 4. 强制 SELECT / WITH 开头
        String upper = stripped.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            guard.audit(getName(), "databaseQuery",
                    Map.of("reason", "not_select_or_with"), "DENIED", "");
            return "错误:仅允许 SELECT 或 WITH 开头的只读查询。";
        }

        // —— 5. 关键词黑名单(按词边界 \b 匹配)
        for (String kw : props.getDatabase().getForbiddenKeywords()) {
            if (containsKeyword(stripped, kw)) {
                guard.audit(getName(), "databaseQuery",
                        Map.of("reason", "forbidden_keyword", "keyword", kw),
                        "DENIED", "");
                return "错误:SQL 包含禁用关键字 " + kw + "。";
            }
        }

        // —— 6. 自动 LIMIT
        int maxRows = Math.max(1, props.getDatabase().getMaxRows());
        if (!LIMIT_TOKEN.matcher(stripped).find()) {
            stripped = stripped + " LIMIT " + maxRows;
        }

        final String finalSql = stripped;
        final int timeoutSec = Math.max(1, props.getDatabase().getQueryTimeoutSeconds());

        // —— 7. 执行:连接级 readonly + queryTimeout + maxRows
        try {
            String result = jdbcTemplate.execute((ConnectionCallback<String>) conn -> {
                boolean prevReadOnly = conn.isReadOnly();
                try {
                    conn.setReadOnly(true);
                    try (PreparedStatement stmt = conn.prepareStatement(finalSql)) {
                        stmt.setQueryTimeout(timeoutSec);
                        stmt.setMaxRows(maxRows + 10); // 留 buffer,业务侧再截一刀
                        try (ResultSet rs = stmt.executeQuery()) {
                            return formatResultSet(rs, maxRows);
                        }
                    }
                } finally {
                    try {
                        conn.setReadOnly(prevReadOnly);
                    } catch (SQLException ignored) {
                        // 连接可能已经被池子收回,忽略
                    }
                }
            });
            guard.audit(getName(), "databaseQuery",
                    Map.of("len", finalSql.length()), "OK", "");
            return result;
        } catch (Exception e) {
            log.warn("DataBaseTools 查询失败 type={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
            guard.audit(getName(), "databaseQuery",
                    Map.of("err", e.getClass().getSimpleName()),
                    "ERROR", e.getMessage());
            // 不回显原 SQL,只给类型化错误
            return "错误:查询失败 (" + e.getClass().getSimpleName() + ")。请检查表名/字段名是否正确,或简化查询后重试。";
        }
    }

    private boolean containsKeyword(String sql, String keyword) {
        return Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(sql)
                .find();
    }

    private String formatResultSet(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        if (cols == 0) {
            return "查询结果为空(无列)。";
        }

        List<String> colNames = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        for (int i = 1; i <= cols; i++) {
            String name = md.getColumnName(i);
            colNames.add(name);
            widths.add(name.length());
        }

        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= cols; i++) {
                Object v = rs.getObject(i);
                String s = v == null ? "NULL" : v.toString();
                if (s.length() > CELL_MAX_CHARS) {
                    s = s.substring(0, CELL_MAX_CHARS) + "…";
                }
                row.add(s);
                widths.set(i - 1, Math.max(widths.get(i - 1), s.length()));
            }
            rows.add(row);
        }

        StringBuilder out = new StringBuilder();
        out.append("| ");
        for (int i = 0; i < cols; i++) {
            out.append(String.format("%-" + widths.get(i) + "s", colNames.get(i))).append(" | ");
        }
        out.append('\n').append('|');
        for (int i = 0; i < cols; i++) {
            out.append("-".repeat(widths.get(i) + 2)).append('|');
        }
        out.append('\n');

        if (rows.isEmpty()) {
            out.append("(无数据)\n");
        } else {
            for (List<String> r : rows) {
                out.append("| ");
                for (int i = 0; i < cols; i++) {
                    out.append(String.format("%-" + widths.get(i) + "s", r.get(i))).append(" | ");
                }
                out.append('\n');
            }
        }

        if (truncated) {
            out.append("[已截断,只返回前 ").append(maxRows).append(" 行]\n");
        }
        out.append("[共 ").append(rows.size()).append(" 行]\n");
        return out.toString();
    }
}
