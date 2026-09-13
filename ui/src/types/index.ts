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

export type AgentCheckpointStatus =
  | "WAITING_APPROVAL"
  | "APPROVED"
  | "EXECUTING"
  | "REJECTED"
  | "REJECTION_EXECUTING"
  | "REJECTION_COMPLETED"
  | "TOOL_COMPLETED"
  | "RESUMING"
  | "COMPLETED"
  | "FAILED"
  | "UNKNOWN";

export interface AgentCheckpoint {
  id: string;
  sessionId: string;
  status: AgentCheckpointStatus;
  toolNames: string[];
  argumentPreview: string;
  createdAt: string;
  errorMessage?: string;
}

export type SseMessageType =
  | "AI_GENERATED_CONTENT"
  | "AI_STREAM_CHUNK"
  | "AI_EVAL_SCORE"
  | "AI_APPROVAL_REQUIRED"
  | "AI_APPROVAL_RESOLVED"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_DONE";

export interface SseMessagePayload {
  message: ChatMessageVO;
  statusText: string;
  done: boolean;
  checkpoint?: AgentCheckpoint;
}

export interface SseMessageMetadata {
  chatMessageId: string;
  checkpointId?: string;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  metadata: SseMessageMetadata;
}
