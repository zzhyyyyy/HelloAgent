import React, { useState, useMemo } from "react";
import { Select, Switch, message as antdMessage, Tooltip } from "antd";
import {
  DownOutlined,
  TeamOutlined,
  UserOutlined,
  ThunderboltOutlined,
  ReadOutlined,
  ShoppingOutlined,
  BulbOutlined,
} from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { type AgentVO } from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import EmbeddingModal from "../../modals/EmbeddingModal.tsx";
import RagEvaluationModal from "../../modals/RagEvaluationModal.tsx";

interface DefaultAgentChatViewProps {
  handleSendMessage: (
    message: string,
    agentId?: string,
    mode?: string,
  ) => Promise<void>;
  loading: boolean;
  agents: AgentVO[];
  multiAgentMode: boolean;
  onModeChange: (mode: boolean) => void;
}

const SUGGESTIONS = [
  {
    icon: <ShoppingOutlined />,
    title: "帮我推荐一款主力机",
    desc: "预算 4000 以内,日常拍照轻度游戏",
  },
  {
    icon: <ReadOutlined />,
    title: "检索知识库",
    desc: "在所有知识库中找一下「退款流程」",
  },
  {
    icon: <ThunderboltOutlined />,
    title: "比较两款产品",
    desc: "对比 iPhone 16 和 小米 15,从续航和拍照角度",
  },
  {
    icon: <BulbOutlined />,
    title: "联网搜索",
    desc: "今天新发布的科技新闻有哪些?",
  },
];

const EmptyAgentChatView: React.FC<DefaultAgentChatViewProps> = ({
  handleSendMessage,
  loading,
  agents,
  multiAgentMode,
  onModeChange,
}) => {
  const [message, setMessage] = useState("");
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);

  const agentsWithEmoji = useMemo(
    () =>
      agents.map((agent) => ({
        ...agent,
        emoji: getAgentEmoji(agent.id),
      })),
    [agents],
  );

  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId) return selectedAgentId;
    return agents.length > 0 ? agents[0].id : null;
  }, [selectedAgentId, agents]);

  const activeAgent = useMemo(
    () => agentsWithEmoji.find((a) => a.id === effectiveAgentId),
    [agentsWithEmoji, effectiveAgentId],
  );

  const submit = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed) {
      antdMessage.warning("请输入消息");
      return;
    }
    if (!effectiveAgentId) {
      antdMessage.warning("请先创建一个智能体助手");
      return;
    }
    try {
      await handleSendMessage(
        trimmed,
        effectiveAgentId,
        multiAgentMode ? "MULTI" : "SINGLE",
      );
      setMessage("");
    } catch (error) {
      antdMessage.error(error instanceof Error ? error.message : "发送消息失败");
    }
  };

  return (
    <div className="flex flex-col h-full">
      {/* 顶部:Agent 选择 + 模式开关 */}
      {agents.length > 0 && (
        <div className="h-14 flex items-center justify-between px-6 glass border-b border-hairline shrink-0">
          <Select
            value={effectiveAgentId}
            onChange={setSelectedAgentId}
            style={{ width: 220 }}
            variant="borderless"
            suffixIcon={<DownOutlined className="text-subtle" />}
            placeholder="选择智能体助手"
            optionRender={(option) => (
              <div className="flex items-center gap-2">
                <span className="text-lg">
                  {agentsWithEmoji.find((a) => a.id === option.value)?.emoji}
                </span>
                <span className="text-sm">{option.label}</span>
              </div>
            )}
            options={agentsWithEmoji.map((agent) => ({
              value: agent.id,
              label: agent.name,
            }))}
          />
          <div className="flex items-center gap-3">
            <EmbeddingModal />
            <RagEvaluationModal />
            <div className="h-4 w-px bg-app" />
            <Tooltip
              title={multiAgentMode ? "多 Agent 协作模式" : "单 Agent 模式"}
            >
              <div className="flex items-center gap-2 px-2 py-1 rounded-lg surface-muted">
                <UserOutlined
                  className={
                    !multiAgentMode ? "brand-text text-xs" : "text-subtle text-xs"
                  }
                />
                <Switch
                  checked={multiAgentMode}
                  onChange={onModeChange}
                  size="small"
                />
                <TeamOutlined
                  className={
                    multiAgentMode ? "brand-text text-xs" : "text-subtle text-xs"
                  }
                />
              </div>
            </Tooltip>
          </div>
        </div>
      )}

      {/* 中心 Hero */}
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-3xl mx-auto px-6 py-16 flex flex-col items-center animate-app-fade-in">
          <div className="brand-gradient w-16 h-16 rounded-2xl flex items-center justify-center shadow-glow mb-6">
            <span className="text-white font-bold text-2xl select-none">H</span>
          </div>
          <h1 className="text-3xl font-semibold text-strong tracking-tight mb-2">
            你好,我是 {activeAgent?.name ?? "HelloAgent"}
          </h1>
          <p className="text-muted text-base text-center max-w-xl mb-10">
            {activeAgent?.description?.trim() ||
              "支持工具调用、知识库检索、多 Agent 协作。试试在下方输入,或者从下面挑一个示例开始。"}
          </p>

          {/* 快捷提问 */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 w-full">
            {SUGGESTIONS.map((s) => (
              <button
                key={s.title}
                onClick={() => submit(`${s.title}。${s.desc}`)}
                className="group text-left surface-card border border-hairline rounded-xl p-4 hover:shadow-elevated hover:border-app transition-all duration-200"
              >
                <div className="flex items-start gap-3">
                  <div className="w-9 h-9 rounded-lg surface-muted flex items-center justify-center brand-text text-base shrink-0 group-hover:scale-105 transition-transform">
                    {s.icon}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="text-[13px] font-medium text-strong leading-snug truncate">
                      {s.title}
                    </div>
                    <div className="text-xs text-muted mt-1 line-clamp-2 leading-relaxed">
                      {s.desc}
                    </div>
                  </div>
                </div>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Sender */}
      <div className="px-6 pb-6 pt-2">
        <div className="max-w-3xl mx-auto">
          <div className="surface-card border border-app rounded-2xl shadow-soft p-2">
            <Sender
              onSubmit={() => submit(message)}
              value={message}
              loading={loading}
              placeholder="输入消息开始对话…"
              onChange={setMessage}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
