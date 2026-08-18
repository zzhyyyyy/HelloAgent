import React from "react";

interface State {
  hasError: boolean;
  error: Error | null;
}

interface Props {
  children: React.ReactNode;
  fallback?: (error: Error, reset: () => void) => React.ReactNode;
}

/**
 * 局部 ErrorBoundary。
 * 单条消息渲染或 XMarkdown 解析炸了的话,不会拖垮整个 App 进入白屏。
 */
export default class ErrorBoundary extends React.Component<Props, State> {
  state: State = { hasError: false, error: null };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error("[ErrorBoundary] caught:", error, info);
  }

  reset = () => this.setState({ hasError: false, error: null });

  render() {
    if (this.state.hasError && this.state.error) {
      if (this.props.fallback) {
        return this.props.fallback(this.state.error, this.reset);
      }
      return (
        <div className="p-6 text-center">
          <div className="text-sm text-muted mb-2">
            页面出现了一个渲染错误,已隔离避免整页崩溃。
          </div>
          <div className="text-xs text-subtle font-mono mb-4">
            {this.state.error.message}
          </div>
          <button
            onClick={this.reset}
            className="text-xs brand-text hover:underline"
          >
            重试
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
