package me.karboom.java.iSerf.llm.text;

import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.util.ErrorUtil;
import me.karboom.java.iSerf.util.HttpUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude Messages API 实现
 * 使用 Anthropic 原生 /v1/messages 接口和 SSE 格式
 */
@Slf4j
public class Anthropic implements IText {
    public String llmType;
    public Map<String, Object> llmConfig;
    protected String apiKey;
    protected String url;
    protected Integer maxRetries;
    private final SchemaGenerator schemaGenerator;

    public Anthropic(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        this.llmType = llmType;
        this.llmConfig = llmConfig;
        this.apiKey = apiKey;
        this.url = url;
        this.maxRetries = maxRetries;

        var configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON).with(new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED));
        configBuilder.forFields().withRequiredCheck(fieldScope -> true);
        this.schemaGenerator = new SchemaGenerator(configBuilder.build());
    }

    /**
     * 使用 Anthropic Messages API 进行流式对话
     */
    @Override
    public Flux<Output> send(List<AgentMessage> memory, Class<?> outputFormat, List<Tool<?>> tools) {
        return Flux.create(sink -> {
            var requestBody = buildRequestBody(memory, outputFormat, tools, true);

            var request = new Request.Builder()
                    .url("%s/messages".formatted(this.url))
                    .addHeader("x-api-key", this.apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            var listener = new EventSourceListener() {
                private String currentBlockType;
                private String textBuffer;
                private String toolId;
                private String toolName;
                private String toolArgsBuffer;
                private String thinkingBuffer;
                private String messageId;
                private Output.Usage usage;
                private String stopReason;

                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    log.debug(" send connection open ");
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    log.debug(" send event type={} data={} ", type, data);
                    if (data == null || data.trim().isEmpty()) {
                        return;
                    }

                    var json = JSONUtil.parse(data);

                    switch (type) {
                        case "message_start":
                            var messageNode = json.path("message");
                            messageId = messageNode.path("id").asText();
                            var usageNode = messageNode.path("usage");
                            if (!usageNode.isMissingNode()) {
                                usage = Output.Usage.builder()
                                        .promptTokens(usageNode.path("input_tokens").asInt())
                                        .completionTokens(usageNode.path("output_tokens").asInt())
                                        .totalTokens(usageNode.path("input_tokens").asInt() + usageNode.path("output_tokens").asInt())
                                        .build();
                            }
                            break;

                        case "content_block_start":
                            var blockNode = json.path("content_block");
                            currentBlockType = blockNode.path("type").asText();
                            switch (currentBlockType) {
                                case "text":
                                    textBuffer = blockNode.path("text").asText("");
                                    break;
                                case "tool_use":
                                    toolId = blockNode.path("id").asText();
                                    toolName = blockNode.path("name").asText();
                                    toolArgsBuffer = "";
                                    break;
                                case "thinking":
                                    thinkingBuffer = blockNode.path("thinking").asText("");
                                    break;
                            }
                            break;

                        case "content_block_delta":
                            var deltaNode = json.path("delta");
                            var deltaType = deltaNode.path("type").asText();
                            switch (deltaType) {
                                case "text_delta":
                                    textBuffer += deltaNode.path("text").asText("");
                                    break;
                                case "input_json_delta":
                                    toolArgsBuffer += deltaNode.path("partial_json").asText("");
                                    break;
                                case "thinking_delta":
                                    thinkingBuffer += deltaNode.path("thinking").asText("");
                                    break;
                                case "signature_delta":
                                    break;
                            }
                            break;

                        case "content_block_stop":
                            var output = new Output();
                            output.setId(messageId);
                            output.type = "message";
                            output.isDelta = true;

                            if ("text".equals(currentBlockType) && textBuffer != null && !textBuffer.isEmpty()) {
                                var choice = new Output.Choice();
                                choice.setText(textBuffer);
                                output.setChoices(List.of(choice));
                            }

                            if ("tool_use".equals(currentBlockType) && toolId != null) {
                                var choice = new Output.Choice();
                                var toolCall = Output.ToolCall.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .arguments(toolArgsBuffer)
                                        .build();
                                choice.setToolCall(List.of(toolCall));
                                output.setChoices(List.of(choice));
                            }

                            if ("thinking".equals(currentBlockType) && thinkingBuffer != null && !thinkingBuffer.isEmpty()) {
                                var choice = new Output.Choice();
                                choice.setThinking(thinkingBuffer);
                                output.setChoices(List.of(choice));
                            }

                            sink.next(output);
                            currentBlockType = null;
                            break;

                        case "message_delta":
                            var delta = json.path("delta");
                            stopReason = delta.path("stop_reason").asText();
                            var deltaUsage = json.path("usage");
                            if (!deltaUsage.isMissingNode()) {
                                usage = Output.Usage.builder()
                                        .promptTokens(usage != null ? usage.getPromptTokens() : 0)
                                        .completionTokens(deltaUsage.path("output_tokens").asInt())
                                        .totalTokens(
                                                (usage != null ? usage.getPromptTokens() : 0) + deltaUsage.path("output_tokens").asInt()
                                        )
                                        .build();
                            }
                            break;

                        case "message_stop":
                            var finalOutput = new Output();
                            finalOutput.setId(messageId);
                            finalOutput.type = "message";
                            finalOutput.isDelta = false;

                            var finalChoice = new Output.Choice();
                            finalChoice.setFinishReason(stopReason);
                            finalOutput.setChoices(List.of(finalChoice));

                            if (usage != null) {
                                finalOutput.setUsage(usage);
                            }

                            sink.next(finalOutput);
                            sink.complete();
                            break;

                        case "error":
                            var errorMsg = json.path("error").path("message").asText("unknown error");
                            sink.error(new RuntimeException(errorMsg));
                            break;

                        case "ping":
                            break;
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    log.debug(" send connection closed ");
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    log.error(" send connection failure ", t);
                    sink.error(t);
                }
            };

            var factory = EventSources.createFactory(HttpUtil.getClient());
            var eventSource = factory.newEventSource(request, listener);

            sink.onDispose(eventSource::cancel);
        });
    }

    /**
     * 使用 Anthropic Messages API 进行非流式查询
     */
    @Override
    public Output query(List<AgentMessage> messages, Class<?> outputFormat) {
        var requestBody = buildRequestBody(messages, outputFormat, null, false);

        var request = new Request.Builder()
                .url("%s/messages".formatted(this.url))
                .addHeader("x-api-key", this.apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

        try (var response = HttpUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                var body = response.body();
                var bodyStr = body != null ? body.string() : "null";
                log.error(" query failed code={} body={} ", response.code(), bodyStr);
                throw ErrorUtil.make("Query failed: %s".formatted(response.code()));
            }
            var responseBody = response.body();
            if (responseBody == null) {
                throw ErrorUtil.make("Query response is null");
            }
            var responseJson = JSONUtil.parse(responseBody.string());
            return parseOutput(responseJson, false);
        } catch (Exception e) {
            throw ErrorUtil.make("Query error: %s".formatted(e.getMessage()));
        }
    }

    /**
     * 批量查询（Anthropic 不支持 Batch API）
     */
    @Override
    public String batch(List<List<AgentMessage>> messageBatch, Class<?> outputFormat) {
        throw ErrorUtil.make("Anthropic does not support batch API");
    }

    /**
     * 查看批量任务状态（Anthropic 不支持 Batch API）
     */
    @Override
    public BatchTaskInfo taskStatus(String taskId) {
        throw ErrorUtil.make("Anthropic does not support batch API");
    }

    /**
     * 查看批量任务结果（Anthropic 不支持 Batch API）
     */
    @Override
    public List<Output> taskResult(BatchTaskInfo task) {
        throw ErrorUtil.make("Anthropic does not support batch API");
    }

    /**
     * 构建请求体
     */
    private String buildRequestBody(List<AgentMessage> memory, Class<?> outputFormat, List<Tool<?>> tools, boolean stream) {
        var body = JSONUtil.create();
        body.put("model", llmType);
        body.put("stream", stream);

        var maxTokens = 1024;
        if (llmConfig.containsKey("max_tokens")) {
            maxTokens = ((Number) llmConfig.get("max_tokens")).intValue();
        }
        body.put("max_tokens", maxTokens);

        var systemPrompt = extractSystemPrompt(memory);
        if (systemPrompt != null) {
            body.put("system", systemPrompt);
        }

        var mergedTools = mergeOutputFormatTools(tools, outputFormat);
        if (mergedTools != null && !mergedTools.isEmpty()) {
            body.set("tools", buildToolsJson(mergedTools));

            if (tools == null || tools.isEmpty()) {
                if (outputFormat != null) {
                    var toolChoice = JSONUtil.create();
                    toolChoice.put("type", "tool");
                    toolChoice.put("name", "output_format");
                    body.set("tool_choice", toolChoice);
                }
            }
        }

        if (llmConfig.containsKey("temperature")) {
            body.put("temperature", ((Number) llmConfig.get("temperature")).doubleValue());
        }

        if (llmConfig.containsKey("top_p")) {
            body.put("top_p", ((Number) llmConfig.get("top_p")).doubleValue());
        }

        if (llmConfig.containsKey("thinking")) {
            var thinking = JSONUtil.create();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", 16000);
            body.set("thinking", thinking);
        }

        body.set("messages", buildMessagesArray(memory));

        return body.toString();
    }

    /**
     * 从 memory 列表中提取 system 角色的消息
     */
    private String extractSystemPrompt(List<AgentMessage> memory) {
        for (var item : memory) {
            if (AgentMessage.ROLE.SYSTEM.equals(item.role)) {
                return item.text;
            }
        }
        return null;
    }

    /**
     * 合并 outputFormat 生成的工具和传入的 tools
     */
    private List<Tool<?>> mergeOutputFormatTools(List<Tool<?>> tools, Class<?> outputFormat) {
        if (outputFormat == null) {
            return tools;
        }

        var outputFormatTool = Tool.builder()
                .name("output_format")
                .description("输出格式工具，请使用此工具返回结构化的输出")
                .paramType((Class<Object>) (Class<?>) outputFormat)
                .build();

        if (tools == null || tools.isEmpty()) {
            return List.of(outputFormatTool);
        }

        var merged = new ArrayList<Tool<?>>();
        merged.add(outputFormatTool);
        merged.addAll(tools);
        return merged;
    }

    /**
     * 构建消息数组（Anthropic 格式，不含 system 角色）
     */
    private ArrayNode buildMessagesArray(List<AgentMessage> memory) {
        var messagesArray = JSONUtil.createArray();

        for (var item : memory) {
            switch (item.role) {
                case AgentMessage.ROLE.USER:
                    var userMessage = JSONUtil.create();
                    userMessage.put("role", "user");
                    userMessage.set("content", buildContentArray(item));
                    messagesArray.add(userMessage);
                    break;

                case AgentMessage.ROLE.ASSISTANT:
                    var assistantMessage = JSONUtil.create();
                    assistantMessage.put("role", "assistant");

                    var assistantContent = JSONUtil.createArray();
                    if (item.toolCalls != null && !item.toolCalls.isEmpty()) {
                        for (var toolCall : item.toolCalls) {
                            var toolUseBlock = JSONUtil.create();
                            toolUseBlock.put("type", "tool_use");
                            toolUseBlock.put("id", toolCall.getId());
                            toolUseBlock.put("name", toolCall.getName());
                            toolUseBlock.set("input", JSONUtil.parse(toolCall.getResult().getLlm()));
                            assistantContent.add(toolUseBlock);
                        }
                    } else if (item.text != null) {
                        var textBlock = JSONUtil.create();
                        textBlock.put("type", "text");
                        textBlock.put("text", item.text);
                        assistantContent.add(textBlock);
                    }

                    assistantMessage.set("content", assistantContent);
                    messagesArray.add(assistantMessage);
                    break;

                case AgentMessage.ROLE.TOOL:
                    if (item.toolCalls != null) {
                        for (var toolCall : item.toolCalls) {
                            var toolMessage = JSONUtil.create();
                            toolMessage.put("role", "user");

                            var toolContent = JSONUtil.createArray();
                            var toolResultBlock = JSONUtil.create();
                            toolResultBlock.put("type", "tool_result");
                            toolResultBlock.put("tool_use_id", toolCall.getId());
                            toolResultBlock.put("content", toolCall.getResult().getLlm());
                            toolContent.add(toolResultBlock);

                            toolMessage.set("content", toolContent);
                            messagesArray.add(toolMessage);
                        }
                    }
                    break;

                case AgentMessage.ROLE.SYSTEM:
                    break;
            }
        }

        return messagesArray;
    }

    /**
     * 构建内容数组（Anthropic 格式）
     */
    private ArrayNode buildContentArray(AgentMessage item) {
        var content = JSONUtil.createArray();

        switch (item.type) {
            case AgentMessage.TYPE.TEXT:
                var textBlock = JSONUtil.create();
                textBlock.put("type", "text");
                textBlock.put("text", item.text);
                content.add(textBlock);
                break;

            case AgentMessage.TYPE.IMAGE:
                if (item.files != null) {
                    for (var image : item.files) {
                        if (image.startsWith("http")) {
                            var imgBlock = JSONUtil.create();
                            imgBlock.put("type", "image");
                            var source = JSONUtil.create();
                            source.put("type", "url");
                            source.put("url", image);
                            imgBlock.set("source", source);
                            content.add(imgBlock);
                        } else {
                            var imgBlock = JSONUtil.create();
                            imgBlock.put("type", "image");
                            var source = JSONUtil.create();
                            source.put("type", "base64");
                            source.put("media_type", "image/jpeg");
                            source.put("data", image);
                            imgBlock.set("source", source);
                            content.add(imgBlock);
                        }
                    }
                }
                if (item.getText() != null) {
                    var captionBlock = JSONUtil.create();
                    captionBlock.put("type", "text");
                    captionBlock.put("text", item.getText());
                    content.add(captionBlock);
                }
                break;

            default:
                var defaultBlock = JSONUtil.create();
                defaultBlock.put("type", "text");
                defaultBlock.put("text", item.text);
                content.add(defaultBlock);
                break;
        }

        return content;
    }

    /**
     * 构建工具 JSON（Anthropic 格式）
     */
    public ArrayNode buildToolsJson(List<Tool<?>> tools) {
        var toolsArray = JSONUtil.createArray();
        for (var tool : tools) {
            var toolNode = JSONUtil.create();
            toolNode.put("name", tool.name);
            toolNode.put("description", tool.description);
            toolNode.set("input_schema", JSONUtil.parse(schemaGenerator.generateSchema(tool.paramType).toString()));
            toolsArray.add(toolNode);
        }
        return toolsArray;
    }

    /**
     * 解析非流式响应
     */
    private Output parseOutput(ObjectNode data, boolean isStream) {
        var output = new Output();
        output.isDelta = isStream;

        var idNode = data.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            output.setId(idNode.asString());
        }

        output.type = data.path("type").asText("message");

        var errorNode = data.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            output.setError(errorNode.path("message").asText());
            return output;
        }

        var contentNode = data.path("content");
        if (!contentNode.isMissingNode() && contentNode.isArray()) {
            var choice = new Output.Choice();
            var textContent = new StringBuilder();
            var thinkingContent = new StringBuilder();
            var toolCalls = new ArrayList<Output.ToolCall>();

            for (var block : contentNode) {
                var blockType = block.path("type").asText();
                switch (blockType) {
                    case "text":
                        var textNode = block.path("text");
                        if (!textNode.isMissingNode() && !textNode.isNull()) {
                            textContent.append(textNode.asText());
                        }
                        break;
                    case "tool_use":
                        var toolCall = Output.ToolCall.builder()
                                .id(block.path("id").asText())
                                .name(block.path("name").asText())
                                .arguments(block.path("input").toString())
                                .build();
                        toolCalls.add(toolCall);
                        break;
                    case "thinking":
                        var thinkingNode = block.path("thinking");
                        if (!thinkingNode.isMissingNode() && !thinkingNode.isNull()) {
                            thinkingContent.append(thinkingNode.asText());
                        }
                        break;
                }
            }

            if (textContent.length() > 0) {
                choice.setText(textContent.toString());
            }
            if (thinkingContent.length() > 0) {
                choice.setThinking(thinkingContent.toString());
            }
            if (!toolCalls.isEmpty()) {
                choice.setToolCall(toolCalls);
            }

            output.setChoices(List.of(choice));
        }

        var stopReasonNode = data.path("stop_reason");
        if (!stopReasonNode.isMissingNode() && !stopReasonNode.isNull()) {
            var choice = output.getChoices() != null && !output.getChoices().isEmpty()
                    ? output.getChoices().getFirst()
                    : new Output.Choice();
            choice.setFinishReason(stopReasonNode.asText());
            if (output.getChoices() == null || output.getChoices().isEmpty()) {
                output.setChoices(List.of(choice));
            }
        }

        var usageNode = data.path("usage");
        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
            var usage = Output.Usage.builder()
                    .promptTokens(usageNode.path("input_tokens").asInt())
                    .completionTokens(usageNode.path("output_tokens").asInt())
                    .totalTokens(
                            usageNode.path("input_tokens").asInt() + usageNode.path("output_tokens").asInt()
                    )
                    .build();
            output.setUsage(usage);
        }

        return output;
    }
}
