package me.karboom.java.iSerf.agent;

/**
 * LLM 流式响应的生命周期观察者
 * 将 emit、记忆存储等副作用从 fluxHandle 中解耦，由外部动态注入
 */
public interface StreamObserver {

    /**
     * 流开始
     */
    default void onStart() {}

    /**
     * thinking 阶段结束，输出完整累积文本
     *
     * @param message 完整的 thinking 消息
     */
    default void onThinking(AgentMessage message) {}

    /**
     * 工具调用阶段结束
     *
     * @param toolCallChunks 工具调用的原始 chunk 列表
     */
    default void onToolCalls(java.util.List<me.karboom.java.iSerf.llm.text.Output> toolCallChunks) {}

    /**
     * 每个 content chunk 到达（用于实时流式推送）
     *
     * @param delta 本次 chunk 的文本增量
     */
    default void onContentChunk(String delta) {}

    /**
     * content 阶段结束（收到 usage 时触发），输出完整累积消息
     *
     * @param message 完整的 content 消息（含 usage、formatted 等）
     */
    default void onContent(AgentMessage message) {}

    /**
     * 完全静默的观察者，不做任何副作用（用于 invoke 等并发场景）
     */
    StreamObserver SILENT = new StreamObserver() {};
}
