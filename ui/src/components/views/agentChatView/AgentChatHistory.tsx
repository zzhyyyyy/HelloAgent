import React, { useState, useRef, useEffect, useCallback } from "react";
import XMarkdown from "@ant-design/x-markdown";
import {
  ToolOutlined,
  CheckCircleOutlined,
  RobotOutlined,
  UserOutlined,
  DownOutlined,
  RightOutlined,
  DownloadOutlined,
  LoadingOutlined,
} from "@ant-design/icons";
import { BASE_URL } from "../../../api/http";
import type {
  ChatMessageVO,
  SseMessageType,
  ToolCall,
  ToolResponse,
} from "../../../types";

interface AgentChatHistoryProps {
  messages: ChatMessageVO[];
  displayAgentStatus?: boolean;
  agentStatusText?: string;
  agentStatusType?: SseMessageType;
}

/* —— 工具调用 chip(inline,挂在 assistant 消息顶部) —— */
const ToolCallChip: React.FC<{ toolCall: ToolCall }> = ({ toolCall }) => {
  let parsedArgs: Record<string, unknown> = {};
  try {
    parsedArgs = JSON.parse(toolCall.arguments) as Record<string, unknown>;
  } catch {
    /* ignore */
  }
  const keys = Object.keys(parsedArgs);
  const argPreview =
    keys.length > 0
      ? keys.slice(0, 2).join(", ") + (keys.length > 2 ? "…" : "")
      : toolCall.arguments.slice(0, 40) +
        (toolCall.arguments.length > 40 ? "…" : "");

  return (
    <div className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full surface-muted text-[11px]">
      <ToolOutlined className="brand-text" />
      <span className="font-mono brand-text font-medium">{toolCall.name}</span>
      {argPreview && (
        <>
          <span className="text-subtle">·</span>
          <span className="text-muted truncate max-w-[180px]">
            {argPreview}
          </span>
        </>
      )}
    </div>
  );
};

/* —— 工具响应卡片 —— */
const ToolResponseCard: React.FC<{ toolResponse: ToolResponse }> = ({
  toolResponse,
}) => {
  const [expanded, setExpanded] = useState(false);

  let parsedData: unknown = null;
  let isJson = false;
  let dataPreview = "";

  try {
    parsedData = JSON.parse(toolResponse.responseData);
    isJson = true;
    const jsonStr = JSON.stringify(parsedData);
    dataPreview = jsonStr.length > 100 ? jsonStr.slice(0, 100) + "…" : jsonStr;
  } catch {
    dataPreview =
      toolResponse.responseData.length > 100
        ? toolResponse.responseData.slice(0, 100) + "…"
        : toolResponse.responseData;
  }

  return (
    <div className="surface-card border border-hairline rounded-xl overflow-hidden">
      <button
        className="w-full flex items-center gap-2 px-3 py-2 text-xs hover:surface-muted transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? (
          <DownOutlined className="text-subtle text-[10px]" />
        ) : (
          <RightOutlined className="text-subtle text-[10px]" />
        )}
        <CheckCircleOutlined className="text-emerald-500" />
        <span className="font-mono text-emerald-600 dark:text-emerald-400 font-medium">
          {toolResponse.name}
        </span>
        <span className="text-subtle">·</span>
        <span className="text-muted truncate flex-1 text-left">
          {dataPreview}
        </span>
      </button>
      {expanded && (
        <div className="border-t border-hairline px-3 py-2 surface-muted">
          <pre className="text-[11px] text-default font-mono whitespace-pre-wrap break-words overflow-x-auto max-h-72 overflow-y-auto m-0">
            {isJson
              ? JSON.stringify(parsedData, null, 2)
              : toolResponse.responseData}
          </pre>
        </div>
      )}
    </div>
  );
};

/* —— 角色标签 —— */
const AgentRoleBadge: React.FC<{ role: string }> = ({ role }) => {
  const isSupervisor = role === "supervisor";
  const isSpecialist = role.startsWith("specialist:");
  const label = isSupervisor
    ? "协调员"
    : isSpecialist
      ? `专家:${role.replace("specialist:", "").split("#")[0]}`
      : role;
  const cls = isSupervisor
    ? "bg-purple-100 text-purple-700 dark:bg-purple-500/10 dark:text-purple-300"
    : isSpecialist
      ? "bg-blue-100 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300"
      : "surface-muted text-muted";
  return (
    <span
      className={`text-[10px] font-medium px-2 py-0.5 rounded-full ${cls}`}
    >
      {label}
    </span>
  );
};

/* —— 主组件 —— */
const AgentChatHistory: React.FC<AgentChatHistoryProps> = ({
  messages,
  displayAgentStatus = false,
  agentStatusText = "",
  agentStatusType,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [isNearBottom, setIsNearBottom] = useState(true);
  const SCROLL_THRESHOLD = 20;
  const prevMessagesLengthRef = useRef(messages.length);

  const checkIfNearBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return false;
    const { scrollTop, clientHeight, scrollHeight } = container;
    return scrollHeight - scrollTop - clientHeight <= SCROLL_THRESHOLD;
  }, []);

  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return;
    requestAnimationFrame(() => {
      if (container) container.scrollTop = container.scrollHeight;
    });
  }, []);

  const handleScroll = useCallback(() => {
    setIsNearBottom(checkIfNearBottom());
  }, [checkIfNearBottom]);

  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;
    const initTimer = setTimeout(
      () => setIsNearBottom(checkIfNearBottom()),
      0,
    );
    container.addEventListener("scroll", handleScroll, { passive: true });
    return () => {
      clearTimeout(initTimer);
      container.removeEventListener("scroll", handleScroll);
    };
  }, [handleScroll, checkIfNearBottom]);

  useEffect(() => {
    const hasNewMessage = messages.length > prevMessagesLengthRef.current;
    prevMessagesLengthRef.current = messages.length;
    if (hasNewMessage && isNearBottom) scrollToBottom();
  }, [messages, isNearBottom, scrollToBottom]);

  useEffect(() => {
    if (displayAgentStatus && isNearBottom) scrollToBottom();
  }, [displayAgentStatus, isNearBottom, scrollToBottom]);

  const getStatusLabel = () => {
    switch (agentStatusType) {
      case "AI_PLANNING":
        return "规划中";
      case "AI_THINKING":
        return "思考中";
      case "AI_EXECUTING":
        return "执行中";
      default:
        return "处理中";
    }
  };

  return (
    <div
      ref={scrollContainerRef}
      className="flex-1 overflow-y-auto"
    >
      <div className="max-w-3xl mx-auto px-6 py-6 space-y-5">
        {messages.map((message) => {
          /* —— assistant —— */
          if (message.role === "assistant") {
            return (
              <div
                key={message.id}
                className="flex gap-3 animate-app-fade-in"
              >
                <div className="brand-gradient w-8 h-8 rounded-full flex items-center justify-center shrink-0">
                  <RobotOutlined className="text-white text-sm" />
                </div>
                <div className="flex-1 min-w-0 space-y-2">
                  {message.metadata?.agentRole && (
                    <AgentRoleBadge role={message.metadata.agentRole} />
                  )}
                  {message.metadata?.toolCalls &&
                    message.metadata.toolCalls.length > 0 && (
                      <div className="flex flex-wrap gap-1.5">
                        {message.metadata.toolCalls.map((toolCall) => (
                          <ToolCallChip
                            key={toolCall.id}
                            toolCall={toolCall}
                          />
                        ))}
                      </div>
                    )}
                  {message.content && (
                    <div className="surface-card border border-hairline rounded-2xl rounded-tl-md px-4 py-3 shadow-soft">
                      <div className="prose prose-sm max-w-none text-default">
                        <XMarkdown
                          streaming={{
                            enableAnimation: false,
                            hasNextChunk: true,
                          }}
                        >
                          {message.content}
                        </XMarkdown>
                      </div>
                      <div className="mt-2 flex items-center justify-end gap-3 text-[11px] text-subtle">
                        {message.metadata?.evalScore && (
                          <span
                            title={`正确性 ${message.metadata.evalScore.correctness ?? "-"}/5 · 完整性 ${message.metadata.evalScore.completeness ?? "-"}/5 · 相关性 ${message.metadata.evalScore.relevance ?? "-"}/5${message.metadata.evalScore.judgeModel ? ` · judge: ${message.metadata.evalScore.judgeModel}` : ""}${message.metadata.evalScore.reasoning ? `\n${message.metadata.evalScore.reasoning}` : ""}`}
                            className="inline-flex items-center gap-1 font-mono"
                          >
                            <span className="text-emerald-600 dark:text-emerald-400">
                              ⭐ 正 {message.metadata.evalScore.correctness ?? "-"}
                            </span>
                            <span className="text-amber-600 dark:text-amber-400">
                              · 全 {message.metadata.evalScore.completeness ?? "-"}
                            </span>
                            <span className="text-sky-600 dark:text-sky-400">
                              · 关 {message.metadata.evalScore.relevance ?? "-"}
                            </span>
                          </span>
                        )}
                        {message.metadata?.usage && (
                          <span
                            title={`prompt: ${message.metadata.usage.promptTokens ?? "-"} · completion: ${message.metadata.usage.completionTokens ?? "-"}${message.metadata.usage.model ? ` · model: ${message.metadata.usage.model}` : ""}${message.metadata.usage.finishReason ? ` · finish: ${message.metadata.usage.finishReason}` : ""}`}
                            className="font-mono"
                          >
                            ↑ {message.metadata.usage.promptTokens ?? "-"}
                            <span className="mx-1">·</span>
                            ↓ {message.metadata.usage.completionTokens ?? "-"}
                            {message.metadata.usage.model && (
                              <span className="ml-1.5 opacity-60">
                                {message.metadata.usage.model}
                              </span>
                            )}
                          </span>
                        )}
                        <a
                          href={`${BASE_URL}/chat-messages/${message.id}/pdf`}
                          download
                          className="inline-flex items-center gap-1 hover:brand-text transition-colors"
                        >
                          <DownloadOutlined />
                          下载 PDF
                        </a>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            );
          }

          /* —— tool —— */
          if (message.role === "tool" && message.metadata?.toolResponse) {
            return (
              <div key={message.id} className="flex gap-3 animate-app-fade-in">
                <div className="w-8 shrink-0" />
                <div className="flex-1 min-w-0">
                  <ToolResponseCard
                    toolResponse={message.metadata.toolResponse}
                  />
                </div>
              </div>
            );
          }

          /* —— user —— */
          if (message.role === "user") {
            return (
              <div
                key={message.id}
                className="flex gap-3 justify-end animate-app-fade-in"
              >
                <div className="max-w-[80%] rounded-2xl rounded-tr-md px-4 py-2.5 shadow-soft brand-gradient">
                  <div className="text-white whitespace-pre-wrap break-words text-sm leading-relaxed">
                    {message.content}
                  </div>
                </div>
                <div className="w-8 h-8 rounded-full surface-muted border border-app flex items-center justify-center shrink-0">
                  <UserOutlined className="text-muted text-sm" />
                </div>
              </div>
            );
          }

          /* —— system —— */
          if (message.role === "system") {
            return (
              <div key={message.id} className="flex justify-center">
                <div className="px-3 py-1 surface-muted text-muted text-xs rounded-full flex items-center gap-1.5">
                  <RobotOutlined />
                  <span>{message.content}</span>
                </div>
              </div>
            );
          }

          return null;
        })}

        {displayAgentStatus && (
          <div className="flex gap-3 animate-app-fade-in">
            <div className="brand-gradient w-8 h-8 rounded-full flex items-center justify-center shrink-0">
              <LoadingOutlined className="text-white text-sm" spin />
            </div>
            <div className="flex-1 min-w-0">
              <div className="surface-card border border-hairline rounded-2xl rounded-tl-md px-4 py-3 shadow-soft inline-flex items-center gap-2">
                <span className="brand-text text-sm font-medium">
                  {getStatusLabel()}
                </span>
                {agentStatusText && (
                  <>
                    <span className="text-subtle">·</span>
                    <span className="text-muted text-sm">
                      {agentStatusText}
                    </span>
                  </>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default AgentChatHistory;
