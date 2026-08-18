package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.prompt.PromptRegistry;
import com.xmu.ShopAssistant.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.stream.Stream;

@Slf4j
@Component
public class EvolutionArtifactManager {

    private final ObjectMapper objectMapper;
    private final PromptRegistry promptRegistry;
    private final SkillRegistry skillRegistry;
    private final Path activeDir;

    public EvolutionArtifactManager(ObjectMapper objectMapper,
                                    PromptRegistry promptRegistry,
                                    SkillRegistry skillRegistry,
                                    EvolutionProperties properties) {
        this.objectMapper = objectMapper;
        this.promptRegistry = promptRegistry;
        this.skillRegistry = skillRegistry;
        this.activeDir = Path.of(properties.getStorageDir(), "active").toAbsolutePath().normalize();
    }

    @PostConstruct
    void restoreActiveArtifacts() {
        if (!Files.isDirectory(activeDir)) return;
        try (Stream<Path> stream = Files.list(activeDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(this::restoreQuietly);
        } catch (IOException e) {
            log.warn("恢复活动演化版本失败 dir={} err={}", activeDir, e.getMessage());
        }
    }

    public String currentContent(EvolutionArtifactType type, String targetName) {
        return type == EvolutionArtifactType.PROMPT
                ? promptRegistry.get(targetName)
                : skillRegistry.raw(targetName);
    }

    public synchronized void activate(EvolutionCandidate candidate) {
        apply(candidate.getType(), candidate.getTargetName(), candidate.getContent());
        persist(EvolutionActiveArtifact.builder()
                .type(candidate.getType())
                .targetName(candidate.getTargetName())
                .candidateId(candidate.getId())
                .content(candidate.getContent())
                .activatedAt(Instant.now())
                .build());
    }

    public synchronized void restore(EvolutionCandidate candidate) {
        if (candidate.getPreviousContent() == null || candidate.getPreviousContent().isBlank()) {
            throw new IllegalStateException("候选版本没有可回滚内容");
        }
        apply(candidate.getType(), candidate.getTargetName(), candidate.getPreviousContent());
        persist(EvolutionActiveArtifact.builder()
                .type(candidate.getType())
                .targetName(candidate.getTargetName())
                .content(candidate.getPreviousContent())
                .activatedAt(Instant.now())
                .build());
    }

    private void apply(EvolutionArtifactType type, String targetName, String content) {
        if (type == EvolutionArtifactType.PROMPT) promptRegistry.activate(targetName, content);
        else skillRegistry.activate(targetName, content);
    }

    private void persist(EvolutionActiveArtifact active) {
        try {
            Files.createDirectories(activeDir);
            Path target = activeDir.resolve(filename(active.getType(), active.getTargetName()));
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), active);
            moveAtomically(temp, target);
        } catch (IOException e) {
            throw new IllegalStateException("持久化活动演化版本失败", e);
        }
    }

    private void restoreQuietly(Path path) {
        try {
            EvolutionActiveArtifact active = objectMapper.readValue(path.toFile(), EvolutionActiveArtifact.class);
            apply(active.getType(), active.getTargetName(), active.getContent());
            log.info("已恢复活动演化版本 type={} target={} candidateId={}",
                    active.getType(), active.getTargetName(), active.getCandidateId());
        } catch (Exception e) {
            log.warn("忽略无法恢复的活动演化版本 file={} err={}", path, e.getMessage());
        }
    }

    private static String filename(EvolutionArtifactType type, String targetName) {
        return type.name().toLowerCase() + "-" + targetName + ".json";
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
