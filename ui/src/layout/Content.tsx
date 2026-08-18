import React from "react";

interface ContentProps {
  children: React.ReactNode;
}

const Content: React.FC<ContentProps> = ({ children }) => {
  return (
    <main className="h-full flex-1 min-w-0 surface-app overflow-hidden">
      {children}
    </main>
  );
};

export default Content;
