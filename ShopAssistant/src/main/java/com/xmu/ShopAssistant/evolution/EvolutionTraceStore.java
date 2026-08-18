package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Component
public class EvolutionTraceStore {

    private final ObjectMapper objectMapper;
    private final Path tracesDir;

    public EvolutionTraceStore(ObjectMapper objectMapper, EvolutionProperties properties) {
        this.objectMapper = objectMapper;
        this.tracesDir = Path.of(properties.getStorageDir(), "traces").toAbsolutePath().normalize();
    }

    public synchronized EvolutionTrace save(EvolutionTrace trace) {
        try {
            Files.createDirectories(tracesDir);
            if (trace.getId() == null || trace.getId().isBlank()) {
                trace.setId(UUID.randomUUID().toString());
            }
            if (trace.getCreatedAt() == null) {
                trace.setCreatedAt(Instant.now());
            }
            Path target = tracesDir.resolve(trace.getId() + ".json");
            Path temp = tracesDir.resolve(trace.getId() + ".json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), trace);
            moveAtomically(temp, target);
            return trace;
        } catch (IOException e) {
            throw new IllegalStateException("保存演化轨迹失败", e);
        }
    }

    public List<EvolutionTrace> list(boolean lowScoreOnly, int limit) {
        if (!Files.isDirectory(tracesDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(tracesDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readQuietly)
                    .filter(trace -> trace != null && (!lowScoreOnly || trace.isLowScore()))
                    .sorted(Comparator.comparing(EvolutionTrace::getCreatedAt).reversed())
                    .limit(Math.max(0, limit))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取演化轨迹失败", e);
        }
    }

    public EvolutionTrace find(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            return null;
        }
        Path path = tracesDir.resolve(id + ".json");
        return Files.isRegularFile(path) ? readQuietly(path) : null;
    }

    private EvolutionTrace readQuietly(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), EvolutionTrace.class);
        } catch (Exception e) {
            log.warn("忽略损坏的演化轨迹 file={} err={}", path, e.getMessage());
            return null;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
