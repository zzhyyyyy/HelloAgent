import React, { useState } from "react";
import { MoonOutlined, SunOutlined } from "@ant-design/icons";
import { Button, Tabs, Tooltip, type TabsProps } from "antd";
import { useNavigate } from "react-router-dom";
import AgentTabContent from "./tabs/AgentTabContent.tsx";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import ChatTabContent from "./tabs/ChatTabContent.tsx";
import KnowledgeBaseTabContent from "./tabs/KnowledgeBaseTabContent.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import { useAgents } from "../hooks/useAgents.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";
import { useTheme } from "../contexts/ThemeContext.tsx";

interface SideMenuProps {
  children?: React.ReactNode;
}

const SideMenu: React.FC<SideMenuProps> = () => {
  const navigate = useNavigate();
  const { theme, toggleTheme } = useTheme();

  const [isAddAgentModalOpen, setIsAddAgentModalOpen] = useState(false);
  const toggleAddAgentModal = () => {
    setIsAddAgentModalOpen(!isAddAgentModalOpen);
    setEditingAgent(null);
  };

  const [editingAgent, setEditingAgent] = useState<
    import("../api/api.ts").AgentVO | null
  >(null);

  const [isAddKnowledgeBaseModalOpen, setIsAddKnowledgeBaseModalOpen] =
    useState(false);
  const toggleAddKnowledgeBaseModal = () => {
    setIsAddKnowledgeBaseModalOpen(!isAddKnowledgeBaseModalOpen);
  };
  const { agents, createAgentHandle, deleteAgentHandle, updateAgentHandle } =
    useAgents();

  const [activeKey, setActiveKey] = useState(() => {
    if (location.pathname.startsWith("/agent")) return "agent";
    if (location.pathname.startsWith("/knowledge-base")) return "knowledgeBase";
    if (location.pathname.startsWith("/chat")) return "chat";
    return "agent";
  });

  const { knowledgeBases, createKnowledgeBaseHandle } = useKnowledgeBases();

  const handleTabChange = (key: string) => {
    setActiveKey(key);
  };

  const items: TabsProps["items"] = [
    {
      key: "agent",
      label: <span className="select-none">智能体</span>,
      children: (
        <AgentTabContent
          agents={agents}
          onSelectAgent={() => {}}
          onCreateAgentClick={toggleAddAgentModal}
          onEditAgent={(agent) => {
            setEditingAgent(agent);
            setIsAddAgentModalOpen(true);
          }}
          onDeleteAgent={deleteAgentHandle}
        />
      ),
    },
    {
      key: "chat",
      label: <span className="select-none">会话</span>,
      children: <ChatTabContent />,
    },
    {
      key: "knowledgeBase",
      label: <span className="select-none">知识库</span>,
      children: (
        <KnowledgeBaseTabContent
          knowledgeBases={knowledgeBases}
          onCreateKnowledgeBaseClick={toggleAddKnowledgeBaseModal}
          onSelectKnowledgeBase={(knowledgeBaseId) => {
            navigate(`/knowledge-base/${knowledgeBaseId}`);
          }}
        />
      ),
    },
  ];

  return (
    <div className="flex flex-col h-full px-3">
      {/* 品牌头部 */}
      <div className="h-16 w-full flex items-center px-2 gap-3 border-b border-hairline">
        <div className="brand-gradient w-9 h-9 rounded-xl flex items-center justify-center shadow-glow">
          <span className="text-white font-bold text-lg leading-none select-none">
            H
          </span>
        </div>
        <div className="flex flex-col leading-tight min-w-0">
          <div className="text-[15px] font-semibold text-strong select-none truncate">
            HelloAgent
          </div>
          <div className="text-[11px] text-subtle select-none">
            智能体工作台
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex-1 min-h-0 flex flex-col pt-2">
        <Tabs
          activeKey={activeKey}
          onChange={handleTabChange}
          items={items}
          size="small"
        />
      </div>

      {/* 底部:主题切换 */}
      <div className="h-12 flex items-center justify-between border-t border-hairline px-1">
        <span className="text-[11px] text-subtle select-none">
          {theme === "dark" ? "深色模式" : "浅色模式"}
        </span>
        <Tooltip title={theme === "dark" ? "切换浅色" : "切换深色"}>
          <Button
            type="text"
            size="small"
            shape="circle"
            icon={theme === "dark" ? <SunOutlined /> : <MoonOutlined />}
            onClick={toggleTheme}
          />
        </Tooltip>
      </div>

      <AddAgentModal
        open={isAddAgentModalOpen}
        onClose={toggleAddAgentModal}
        createAgentHandle={createAgentHandle}
        updateAgentHandle={updateAgentHandle}
        editingAgent={editingAgent}
      />
      <AddKnowledgeBaseModal
        open={isAddKnowledgeBaseModalOpen}
        onClose={toggleAddKnowledgeBaseModal}
        createKnowledgeBaseHandle={createKnowledgeBaseHandle}
      />
    </div>
  );
};

export default SideMenu;
