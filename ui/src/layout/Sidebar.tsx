import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <aside
      className="h-full surface-sidebar border-r border-app shadow-soft shrink-0"
      style={{ width: 280 }}
    >
      {children}
    </aside>
  );
};

export default Sidebar;
