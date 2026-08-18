package com.xmu.ShopAssistant.agent.tools.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工具沙箱与安全策略配置。
 * yaml 前缀:agent.tools.security
 */
@Data
@ConfigurationProperties(prefix = "agent.tools.security")
public class ToolSecurityProperties {

    private boolean auditEnabled = true;
    private Database database = new Database();
    private Email email = new Email();
    private Filesystem filesystem = new Filesystem();

    @Data
    public static class Database {
        private int maxRows = 200;
        private int queryTimeoutSeconds = 5;
        private Set<String> forbiddenKeywords = Set.of(
                "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE", "ALTER",
                "CREATE", "GRANT", "REVOKE", "MERGE", "EXEC", "CALL",
                "VACUUM", "COPY"
        );
        private int permitsPerMinute = 30;
    }

    @Data
    public static class Email {
        /** 空表示拒绝所有(默认安全策略),允许 glob 通配: a@b.com 或 *@b.com */
        private List<String> recipientAllowlist = new ArrayList<>();
        private int maxSubjectLength = 200;
        private int maxBodyLength = 5000;
        private int permitsPerMinute = 3;
        private int dailyCap = 20;
    }

    @Data
    public static class Filesystem {
        /** 沙箱根目录,所有读写都被限制在此目录内 */
        private String sandboxDir = "./data/sandbox";
        private int maxReadBytes = 262144; // 256KB
        private boolean writeEnabled = false;
        private boolean deleteEnabled = false;
        private List<String> filenameDenylist = List.of(
                "(?i).+\\.(env|key|pem|p12|jks|crt|secret)$",
                "^\\..*"
        );
        private int permitsPerMinute = 60;
    }
}
