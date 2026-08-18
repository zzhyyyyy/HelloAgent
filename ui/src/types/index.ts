export type MessageType = "user" | "assistant" | "system" | "tool";

export interface KnowledgeBase {
  knowledgeBaseId: string;
  name: string;
  description: string;
}

export interface ToolCall {
  id: string;
  type: string;
  name: string;
  arguments: string;
}

export interface ToolResponse {
  id: string;
  name: string;
  responseData: string;
}

export interface ChatMessageUsage {
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  model?: string;
  finishReason?: string;
}

export interface ChatMessageEvalScore {
  correctness?: number;
  completeness?: number;
  relevance?: number;
  reasoning?: string;
  judgeModel?: string;
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
  agentRole?: string;
  usage?: ChatMessageUsage;
  evalScore?: ChatMessageEvalScore;
}

export interface ChatMessageVO {
  id: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: ChatMessageVOMetadata;
}

export type SseMessageType =
  | "AI_GENERATED_CONTENT"
  | "AI_STREAM_CHUNK"
  | "AI_EVAL_SCORE"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_DONE";

export interface SseMessagePayload {
  message: ChatMessageVO;
  statusText: string;
  done: boolean;
}

export interface SseMessageMetadata {
  chatMessageId: string;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  metadata: SseMessageMetadata;
}
