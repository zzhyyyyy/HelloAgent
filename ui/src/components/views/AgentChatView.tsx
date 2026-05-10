import React, { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { Tag, message as antdMessage } from "antd";
import { TeamOutlined, UserOutlined } from "@ant-design/icons";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createChatMessage,
  createChatSession,
  getChatMessagesBySessionId,
  getChatSession,
} from "../../api/api.ts";
import { SSE_BASE_URL } from "../../api/http.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";

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

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => [...prevMessages, message]);
  };

  const [agentId, setAgentId] = useState<string>("");
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
      if (sessionResp.chatSession.mode === "MULTI") {
        setMultiAgentMode(true);
      }
    };
    fetchData().then();
  }, [chatSessionId]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages().then();
  }, [chatSessionId, getChatMessages]);

  const handleSendMessage = async (
    value: string | { text: string },
    selectedAgentId?: string,
    mode?: string,
  ) => {
    // 处理 Sender 组件可能传递的不同格式
    const message = typeof value === "string" ? value : value.text;

    if (!message || !message.trim()) return;

    // 如果没有 chatSessionId，创建新会话
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
        // 刷新聊天会话列表
        await refreshChatSessions();
        // 导航到新创建的会话
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
        antdMessage.error("创建聊天会话失败，请重试");
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
          antdMessage.error("发送消息失败，请重试");
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
    // sse 连接处理, 不是对话消息不开连接
    if (!chatSessionId) {
      return;
    }
    const es = new EventSource(`${SSE_BASE_URL}/connect/${chatSessionId}`);
    es.onmessage = (event) => {
      console.log("Received message:", event.data);
    };
    es.onerror = (error) => {
      console.error("SSE error:", error);
    };

    es.addEventListener("message", (event) => {
      // 解析 JSON
      const message = JSON.parse(event.data) as SseMessage;
      if (message.type === "AI_GENERATED_CONTENT") {
        // 将 AI 生成的内容存到 messages 中
        addMessage(message.payload.message);
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
        throw new Error(`Unknown message type: ${message.type}`);
      }
    });

    es.addEventListener("init", (event) => {
      console.log("Received init message:", event.data);
    });

    return () => {
      console.log("Closing SSE connection.");
      es.close();
    };
  }, [chatSessionId]);

  // 如果没有 chatSessionId，显示提示界面
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

  // 如果有 chatSessionId，显示正常的聊天界面
  return (
    <div className="flex flex-col h-full">
      {/* 模式指示器 */}
      {chatSessionId && (
        <div className="flex justify-end px-16 pt-2">
          {multiAgentMode ? (
            <Tag icon={<TeamOutlined />} color="blue" className="text-xs">
              多 Agent 协作模式
            </Tag>
          ) : (
            <Tag icon={<UserOutlined />} color="default" className="text-xs">
              单 Agent 模式
            </Tag>
          )}
        </div>
      )}
      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
      />
      <div className="border-t border-gray-200 p-4 bg-white">
        <AgentChatInput onSend={handleSendMessage} />
      </div>
    </div>
  );
};

export default AgentChatView;
