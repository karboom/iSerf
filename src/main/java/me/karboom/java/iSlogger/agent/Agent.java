package me.karboom.java.iSlogger.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.chat.completions.ChatCompletionChunk;
import me.karboom.java.iSlogger.llm.text.BaseLLM;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.memory.LocalMemory;
import me.karboom.java.iSlogger.memory.Memory;
import me.karboom.java.iSlogger.tool.Tool;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Agent 基类
 */
public abstract class Agent {
    protected String id;
    protected Memory memory = new LocalMemory();
    protected List<Tool> tools;
    protected BaseLLM llm;
    protected String prompt;

    /**
     * 构造函数
     *
     * @param id    Agent ID
     * @param llm   LLM 实例
     * @param tools 工具列表
     */
    public Agent(String id, String prompt, BaseLLM llm, List<Tool> tools) {
        this.id = id;
        this.prompt = prompt;
        this.llm = llm;
        this.tools = tools != null ? tools : new ArrayList<>();
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

        return llm.send(memory.get(), null, tools)
                .switchOnFirst((first, other) -> {
                    var toolCalls = first.get().choices().get(0).delta().toolCalls();

                    if (toolCalls.isPresent()) {
                        return other.collectList().flatMapMany((list)-> {
                            // 合并ToolCall参数
                            var calls = mergeToolCalls(list);

                            // 通过cli调用MCP函数
                            var callResult = invokeToolCalls(calls);

                            // 判断是否需要直接更新记忆
                            var allDirect = true;
                            for (Item.ToolCall call : callResult) {
                                if (!"direct".equals(call.result.get("type").asText())) {
                                    allDirect = false;
                                }
                            }

                            if (allDirect) {
                                // 构建合并后的响应文本
                                var responseBuilder = new StringBuilder();
                                for (Item.ToolCall call : callResult) {
                                    responseBuilder.append(call.result.get("content").asText());
                                    responseBuilder.append("\n");
                                }
                                var responseText = responseBuilder.toString().trim();

                                // 构造完整的 ChatCompletionChunk
                                var fake = ChatCompletionChunk.builder()
                                        .id("chatcmpl-fake-" + System.currentTimeMillis())
//                                        .object("chat.completion.chunk")
                                        .created(System.currentTimeMillis() / 1000)
                                        .model(llm.llmType)
                                        .addChoice(
                                            ChatCompletionChunk.Choice.builder()
                                                .index(0)
                                                .delta(
                                                    ChatCompletionChunk.Choice.Delta.builder()
                                                        .role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT)
                                                        .content(responseText)
                                                        .build()
                                                )
                                                .finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
                                                .build()
                                        )
                                        .build();

                                // 直接返回结果
                                return Flux.just(fake);
                            } else {

                                return llm.send(memory.get(), null, tools);
                            }

                        });

                    } else {
                        return other;
                    }
                });
    }

    /**
     * 合并函数调用chunk
     * @param chunks 流式响应块列表
     * @return 合并后的工具调用列表
     */
    private List<Item.ToolCall> mergeToolCalls(List<ChatCompletionChunk> chunks) {
        // 使用 Map 存储每个 index 对应的 ToolCall
        var toolCallsMap = new HashMap<Integer, Item.ToolCall>();
        // 使用 StringBuilder 累积 arguments JSON 字符串
        var argumentsMap = new HashMap<Integer, StringBuilder>();

        for (var chunk : chunks) {
            if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                var delta = chunk.choices().get(0).delta();
                if (delta.toolCalls().isPresent()) {
                    for (var toolCall : delta.toolCalls().get()) {
                        int index = (int) toolCall.index();

                        // 初始化 ToolCall 对象（如果不存在）
                        if (!toolCallsMap.containsKey(index)) {
                            toolCallsMap.put(index, Item.ToolCall.builder()
                                .arguments(new HashMap<>())
                                .build());
                        }

                        var currentToolCall = toolCallsMap.get(index);

                        // 设置 id
                        if (toolCall.id().isPresent()) {
                            currentToolCall.id = toolCall.id().get();
                        }

                        // 设置 function 信息
                        if (toolCall.function().isPresent()) {
                            var function = toolCall.function().get();
                            if (function.name().isPresent()) {
                                currentToolCall.name = function.name().get();
                            }
                            if (function.arguments().isPresent()) {
                                // 累积 arguments 字符串
                                if (!argumentsMap.containsKey(index)) {
                                    argumentsMap.put(index, new StringBuilder());
                                }
                                argumentsMap.get(index).append(function.arguments().get());
                            }
                        }
                    }
                }
            }
        }

        // 解析累积的 arguments JSON 字符串为 HashMap
        var objectMapper = new ObjectMapper();
        for (var entry : argumentsMap.entrySet()) {
            var index = entry.getKey();
            var argsJson = entry.getValue().toString();
            try {
                @SuppressWarnings("unchecked")
                var argsMap = objectMapper.readValue(argsJson, HashMap.class);
                toolCallsMap.get(index).arguments = argsMap;
            } catch (Exception e) {
                // 如果解析失败，保持空的 HashMap
                System.err.println("Failed to parse tool call arguments: " + e.getMessage());
            }
        }

        return new ArrayList<>(toolCallsMap.values());
    }

    /**
     * 调用函数
     * @param calls 工具调用列表
     * @return 带有调用结果的工具调用列表
     */
    private List<Item.ToolCall> invokeToolCalls(List<Item.ToolCall> calls) {
        var objectMapper = new ObjectMapper();
        
        // 为每个工具调用创建假的结果
        for (var call : calls) {
            // 创建假的结果 ObjectNode
            ObjectNode result = objectMapper.createObjectNode();
            result.put("type", "direct");
            result.put("content", "This is a mock result for " + call.name);
            
            // 设置结果
            call.result = result;
        }
        
        return calls;
    }


}
