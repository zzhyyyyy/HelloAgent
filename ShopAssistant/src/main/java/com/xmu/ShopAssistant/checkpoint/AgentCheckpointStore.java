package com.xmu.ShopAssistant.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 使用原子文件替换持久化检查点，避免服务重启丢失待审批任务。 */
@Component
public class AgentCheckpointStore {
    private static final String SUFFIX = ".checkpoint.json";

    private final ObjectMapper objectMapper;
    private final Path storageDir;

    public AgentCheckpointStore(ObjectMapper objectMapper, AgentCheckpointProperties properties) {
        this.objectMapper = objectMapper;
        this.storageDir = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
    }

    public synchronized AgentCheckpoint save(AgentCheckpoint checkpoint) {
        try {
            Files.createDirectories(storageDir);
            Path target = pathOf(checkpoint.getId());
            Path temporary = Files.createTempFile(storageDir, checkpoint.getId() + "-", ".tmp");
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(checkpoint);
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return checkpoint;
        } catch (IOException e) {
            throw new IllegalStateException("保存 Agent 检查点失败: " + checkpoint.getId(), e);
        }
    }

    public synchronized Optional<AgentCheckpoint> findById(String checkpointId) {
        Path path = pathOf(checkpointId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), AgentCheckpoint.class));
        } catch (IOException e) {
            throw new IllegalStateException("读取 Agent 检查点失败: " + checkpointId, e);
        }
    }

    public synchronized List<AgentCheckpoint> findBySessionId(String sessionId) {
        return findAll().stream()
                .filter(checkpoint -> sessionId.equals(checkpoint.getSessionId()))
                .toList();
    }

    public synchronized List<AgentCheckpoint> findAll() {
        if (!Files.isDirectory(storageDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(storageDir)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .map(this::readUnchecked)
                    .sorted(Comparator.comparing(AgentCheckpoint::getCreatedAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取 Agent 检查点目录失败", e);
        }
    }

    public Path getStorageDir() {
        return storageDir;
    }

    private AgentCheckpoint readUnchecked(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), AgentCheckpoint.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Agent 检查点文件失败: " + path, e);
        }
    }

    private Path pathOf(String checkpointId) {
        if (checkpointId == null || !checkpointId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("非法检查点 ID");
        }
        Path resolved = storageDir.resolve(checkpointId + SUFFIX).normalize();
        if (!resolved.startsWith(storageDir)) {
            throw new IllegalArgumentException("检查点路径越界");
        }
        return resolved;
    }
}
