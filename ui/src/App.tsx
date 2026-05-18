import { BrowserRouter } from "react-router-dom";
import { ConfigProvider, theme as antTheme } from "antd";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";
import { ThemeProvider, useTheme } from "./contexts/ThemeContext.tsx";

function ThemedApp() {
  const { theme } = useTheme();

  return (
    <ConfigProvider
      theme={{
        algorithm:
          theme === "dark" ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
      }}
    >
      <ChatSessionsProvider>
        <JChatMindLayout />
      </ChatSessionsProvider>
    </ConfigProvider>
  );
}

function App() {
  return (
    <BrowserRouter>
      <ThemeProvider>
        <ThemedApp />
      </ThemeProvider>
    </BrowserRouter>
  );
}

export default App;
