package me.karboom.java.iSerf.agent.memory;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent 记忆管理器
 * 负责记忆的增删查、持久化加载和记忆压缩
 */
@Data
@Slf4j
public class MemoryManager {

    private String systemPrompt;
    private final List<AgentMessage> messages = new ArrayList<>();

    /**
     * @param systemPrompt  系统提示词
     */
    public MemoryManager(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    // region ========== 基础操作 ==========

    public void add(AgentMessage message) {
        messages.add(message);
    }

    public void addAll(List<AgentMessage> list) {
        messages.addAll(list);
    }

    public void removeByEventId(String eventId) {
        messages.removeIf(msg -> eventId.equals(msg.getEventId()));
    }

    /**
     * 删除指定 eventId 及之后的所有消息
     *
     * @param eventId 起始事件的 ID
     */
    public void removeFromEventId(String eventId) {
        var index = messages.stream()
                .filter(msg -> eventId.equals(msg.getEventId()))
                .findFirst()
                .map(messages::indexOf)
                .orElse(-1);

        if (index >= 0) {
            messages.subList(index, messages.size()).clear();
        }
    }

    public int size() {
        return messages.size();
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    // endregion

    // region ========== 读取 ==========

    /**
     * 获取全部对话消息（不含 systemPrompt），返回实际列表引用（用于计费等内部场景）
     */
    public List<AgentMessage> getMessagesRaw() {
        return messages;
    }

    /**
     * 获取全部对话消息的防御性副本（不含 systemPrompt）
     */
    public List<AgentMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * 获取未被遗忘的消息（不含 systemPrompt）
     */
    public List<AgentMessage> getActiveMessages() {
        return messages.stream()
                .filter(item -> item.getIsForgotten() == null || item.getIsForgotten() == 0)
                .filter(item -> !AgentMessage.TYPE.THINKING.equals(item.getType()))
                .filter(item -> !AgentMessage.TYPE.CUSTOM.equals(item.getType()))
                .toList();
    }

    /**
     * 获取用于发送给 LLM 的消息列表
     * 包含 systemPrompt（作为 SYSTEM 消息）和所有未遗忘的消息
     */
    public List<AgentMessage> getMessagesForLLM() {
        var result = new ArrayList<AgentMessage>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            result.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.SYSTEM)
                    .type(AgentMessage.TYPE.TEXT)
                    .text(systemPrompt)
                    .isForgotten(0)
                    .build());
        }
        result.addAll(getActiveMessages());
        return result;
    }

    // endregion

    // region ========== 记忆管理 ==========

    /**
     * 整理记忆
     * 1. 调用 llm.query 将当前记忆压缩为摘要
     * 2. 摘要追加到记忆，原参与压缩的记忆标记为遗忘
     * @param agent Agent 实例，用于获取 LLM 提供者
     */
    public void organizeMemory(Agent agent) {
        if (messages.isEmpty()) {
            return;
        }

        var summaryPrompt = AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .text("请将以上对话历史压缩为简洁的摘要，保留关键信息和上下文，用于后续对话参考。")
                .build();

        var list = new ArrayList<>(messages);
        list.add(summaryPrompt);

        var result = agent.getLlmProvider().get(null, null, null).query(list, null);

        if (result != null && result.getChoices() != null && !result.getChoices().isEmpty()) {
            var summaryText = result.getChoices().get(0).getText();
            if (summaryText != null && !summaryText.isEmpty()) {
                var summaryItem = AgentMessage.builder()
                        .id(UUID.randomUUID().toString())
                        .role(AgentMessage.ROLE.ASSISTANT)
                        .type(AgentMessage.TYPE.TEXT)
                        .text(summaryText)
                        .isForgotten(0)
                        .build();

                messages.add(summaryItem);

                messages.forEach(item -> {
                    if (item.getIsForgotten() == null || item.getIsForgotten() == 0) {
                        item.setIsForgotten(1);
                    }
                });

                // 摘要自身不应被遗忘
                summaryItem.setIsForgotten(0);

                log.debug("organizeMemory: Memory compressed successfully");
            }
        }
    }

    /**
     * 检查记忆是否超出阈值（prompt token > 150k），超出则直接压缩
     * @param agent Agent 实例，用于获取 LLM 提供者
     */
    public void checkAndOrganize(Agent agent) {
        var promptTotal = messages.stream()
                .filter(item -> item.getUsage() != null && item.getUsage().getPromptTotal() != null)
                .mapToInt(item -> item.getUsage().getPromptTotal())
                .sum();

        if (promptTotal > 150000) {
            organizeMemory(agent);
        }
    }

    // endregion
}
