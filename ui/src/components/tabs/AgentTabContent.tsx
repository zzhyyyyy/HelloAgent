import React, { useMemo } from "react";
import { Button, Dropdown, Modal } from "antd";
import type { MenuProps } from "antd";
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  MoreOutlined,
} from "@ant-design/icons";
import type { AgentVO } from "../../api/api.ts";
import { formatDateTime, getAgentEmoji } from "../../utils";

interface AgentTabContentProps {
  agents: AgentVO[];
  onCreateAgentClick: () => void;
  onSelectAgent: (agentId: string) => void;
  onEditAgent?: (agent: AgentVO) => void;
  onDeleteAgent?: (agentId: string) => void;
}

const AgentTabContent: React.FC<AgentTabContentProps> = ({
  agents,
  onCreateAgentClick,
  onSelectAgent,
  onEditAgent,
  onDeleteAgent,
}) => {
  const agentsWithEmoji = useMemo(
    () =>
      agents.map((agent) => ({
        ...agent,
        emoji: getAgentEmoji(agent.id),
      })),
    [agents],
  );

  const getContextMenuItems = (agent: AgentVO): MenuProps["items"] => {
    const items: MenuProps["items"] = [];
    if (onEditAgent) {
      items.push({
        key: "edit",
        label: "编辑",
        icon: <EditOutlined />,
        onClick: (e) => {
          e.domEvent.stopPropagation();
          onEditAgent(agent);
        },
      });
    }
    if (onDeleteAgent) {
      items.push({
        key: "delete",
        label: "删除",
        icon: <DeleteOutlined />,
        danger: true,
        onClick: (e) => {
          e.domEvent.stopPropagation();
          Modal.confirm({
            title: "确定要删除这个智能体吗?",
            content: "删除后将无法恢复",
            okText: "确定",
            cancelText: "取消",
            okType: "danger",
            onOk: () => onDeleteAgent(agent.id),
          });
        },
      });
    }
    return items;
  };

  return (
    <div className="flex flex-col h-full pt-1">
      <Button
        type="primary"
        ghost
        icon={<PlusOutlined />}
        onClick={onCreateAgentClick}
        className="w-full mb-3"
      >
        新建智能体
      </Button>
      <div className="flex-1 overflow-y-auto -mx-1 px-1">
        {agents.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-subtle py-12">
            <div className="text-sm">暂无智能体</div>
            <div className="text-xs mt-1">点击上方按钮添加</div>
          </div>
        ) : (
          <div className="space-y-1">
            {agentsWithEmoji.map((agent) => {
              const menuItems = getContextMenuItems(agent);
              const hasMenu = menuItems && menuItems.length > 0;
              return (
                <div
                  key={agent.id}
                  onClick={() => onSelectAgent(agent.id)}
                  className="group relative px-2.5 py-2.5 rounded-xl cursor-pointer transition-all hover:surface-muted"
                >
                  <div className="flex items-start gap-2.5">
                    <div className="w-9 h-9 rounded-lg surface-muted border border-hairline flex items-center justify-center shrink-0 text-base">
                      {agent.emoji}
                    </div>
                    <div className="flex-1 min-w-0 pr-6">
                      <div className="text-[13px] font-medium text-strong truncate leading-snug">
                        {agent.name}
                      </div>
                      {agent.description && (
                        <div className="text-[11px] text-muted mt-0.5 line-clamp-1">
                          {agent.description}
                        </div>
                      )}
                      {agent.updatedAt && (
                        <div className="text-[10px] text-subtle mt-1">
                          {formatDateTime(agent.updatedAt)}
                        </div>
                      )}
                    </div>
                    {hasMenu && (
                      <div
                        onClick={(e) => e.stopPropagation()}
                        onContextMenu={(e) => e.stopPropagation()}
                        className="absolute top-1.5 right-1.5 opacity-0 group-hover:opacity-100 transition-opacity"
                      >
                        <Dropdown
                          menu={{ items: menuItems }}
                          trigger={["contextMenu", "click"]}
                          placement="bottomRight"
                        >
                          <Button
                            type="text"
                            size="small"
                            shape="circle"
                            icon={<MoreOutlined />}
                            onClick={(e) => e.stopPropagation()}
                          />
                        </Dropdown>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export default AgentTabContent;
