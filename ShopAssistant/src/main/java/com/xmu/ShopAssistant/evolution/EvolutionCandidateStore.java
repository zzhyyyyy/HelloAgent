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
public class EvolutionCandidateStore {

    private final ObjectMapper objectMapper;
    private final Path candidatesDir;

    public EvolutionCandidateStore(ObjectMapper objectMapper, EvolutionProperties properties) {
        this.objectMapper = objectMapper;
        this.candidatesDir = Path.of(properties.getStorageDir(), "candidates").toAbsolutePath().normalize();
    }

    public synchronized EvolutionCandidate save(EvolutionCandidate candidate) {
        try {
            Files.createDirectories(candidatesDir);
            Instant now = Instant.now();
            if (candidate.getId() == null || candidate.getId().isBlank()) {
                candidate.setId(UUID.randomUUID().toString());
            }
            if (candidate.getCreatedAt() == null) candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            Path target = candidatesDir.resolve(candidate.getId() + ".json");
            Path temp = candidatesDir.resolve(candidate.getId() + ".json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), candidate);
            moveAtomically(temp, target);
            return candidate;
        } catch (IOException e) {
            throw new IllegalStateException("保存演化候选版本失败", e);
        }
    }

    public EvolutionCandidate find(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return null;
        Path path = candidatesDir.resolve(id + ".json");
        return Files.isRegularFile(path) ? readQuietly(path) : null;
    }

    public List<EvolutionCandidate> list(int limit) {
        if (!Files.isDirectory(candidatesDir)) return List.of();
        try (Stream<Path> stream = Files.list(candidatesDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readQuietly)
                    .filter(candidate -> candidate != null)
                    .sorted(Comparator.comparing(EvolutionCandidate::getCreatedAt).reversed())
                    .limit(Math.max(0, limit))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取演化候选版本失败", e);
        }
    }

    private EvolutionCandidate readQuietly(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), EvolutionCandidate.class);
        } catch (Exception e) {
            log.warn("忽略损坏的演化候选版本 file={} err={}", path, e.getMessage());
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
