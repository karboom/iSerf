package me.karboom.java.iSlogger.agent;

import com.openai.models.chat.completions.ChatCompletionChunk;
import me.karboom.java.iSlogger.Tool;
import me.karboom.java.iSlogger.llm.text.BaseLLM;
import me.karboom.java.iSlogger.memory.Item;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 基类
 */
public abstract class Agent {
    protected String id;
    protected List<Item> memory;
    protected List<Tool> tools;
    protected BaseLLM llm;

    /**
     * 构造函数
     *
     * @param id    Agent ID
     * @param llm   LLM 实例
     * @param tools 工具列表
     */
    public Agent(String id, BaseLLM llm, List<Tool> tools) {
        this.id = id;
        this.llm = llm;
        this.tools = tools != null ? tools : new ArrayList<>();
        this.memory = new ArrayList<>();
    }

    /**
     * 与 Agent 对话
     *
     * @param message 用户消息
     * @return 流式响应
     */
    public Flux<ChatCompletionChunk> talk(String message) {
        // 创建用户消息
        var userMessage = Item.builder()
                .role("user")
                .text(message)
                .build();

        // 添加到记忆中
        memory.add(userMessage);

        return llm.send(memory, null, tools)
                .switchOnFirst((first, other) -> {
                    var toolCalls = first.get().choices().get(0).delta().toolCalls();

                    if (toolCalls.isPresent()) {
                        return other.collectList().flatMapMany((list)-> {
                            // Todo 合并ToolCall参数

                            // Todo 通过cli调用MCP函数

                            // Todo 解析函数返回的json，如果$.type = direct那么直接更新到memory，否则继续调用llm.send

                            return llm.send(memory, null, tools);
                        });

                    } else {
                        return other.skip(1);
                    }
                });
    }

    /**
     * 加载记忆
     *
     * @param messages 记忆消息列表
     */
    public void loadMemory(List<Item> messages) {
        if (messages != null) {
            this.memory = new ArrayList<>(messages);
        } else {
            this.memory = new ArrayList<>();
        }
    }

    /**
     * 导出记忆
     *
     * @return 记忆列表的副本
     */
    public List<Item> dumpMemory() {
        return new ArrayList<>(this.memory);
    }

    /**
     * 清空记忆
     */
    public void clearMemory() {
        this.memory.clear();
    }


}
