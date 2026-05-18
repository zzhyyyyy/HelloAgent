import React, { useState, useMemo } from "react";
import { Card, Space, Typography, Select, Switch, Button, message as antdMessage } from "antd";
import {
  BulbOutlined,
  MessageOutlined,
  RobotOutlined,
  DownOutlined,
  TeamOutlined,
  UserOutlined,
  MoonOutlined,
  SunOutlined,
} from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { type AgentVO } from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import { useTheme } from "../../../contexts/ThemeContext.tsx";
import EmbeddingModal from "../../modals/EmbeddingModal.tsx";
import RagEvaluationModal from "../../modals/RagEvaluationModal.tsx";

const { Title, Text } = Typography;

interface DefaultAgentChatViewProps {
  handleSendMessage: (message: string, agentId?: string, mode?: string) => Promise<void>;
  loading: boolean;
  agents: AgentVO[];
  multiAgentMode: boolean;
  onModeChange: (mode: boolean) => void;
}

const EmptyAgentChatView: React.FC<DefaultAgentChatViewProps> = ({
  handleSendMessage,
  loading,
  agents,
  multiAgentMode,
  onModeChange,
}) => {
  const { theme, toggleTheme } = useTheme();
  const [message, setMessage] = useState("");
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);

  // 为每个 agent 生成 emoji
  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      emoji: getAgentEmoji(agent.id),
    }));
  }, [agents]);

  // 计算实际选中的 agent ID（如果用户没有选择，则使用默认的第一个）
  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId) {
      return selectedAgentId;
    }
    return agents.length > 0 ? agents[0].id : null;
  }, [selectedAgentId, agents]);

  return (
    <div className="flex flex-col h-full">
      {/* Agent 选择器 + 模式切换 - 顶部 */}
      {agents.length > 0 && (
        <div className="border-b border-gray-200 bg-white px-4 py-3">
          <div className="flex items-center justify-between">
            <Select
              value={effectiveAgentId}
              onChange={(value) => setSelectedAgentId(value)}
              style={{ width: 200 }}
              className="agent-selector"
              suffixIcon={<DownOutlined className="text-gray-400" />}
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
            <div className="flex items-center gap-2">
              <EmbeddingModal />
              <RagEvaluationModal />
              <div className="border-r border-gray-200 h-4 mx-1" />
              <UserOutlined className={`text-sm ${!multiAgentMode ? 'text-blue-500' : 'text-gray-400'}`} />
              <Switch
                checked={multiAgentMode}
                onChange={onModeChange}
                size="small"
                checkedChildren={<TeamOutlined />}
                unCheckedChildren={<UserOutlined />}
              />
              <TeamOutlined className={`text-sm ${multiAgentMode ? 'text-blue-500' : 'text-gray-400'}`} />
              <div className="border-r border-gray-200 h-4 mx-1" />
              <Button
                type="text"
                size="small"
                icon={theme === "dark" ? <SunOutlined /> : <MoonOutlined />}
                onClick={toggleTheme}
                className="text-gray-500 hover:text-blue-500"
              />
            </div>
          </div>
        </div>
      )}
      <div className="flex-1 flex items-center justify-center p-6">
        <div className="max-w-2xl w-full space-y-6">
          <div className="text-center mb-8">
            <Title level={2} className="mb-2">
              开始新的对话
            </Title>
            <Text type="secondary" className="text-base">
              选择一个智能体助手开始聊天，或直接发送消息创建新会话
            </Text>
          </div>
          <Space orientation="vertical" size="large" className="w-full">
            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-blue-400 to-purple-400 flex items-center justify-center">
                  <RobotOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    智能对话
                  </Title>
                  <Text type="secondary">
                    与 AI 助手进行智能对话，获取帮助和建议
                  </Text>
                </div>
              </Space>
            </Card>

            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-green-400 to-teal-400 flex items-center justify-center">
                  <BulbOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    知识问答
                  </Title>
                  <Text type="secondary">
                    基于知识库进行问答，获取准确的信息
                  </Text>
                </div>
              </Space>
            </Card>

            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-orange-400 to-red-400 flex items-center justify-center">
                  <MessageOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    快速开始
                  </Title>
                  <Text type="secondary">
                    在下方输入框输入消息，立即开始对话
                  </Text>
                </div>
              </Space>
            </Card>
          </Space>
        </div>
      </div>
      <div className="border-t border-gray-200 bg-white">
        {/* 输入框 */}
        <div className="px-4 pb-4 pt-4">
          <Sender
            onSubmit={async () => {
              const trimmedMessage = message.trim();
              if (!trimmedMessage) {
                antdMessage.warning("请输入消息");
                return;
              }
              if (!effectiveAgentId) {
                antdMessage.warning("请先创建一个智能体助手");
                return;
              }

              try {
                await handleSendMessage(trimmedMessage, effectiveAgentId, multiAgentMode ? "MULTI" : "SINGLE");
                setMessage("");
              } catch (error) {
                const errorMessage =
                  error instanceof Error ? error.message : "发送消息失败";
                antdMessage.error(errorMessage);
              }
            }}
            value={message}
            loading={loading}
            placeholder="输入消息开始对话..."
            onChange={(value) => {
              setMessage(value);
            }}
          />
        </div>
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
