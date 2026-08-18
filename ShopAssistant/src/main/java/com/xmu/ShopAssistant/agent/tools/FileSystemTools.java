package com.xmu.ShopAssistant.agent.tools;

import com.xmu.ShopAssistant.agent.tools.security.GuardOutcome;
import com.xmu.ShopAssistant.agent.tools.security.SensitivityLevel;
import com.xmu.ShopAssistant.agent.tools.security.ToolGuard;
import com.xmu.ShopAssistant.agent.tools.security.ToolSecurityProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class FileSystemTools implements Tool {

    private final ToolGuard guard;
    private final ToolSecurityProperties props;

    private Path sandboxRoot;
    private List<Pattern> denyPatterns;

    public FileSystemTools(ToolGuard guard, ToolSecurityProperties props) {
        this.guard = guard;
        this.props = props;
    }

    @PostConstruct
    void init() {
        this.sandboxRoot = Paths.get(props.getFilesystem().getSandboxDir())
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(sandboxRoot);
        } catch (IOException e) {
            log.warn("无法创建沙箱目录 {}: {}", sandboxRoot, e.getMessage());
        }
        this.denyPatterns = new ArrayList<>();
        for (String p : props.getFilesystem().getFilenameDenylist()) {
            try {
                denyPatterns.add(Pattern.compile(p));
            } catch (Exception e) {
                log.warn("denylist 正则编译失败 pattern={} err={}", p, e.getMessage());
            }
        }
        log.info("FileSystemTools 沙箱已就绪 root={} writeEnabled={} deleteEnabled={}",
                sandboxRoot,
                props.getFilesystem().isWriteEnabled(),
                props.getFilesystem().isDeleteEnabled());
    }

    @Override
    public String getName() {
        return "fileSystemTool";
    }

    @Override
    public String getDescription() {
        return "受沙箱保护的文件系统访问。默认只读,所有路径限制在沙箱根目录内,文件名黑名单与大小上限受配置约束。";
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
            name = "readFile",
            description = "读取沙箱目录内文件的内容。参数:filePath(相对沙箱根)。" +
                    "受文件大小上限约束,且命中文件名黑名单(.env/.key/.pem 等)会被拒绝。"
    )
    public String readFile(String filePath) {
        GuardOutcome g = guard.check(getName(), "readFile");
        if (!g.allowed()) return g.message();
        try {
            Path path = resolve(filePath);
            if (!Files.exists(path)) return "错误:文件不存在 - " + filePath;
            if (!Files.isRegularFile(path)) return "错误:路径不是文件 - " + filePath;
            long size = Files.size(path);
            int maxBytes = props.getFilesystem().getMaxReadBytes();
            if (size > maxBytes) {
                return "错误:文件大小 " + size + " 字节,超过单次读取上限 " + maxBytes + " 字节。";
            }
            String content = Files.readString(path);
            guard.audit(getName(), "readFile",
                    Map.of("path", filePath, "size", size), "OK", "");
            return "文件内容:\n" + content;
        } catch (SecurityException e) {
            guard.audit(getName(), "readFile",
                    Map.of("path", filePath, "reason", "security"),
                    "DENIED", e.getMessage());
            return "错误:访问被拒绝。";
        } catch (Exception e) {
            log.warn("readFile failed path={} err={}", filePath, e.getMessage());
            guard.audit(getName(), "readFile",
                    Map.of("path", filePath, "err", e.getClass().getSimpleName()),
                    "ERROR", e.getMessage());
            return "错误:读取失败。";
        }
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "writeFile",
            description = "向沙箱目录写入文件(默认禁用,需在 agent.tools.security.filesystem.write-enabled=true 开启)。"
    )
    public String writeFile(String filePath, String content) {
        if (!props.getFilesystem().isWriteEnabled()) {
            guard.audit(getName(), "writeFile",
                    Map.of("path", filePath, "reason", "write_disabled"),
                    "DENIED", "");
            return "错误:写入功能已禁用。请管理员在配置中开启 write-enabled。";
        }
        GuardOutcome g = guard.check(getName(), "writeFile");
        if (!g.allowed()) return g.message();
        try {
            Path path = resolve(filePath);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            guard.audit(getName(), "writeFile",
                    Map.of("path", filePath, "size", content.length()),
                    "OK", "");
            return "成功写入文件: " + filePath;
        } catch (SecurityException e) {
            return "错误:访问被拒绝。";
        } catch (Exception e) {
            log.warn("writeFile failed path={} err={}", filePath, e.getMessage());
            return "错误:写入失败。";
        }
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "appendToFile",
            description = "向沙箱目录文件追加内容(默认禁用,需 write-enabled=true 开启)。"
    )
    public String appendToFile(String filePath, String content) {
        if (!props.getFilesystem().isWriteEnabled()) {
            return "错误:追加(写入)功能已禁用。";
        }
        GuardOutcome g = guard.check(getName(), "appendToFile");
        if (!g.allowed()) return g.message();
        try {
            Path path = resolve(filePath);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            guard.audit(getName(), "appendToFile",
                    Map.of("path", filePath, "size", content.length()),
                    "OK", "");
            return "成功追加到文件: " + filePath;
        } catch (SecurityException e) {
            return "错误:访问被拒绝。";
        } catch (Exception e) {
            log.warn("appendToFile failed path={} err={}", filePath, e.getMessage());
            return "错误:追加失败。";
        }
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "listFiles",
            description = "列出沙箱目录下的文件与子目录。参数:directoryPath(相对沙箱根;空字符串则列出根)。" +
                    "黑名单文件被自动过滤。"
    )
    public String listFiles(String directoryPath) {
        GuardOutcome g = guard.check(getName(), "listFiles");
        if (!g.allowed()) return g.message();
        try {
            Path path = (directoryPath == null || directoryPath.isBlank())
                    ? sandboxRoot
                    : resolve(directoryPath);
            if (!Files.exists(path)) return "错误:目录不存在。";
            if (!Files.isDirectory(path)) return "错误:路径不是目录。";

            try (Stream<Path> stream = Files.list(path)) {
                List<String> items = stream
                        .filter(p -> !isDenied(p.getFileName().toString()))
                        .map(this::formatEntry)
                        .sorted()
                        .collect(Collectors.toList());
                if (items.isEmpty()) return "目录为空。";
                String shown = directoryPath == null || directoryPath.isBlank() ? "/" : directoryPath;
                return "目录内容 (" + shown + "):\n" + String.join("\n", items);
            }
        } catch (SecurityException e) {
            return "错误:访问被拒绝。";
        } catch (Exception e) {
            log.warn("listFiles failed dir={} err={}", directoryPath, e.getMessage());
            return "错误:列目录失败。";
        }
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "deleteFile",
            description = "删除沙箱目录下的文件或目录(默认禁用,需 delete-enabled=true 开启)。"
    )
    public String deleteFile(String filePath) {
        if (!props.getFilesystem().isDeleteEnabled()) {
            guard.audit(getName(), "deleteFile",
                    Map.of("path", filePath, "reason", "delete_disabled"),
                    "DENIED", "");
            return "错误:删除功能已禁用。";
        }
        GuardOutcome g = guard.check(getName(), "deleteFile");
        if (!g.allowed()) return g.message();
        try {
            Path target = resolve(filePath);
            if (!Files.exists(target)) return "错误:目标不存在。";
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("删除失败 path={} err={}", p, e.getMessage());
                        }
                    });
                }
            } else {
                Files.delete(target);
            }
            guard.audit(getName(), "deleteFile",
                    Map.of("path", filePath), "OK", "");
            return "成功删除: " + filePath;
        } catch (SecurityException e) {
            return "错误:访问被拒绝。";
        } catch (Exception e) {
            log.warn("deleteFile failed path={} err={}", filePath, e.getMessage());
            return "错误:删除失败。";
        }
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "createDirectory",
            description = "在沙箱目录内创建目录(默认禁用,需 write-enabled=true 开启)。"
    )
    public String createDirectory(String directoryPath) {
        if (!props.getFilesystem().isWriteEnabled()) {
            return "错误:创建目录(写入)功能已禁用。";
        }
        GuardOutcome g = guard.check(getName(), "createDirectory");
        if (!g.allowed()) return g.message();
        try {
            Path path = resolve(directoryPath);
            if (Files.exists(path)) {
                return Files.isDirectory(path) ? "目录已存在: " + directoryPath
                        : "错误:路径已存在但不是目录。";
            }
            Files.createDirectories(path);
            guard.audit(getName(), "createDirectory",
                    Map.of("path", directoryPath), "OK", "");
            return "成功创建目录: " + directoryPath;
        } catch (SecurityException e) {
            return "错误:访问被拒绝。";
        } catch (Exception e) {
            log.warn("createDirectory failed path={} err={}", directoryPath, e.getMessage());
            return "错误:创建目录失败。";
        }
    }

    // ---------- 内部 ----------

    private Path resolve(String rel) {
        if (rel == null || rel.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        // 拒绝绝对路径(Unix / 与 Windows 盘符)
        if (rel.startsWith("/") || rel.startsWith("\\") || rel.matches("^[a-zA-Z]:.*")) {
            throw new SecurityException("不允许绝对路径");
        }
        Path resolved = sandboxRoot.resolve(rel).toAbsolutePath().normalize();
        if (!resolved.startsWith(sandboxRoot)) {
            throw new SecurityException("路径越界,已阻止");
        }
        String name = resolved.getFileName() == null ? "" : resolved.getFileName().toString();
        if (isDenied(name)) {
            throw new SecurityException("文件名命中黑名单");
        }
        return resolved;
    }

    private boolean isDenied(String name) {
        if (name == null || name.isEmpty()) return false;
        for (Pattern p : denyPatterns) {
            if (p.matcher(name).matches()) return true;
        }
        return false;
    }

    private String formatEntry(Path p) {
        String name = p.getFileName().toString();
        if (Files.isDirectory(p)) return "[DIR] " + name;
        try {
            return "[FILE] " + name + " (" + formatSize(Files.size(p)) + ")";
        } catch (IOException e) {
            return "[FILE] " + name;
        }
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.2f MB", size / 1048576.0);
        return String.format("%.2f GB", size / 1073741824.0);
    }
}
