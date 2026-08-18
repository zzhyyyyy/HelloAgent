import React from "react";

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  return <div className="h-screen flex surface-app">{children}</div>;
};

export default Layout;
