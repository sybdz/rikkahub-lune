import type { MessageRole, TokenUsage } from "./core";
import type { UIMessageAnnotation } from "./annotations";
import type { UIMessagePart } from "./parts";

export interface CompressionCheckpointSyntheticKind {
  type: "compression_checkpoint";
  level: number;
  sourceMessageCount: number;
}

export type UISyntheticKind = CompressionCheckpointSyntheticKind;

/**
 * UI Message
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessage
 */
export interface UIMessage {
  id: string;
  role: MessageRole;
  parts: UIMessagePart[];
  syntheticKind?: UISyntheticKind | null;
  annotations: UIMessageAnnotation[];
  createdAt: string;
  finishedAt?: string | null;
  modelId?: string | null;
  usage?: TokenUsage | null;
  translation?: string | null;
}
