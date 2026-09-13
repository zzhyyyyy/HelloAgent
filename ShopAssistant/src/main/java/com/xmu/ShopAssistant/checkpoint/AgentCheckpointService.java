package com.xmu.ShopAssistant.checkpoint;

import com.xmu.ShopAssistant.message.SseMessage;
import com.xmu.ShopAssistant.service.SseService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentCheckpointService {
    private final AgentCheckpointStore store;
    private final AgentCheckpointProperties properties;
    private final SseService sseService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 若本轮包含需要审批的工具，则保存完整 Tool Call 并暂停；否则返回 empty。
     * 多个并行 Tool Call 作为一个审批单元处理，防止只执行其中一部分造成协议残缺。
     */
    public synchronized Optional<AgentCheckpoint> pauseIfApprovalRequired(
            String sessionId,
            String agentId,
            String agentRole,
            AgentRuntimeType runtimeType,
            List<AssistantMessage.ToolCall> toolCalls,
            Set<String> approvalRequiredToolNames
    ) {
        return pauseIfApprovalRequired(
                sessionId, agentId, agentRole, runtimeType,
                toolCalls, approvalRequiredToolNames, null
        );
    }

    public synchronized Optional<AgentCheckpoint> pauseIfApprovalRequired(
            String sessionId,
            String agentId,
            String agentRole,
            AgentRuntimeType runtimeType,
            List<AssistantMessage.ToolCall> toolCalls,
            Set<String> approvalRequiredToolNames,
            String resumeContext
    ) {
        if (!properties.isEnabled() || toolCalls == null || toolCalls.isEmpty()) {
            return Optional.empty();
        }

        Set<String> requiredNames = toolCalls.stream()
                .map(AssistantMessage.ToolCall::name)
                .filter(approvalRequiredToolNames::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requiredNames.isEmpty()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        AgentCheckpoint checkpoint = AgentCheckpoint.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .agentId(agentId)
                .agentRole(agentRole)
                .runtimeType(runtimeType)
                .resumeContext(resumeContext)
                .status(AgentCheckpointStatus.WAITING_APPROVAL)
                .toolCalls(toolCalls.stream().map(this::copyToolCall).toList())
                .approvalRequiredToolNames(List.copyOf(requiredNames))
                .createdAt(now)
                .updatedAt(now)
                .build();

        store.save(checkpoint);
        sendRequired(checkpoint);
        return Optional.of(checkpoint);
    }

    public synchronized AgentCheckpointView approve(String checkpointId) {
        AgentCheckpoint checkpoint = requireWaiting(checkpointId);
        checkpoint.setStatus(AgentCheckpointStatus.APPROVED);
        checkpoint.setUpdatedAt(Instant.now());
        store.save(checkpoint);
        eventPublisher.publishEvent(new CheckpointDecisionEvent(checkpointId));
        return toView(checkpoint);
    }

    public synchronized AgentCheckpointView reject(String checkpointId) {
        AgentCheckpoint checkpoint = requireWaiting(checkpointId);
        checkpoint.setStatus(AgentCheckpointStatus.REJECTED);
        checkpoint.setUpdatedAt(Instant.now());
        store.save(checkpoint);
        eventPublisher.publishEvent(new CheckpointDecisionEvent(checkpointId));
        return toView(checkpoint);
    }

    /** 只允许一个异步消费者把 APPROVED 原子推进到 EXECUTING。 */
    public synchronized Optional<AgentCheckpoint> claimApproved(String checkpointId) {
        AgentCheckpoint checkpoint = require(checkpointId);
        if (checkpoint.getStatus() != AgentCheckpointStatus.APPROVED) {
            return Optional.empty();
        }
        checkpoint.setStatus(AgentCheckpointStatus.EXECUTING);
        checkpoint.setUpdatedAt(Instant.now());
        return Optional.of(store.save(checkpoint));
    }

    /** 防止同一个拒绝事件重复写入 ToolResponse。 */
    public synchronized Optional<AgentCheckpoint> claimRejected(String checkpointId) {
        AgentCheckpoint checkpoint = require(checkpointId);
        if (checkpoint.getStatus() != AgentCheckpointStatus.REJECTED) {
            return Optional.empty();
        }
        checkpoint.setStatus(AgentCheckpointStatus.REJECTION_EXECUTING);
        checkpoint.setUpdatedAt(Instant.now());
        return Optional.of(store.save(checkpoint));
    }

    public synchronized AgentCheckpoint completeToolExecution(String checkpointId) {
        AgentCheckpoint checkpoint = require(checkpointId);
        if (checkpoint.getStatus() != AgentCheckpointStatus.EXECUTING) {
            throw new IllegalStateException("检查点不是工具执行中状态: " + checkpointId);
        }
        checkpoint.setStatus(AgentCheckpointStatus.TOOL_COMPLETED);
        checkpoint.setUpdatedAt(Instant.now());
        checkpoint.setErrorMessage(null);
        AgentCheckpoint saved = store.save(checkpoint);
        sendResolved(saved);
        return saved;
    }

    /** 工具响应已落库后领取“恢复 Agent Loop”阶段，关闭重启与重复事件造成的并发恢复窗口。 */
    public synchronized Optional<AgentCheckpoint> claimContinuation(String checkpointId) {
        AgentCheckpoint checkpoint = require(checkpointId);
        if (checkpoint.getStatus() != AgentCheckpointStatus.TOOL_COMPLETED
                && checkpoint.getStatus() != AgentCheckpointStatus.REJECTION_COMPLETED) {
            return Optional.empty();
        }
        checkpoint.setStatus(AgentCheckpointStatus.RESUMING);
        checkpoint.setUpdatedAt(Instant.now());
        return Optional.of(store.save(checkpoint));
    }

    public synchronized AgentCheckpoint completeContinuation(String checkpointId) {
        AgentCheckpoint checkpoint = require(checkpointId);
        if (checkpoint.getStatus() != AgentCheckpointStatus.RESUMING) {
            throw new IllegalStateException("检查点不是恢复中状态: " + checkpointId);
        }
        checkpoint.setStatus(AgentCheckpointStatus.COMPLETED);
        checkpoint.setUpdatedAt(Instant.now());
        checkpoint.setErrorMessage(null);
        return store.save(checkpoint);
    }

    public synchronized AgentCheckpoint fail(String checkpointId, String errorMessage) {
        AgentCheckpoint checkpoint = require(checkpointId);
        checkpoint.setStatus(AgentCheckpointStatus.FAILED);
        checkpoint.setUpdatedAt(Instant.now());
        checkpoint.setErrorMessage(limit(errorMessage, 1000));
        AgentCheckpoint saved = store.save(checkpoint);
        sendResolved(saved);
        return saved;
    }

    public synchronized AgentCheckpoint markRejectedResolved(String checkpointId) {
        AgentCheckpoint checkpoint = require(checkpointId);
        if (checkpoint.getStatus() != AgentCheckpointStatus.REJECTION_EXECUTING) {
            throw new IllegalStateException("检查点不是拒绝处理中状态: " + checkpointId);
        }
        checkpoint.setStatus(AgentCheckpointStatus.REJECTION_COMPLETED);
        checkpoint.setUpdatedAt(Instant.now());
        AgentCheckpoint saved = store.save(checkpoint);
        sendResolved(saved);
        return saved;
    }

    public AgentCheckpoint require(String checkpointId) {
        return store.findById(checkpointId)
                .orElseThrow(() -> new IllegalArgumentException("检查点不存在: " + checkpointId));
    }

    public List<AgentCheckpointView> pendingBySession(String sessionId) {
        return store.findBySessionId(sessionId).stream()
                .filter(checkpoint -> checkpoint.getStatus() == AgentCheckpointStatus.WAITING_APPROVAL
                        || checkpoint.getStatus() == AgentCheckpointStatus.FAILED
                        || checkpoint.getStatus() == AgentCheckpointStatus.UNKNOWN)
                .map(this::toView)
                .toList();
    }

    public synchronized List<AgentCheckpoint> recoverAfterRestart() {
        List<AgentCheckpoint> resumable = new java.util.ArrayList<>();
        for (AgentCheckpoint checkpoint : store.findAll()) {
            if (checkpoint.getStatus() == AgentCheckpointStatus.APPROVED
                    || checkpoint.getStatus() == AgentCheckpointStatus.REJECTED
                    || checkpoint.getStatus() == AgentCheckpointStatus.TOOL_COMPLETED
                    || checkpoint.getStatus() == AgentCheckpointStatus.REJECTION_COMPLETED) {
                // 尚未被消费者领取，启动后可以安全恢复。
                resumable.add(checkpoint);
            } else if (checkpoint.getStatus() == AgentCheckpointStatus.EXECUTING
                    || checkpoint.getStatus() == AgentCheckpointStatus.REJECTION_EXECUTING
                    || checkpoint.getStatus() == AgentCheckpointStatus.RESUMING) {
                // 无法判断进程退出前副作用是否已发生，禁止自动重试。
                checkpoint.setStatus(AgentCheckpointStatus.UNKNOWN);
                checkpoint.setErrorMessage("服务在工具执行期间重启，副作用状态未知，请人工核实，系统不会自动重试。");
                checkpoint.setUpdatedAt(Instant.now());
                store.save(checkpoint);
            }
        }
        return resumable;
    }

    public AgentCheckpointView toView(AgentCheckpoint checkpoint) {
        List<String> toolNames = checkpoint.getToolCalls() == null
                ? List.of()
                : checkpoint.getToolCalls().stream().map(CheckpointToolCall::getName).toList();
        String preview = checkpoint.getToolCalls() == null
                ? ""
                : checkpoint.getToolCalls().stream()
                    .map(call -> call.getName() + ": " + maskSecrets(call.getArguments()))
                    .collect(Collectors.joining("\n"));
        return AgentCheckpointView.builder()
                .id(checkpoint.getId())
                .sessionId(checkpoint.getSessionId())
                .status(checkpoint.getStatus())
                .toolNames(toolNames)
                .argumentPreview(limit(preview, properties.getArgumentPreviewMaxChars()))
                .createdAt(checkpoint.getCreatedAt())
                .errorMessage(checkpoint.getErrorMessage())
                .build();
    }

    private AgentCheckpoint requireWaiting(String checkpointId) {
        AgentCheckpoint checkpoint = require(checkpointId);
        if (checkpoint.getStatus() != AgentCheckpointStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("检查点已处理，当前状态: " + checkpoint.getStatus());
        }
        return checkpoint;
    }

    private CheckpointToolCall copyToolCall(AssistantMessage.ToolCall call) {
        return CheckpointToolCall.builder()
                .id(call.id())
                .type(call.type())
                .name(call.name())
                .arguments(call.arguments())
                .build();
    }

    private void sendRequired(AgentCheckpoint checkpoint) {
        sseService.send(checkpoint.getSessionId(), SseMessage.builder()
                .type(SseMessage.Type.AI_APPROVAL_REQUIRED)
                .payload(SseMessage.Payload.builder().checkpoint(toView(checkpoint)).build())
                .metadata(SseMessage.Metadata.builder().checkpointId(checkpoint.getId()).build())
                .build());
    }

    private void sendResolved(AgentCheckpoint checkpoint) {
        sseService.send(checkpoint.getSessionId(), SseMessage.builder()
                .type(SseMessage.Type.AI_APPROVAL_RESOLVED)
                .payload(SseMessage.Payload.builder().checkpoint(toView(checkpoint)).build())
                .metadata(SseMessage.Metadata.builder().checkpointId(checkpoint.getId()).build())
                .build());
    }

    private String maskSecrets(String arguments) {
        if (!StringUtils.hasText(arguments)) {
            return "{}";
        }
        return arguments
                .replaceAll("(?i)(password|passwd|token|api[_-]?key|authorization|secret)\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?[^,}\\s\\\"']+", "$1:***")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer ***");
    }

    private String limit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)) + "…";
    }
}
