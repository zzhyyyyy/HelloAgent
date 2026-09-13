import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { Button, Tag, message as antdMessage } from "antd";
import { TeamOutlined, UserOutlined } from "@ant-design/icons";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import RagEvaluationModal from "../modals/RagEvaluationModal.tsx";
import EmbeddingModal from "../modals/EmbeddingModal.tsx";
import {
  createChatMessage,
  createChatSession,
  approveCheckpoint,
  getChatMessagesBySessionId,
  getChatSession,
  getPendingCheckpoints,
  rejectCheckpoint,
} from "../../api/api.ts";
import { SSE_BASE_URL } from "../../api/http.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type {
  AgentCheckpoint,
  ChatMessageVO,
  SseMessage,
  SseMessageType,
} from "../../types";

interface AgentChatRouteState {
  init?: boolean;
  initMessage?: string;
  agentId?: string;
}

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const state = (location.state as AgentChatRouteState | null) ?? null;
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [pendingCheckpoints, setPendingCheckpoints] = useState<AgentCheckpoint[]>([]);
  const [decidingCheckpointId, setDecidingCheckpointId] = useState<string>();

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => [...prevMessages, message]);
  };

  const [agentId, setAgentId] = useState<string>("");
  const [sessionTitle, setSessionTitle] = useState<string>("");
  const [multiAgentMode, setMultiAgentMode] = useState(false);
  const initMessageKeyRef = useRef<string | null>(null);

  const getChatMessages = useCallback(async () => {
    if (!chatSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(chatSessionId);
    setMessages(resp.chatMessages);

    const fetchData = async () => {
      const sessionResp = await getChatSession(chatSessionId);
      setAgentId(sessionResp.chatSession.agentId);
      const title = (sessionResp.chatSession as { title?: string }).title;
      if (title) setSessionTitle(title);
      if (sessionResp.chatSession.mode === "MULTI") {
        setMultiAgentMode(true);
      }
    };
    fetchData().then();
  }, [chatSessionId]);

  const refreshPendingCheckpoints = useCallback(async () => {
    if (!chatSessionId) return;
    const checkpoints = await getPendingCheckpoints(chatSessionId);
    setPendingCheckpoints(checkpoints);
  }, [chatSessionId]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages().then();
    refreshPendingCheckpoints().catch((error) => {
      console.error("加载待审批检查点失败:", error);
    });
  }, [chatSessionId, getChatMessages, refreshPendingCheckpoints]);

  const handleSendMessage = async (
    value: string | { text: string },
    selectedAgentId?: string,
    mode?: string,
  ) => {
    const message = typeof value === "string" ? value : value.text;
    if (!message || !message.trim()) return;

    if (!chatSessionId) {
      const nextAgentId = selectedAgentId ?? agentId;

      if (!nextAgentId) {
        antdMessage.warning("请先创建一个智能体助手");
        return;
      }
      setLoading(true);
      try {
        const response = await createChatSession({
          agentId: nextAgentId,
          title: message.slice(0, 20),
          mode: mode ?? (multiAgentMode ? "MULTI" : "SINGLE"),
        });
        await refreshChatSessions();
        navigate(`/chat/${response.chatSessionId}`, {
          replace: true,
          state: {
            init: true,
            initMessage: message,
            agentId: nextAgentId,
          },
        });
      } catch (error) {
        console.error("创建聊天会话失败:", error);
        antdMessage.error("创建聊天会话失败,请重试");
      } finally {
        setLoading(false);
      }
    } else {
      await createChatMessage({
        agentId: selectedAgentId ?? agentId ?? state?.agentId ?? "",
        sessionId: chatSessionId,
        role: "user",
        content: message,
      });
      await getChatMessages();
    }
  };

  useEffect(() => {
    if (!chatSessionId || !agentId || !state?.init || !state.initMessage) {
      return;
    }

    const initMessageKey = `${chatSessionId}:${state.initMessage}`;
    if (initMessageKeyRef.current === initMessageKey) {
      return;
    }

    let cancelled = false;
    initMessageKeyRef.current = initMessageKey;

    const sendInitMessage = async () => {
      try {
        setLoading(true);
        await createChatMessage({
          agentId: state.agentId ?? agentId,
          sessionId: chatSessionId,
          role: "user",
          content: state.initMessage ?? "",
        });
        if (!cancelled) {
          await getChatMessages();
          navigate(`/chat/${chatSessionId}`, { replace: true });
        }
      } catch (error) {
        initMessageKeyRef.current = null;
        console.error("发送初始消息失败:", error);
        if (!cancelled) {
          antdMessage.error("发送消息失败,请重试");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    sendInitMessage().then();

    return () => {
      cancelled = true;
    };
  }, [agentId, chatSessionId, getChatMessages, navigate, state]);

  const [displayAgentStatus, setDisplayAgentStatus] = useState<boolean>(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<
    SseMessageType | undefined
  >(undefined);

  useEffect(() => {
    if (!chatSessionId) return;
    const es = new EventSource(`${SSE_BASE_URL}/connect/${chatSessionId}`);
    es.onmessage = (event) => {
      console.log("Received message:", event.data);
    };
    es.onerror = (error) => {
      console.error("SSE error:", error);
    };

    es.addEventListener("message", (event) => {
      // 全局 try/catch:单条 SSE 消息异常不应该拖垮整个 React 树
      try {
        const message = JSON.parse(event.data) as SseMessage;
        if (message.type === "AI_GENERATED_CONTENT") {
          addMessage(message.payload.message);
        } else if (message.type === "AI_STREAM_CHUNK") {
          // 流式 chunk:用 streamId(chunk.id)定位 placeholder。
          // done=true 时把 id 换成持久化后的 DB id,PDF 下载链接才能正确工作。
          // 由于 done=true 是该 think 迭代的最后一帧,后续不会再有 chunk 引用旧 streamId,swap 是安全的。
          // 配合根 ErrorBoundary,即便 XMarkdown 重挂载意外抛错也只会显示局部回退。
          const chunk = message.payload.message;
          if (!chunk || !chunk.id) return;
          const done = message.payload.done;
          const persistedId = message.metadata?.chatMessageId;
          const finalId = done && persistedId ? persistedId : chunk.id;
          setMessages((prev) => {
            const idx = prev.findIndex(
              (m) => m.id === chunk.id || m.id === finalId,
            );
            if (idx === -1) {
              return [...prev, { ...chunk, id: finalId }];
            }
            const next = [...prev];
            next[idx] = { ...chunk, id: finalId };
            return next;
          });
        } else if (message.type === "AI_EVAL_SCORE") {
          // Agent 持久化后,后端 LLM-as-Judge 异步评分回传 —— 合并到对应消息的 metadata.evalScore
          const messageId = message.metadata?.chatMessageId;
          const score = message.payload?.message?.metadata?.evalScore;
          if (!messageId || !score) return;
          setMessages((prev) => {
            const idx = prev.findIndex((m) => m.id === messageId);
            if (idx === -1) return prev;
            const next = [...prev];
            next[idx] = {
              ...next[idx],
              metadata: {
                ...(next[idx].metadata ?? {}),
                evalScore: score,
              },
            };
            return next;
          });
        } else if (message.type === "AI_APPROVAL_REQUIRED") {
          const checkpoint = message.payload?.checkpoint;
          if (!checkpoint) return;
          setDisplayAgentStatus(false);
          setPendingCheckpoints((prev) => {
            const withoutCurrent = prev.filter((item) => item.id !== checkpoint.id);
            return [...withoutCurrent, checkpoint];
          });
        } else if (message.type === "AI_APPROVAL_RESOLVED") {
          const checkpoint = message.payload?.checkpoint;
          if (!checkpoint) return;
          setPendingCheckpoints((prev) => {
            const withoutCurrent = prev.filter((item) => item.id !== checkpoint.id);
            return checkpoint.status === "FAILED" || checkpoint.status === "UNKNOWN"
              ? [...withoutCurrent, checkpoint]
              : withoutCurrent;
          });
        } else if (message.type === "AI_PLANNING") {
          setDisplayAgentStatus(true);
          setAgentStatusText(message.payload.statusText);
          setAgentStatusType("AI_PLANNING");
        } else if (message.type === "AI_THINKING") {
          setDisplayAgentStatus(true);
          setAgentStatusText(message.payload.statusText);
          setAgentStatusType("AI_THINKING");
        } else if (message.type === "AI_EXECUTING") {
          setDisplayAgentStatus(true);
          setAgentStatusText(message.payload.statusText);
          setAgentStatusType("AI_EXECUTING");
        } else if (message.type === "AI_DONE") {
          setDisplayAgentStatus(false);
          setAgentStatusText("");
          setAgentStatusType(undefined);
        } else {
          console.warn("Unknown SSE message type:", message.type);
        }
      } catch (err) {
        console.error("SSE message handler error (suppressed):", err, "raw:", event.data);
      }
    });

    es.addEventListener("init", (event) => {
      console.log("Received init message:", event.data);
    });

    return () => {
      console.log("Closing SSE connection.");
      es.close();
    };
  }, [chatSessionId, getChatMessages, refreshPendingCheckpoints]);

  const decideCheckpoint = async (checkpointId: string, approved: boolean) => {
    setDecidingCheckpointId(checkpointId);
    try {
      if (approved) {
        await approveCheckpoint(checkpointId);
        antdMessage.success("已批准，正在执行并继续任务");
      } else {
        await rejectCheckpoint(checkpointId);
        antdMessage.info("已拒绝，危险工具不会执行");
      }
      setPendingCheckpoints((prev) => prev.filter((item) => item.id !== checkpointId));
    } catch (error) {
      console.error("处理检查点失败:", error);
      antdMessage.error("处理审批失败，请刷新后重试");
      await refreshPendingCheckpoints();
    } finally {
      setDecidingCheckpointId(undefined);
    }
  };

  // ⚠️ 所有 hook 必须在条件 return 之前调用,否则前后 render 的 hook 数量不一致会触发
  // "Rendered fewer hooks than expected"。
  const sessionTokens = useMemo(() => {
    let prompt = 0;
    let completion = 0;
    for (const m of messages) {
      const u = m.metadata?.usage;
      if (u) {
        prompt += u.promptTokens ?? 0;
        completion += u.completionTokens ?? 0;
      }
    }
    return { prompt, completion, total: prompt + completion };
  }, [messages]);

  if (!chatSessionId) {
    return (
      <EmptyAgentChatView
        agents={agents}
        loading={loading}
        handleSendMessage={handleSendMessage}
        multiAgentMode={multiAgentMode}
        onModeChange={setMultiAgentMode}
      />
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* 顶部:sticky glass bar */}
      <div className="h-14 flex items-center justify-between px-6 glass border-b border-hairline shrink-0">
        <div className="flex items-center gap-3 min-w-0">
          <div className="text-[15px] font-semibold text-strong truncate max-w-[420px]">
            {sessionTitle || "新会话"}
          </div>
          {multiAgentMode ? (
            <Tag
              icon={<TeamOutlined />}
              color="purple"
              bordered={false}
              className="text-xs"
            >
              多 Agent 协作
            </Tag>
          ) : (
            <Tag
              icon={<UserOutlined />}
              color="default"
              bordered={false}
              className="text-xs"
            >
              单 Agent
            </Tag>
          )}
          {sessionTokens.total > 0 && (
            <Tag
              bordered={false}
              color="blue"
              className="text-xs font-mono"
              title={`本会话累计 token:prompt ${sessionTokens.prompt} + completion ${sessionTokens.completion} = ${sessionTokens.total}`}
            >
              ↑{sessionTokens.prompt} · ↓{sessionTokens.completion}
            </Tag>
          )}
        </div>
        <div className="flex items-center gap-2">
          <EmbeddingModal />
          <RagEvaluationModal />
        </div>
      </div>

      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
      />

      {pendingCheckpoints.length > 0 && (
        <div className="px-6 pt-2 shrink-0">
          <div className="max-w-3xl mx-auto space-y-2">
            {pendingCheckpoints.map((checkpoint) => {
              const waiting = checkpoint.status === "WAITING_APPROVAL";
              return (
                <div
                  key={checkpoint.id}
                  className="rounded-2xl border border-amber-300 bg-amber-50 px-4 py-3 shadow-sm"
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <div className="font-semibold text-amber-900">
                        {waiting ? "危险操作需要你的确认" : "危险操作状态需要人工核实"}
                      </div>
                      <div className="mt-1 text-sm text-amber-800">
                        工具：{checkpoint.toolNames.join("、")}
                      </div>
                      {checkpoint.argumentPreview && (
                        <pre className="mt-2 max-h-28 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-white/70 p-2 text-xs text-amber-950">
                          {checkpoint.argumentPreview}
                        </pre>
                      )}
                      {checkpoint.errorMessage && (
                        <div className="mt-2 text-xs text-red-700">{checkpoint.errorMessage}</div>
                      )}
                    </div>
                    {waiting && (
                      <div className="flex shrink-0 gap-2">
                        <Button
                          danger
                          disabled={decidingCheckpointId === checkpoint.id}
                          onClick={() => decideCheckpoint(checkpoint.id, false)}
                        >
                          拒绝
                        </Button>
                        <Button
                          type="primary"
                          loading={decidingCheckpointId === checkpoint.id}
                          onClick={() => decideCheckpoint(checkpoint.id, true)}
                        >
                          批准并继续
                        </Button>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* 底部输入 */}
      <div className="px-6 pb-6 pt-2 shrink-0">
        <div className="max-w-3xl mx-auto">
          <div className="surface-card border border-app rounded-2xl shadow-soft p-2">
            <AgentChatInput onSend={handleSendMessage} />
          </div>
        </div>
      </div>
    </div>
  );
};

export default AgentChatView;
