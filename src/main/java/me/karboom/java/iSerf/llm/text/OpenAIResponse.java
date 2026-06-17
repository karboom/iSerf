package me.karboom.java.iSerf.llm.text;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API 实现
 */
@Slf4j
public class OpenAIResponse extends AbstractOpenAIText {

    public OpenAIResponse(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        super(llmType, llmConfig, apiKey, url, maxRetries);
    }

    @Override
    protected String chatEndpoint() {
        return "/v1/responses";
    }

    @Override
    protected String batchEndpoint() {
        return "/v1/responses";
    }

    /**
     * 使用 Responses API 进行流式对话
     */
    @Override
    public Flux<Output> send(List<AgentMessage> memory, Class<?> outputFormat, List<Tool<?>> tools) {
        return Flux.create(sink -> {
            var requestBody = buildRequestBody(memory, outputFormat, tools);

            var request = new Request.Builder()
                    .url("%s/responses".formatted(this.url))
                    .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            var listener = new EventSourceListener() {
                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    log.debug(" send response open ");
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    log.debug(" send response event type={} data={} ", type, data);
                    if (data != null) {
                        if ("[DONE]".equals(data)) {
                            sink.complete();
                            return;
                        }
                        var output = parseOutput(JSONUtil.parse(data), true);
                        if (output != null) {
                            sink.next(output);
                        }
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    log.debug(" send response close ");
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    log.error(" send response failure ", t);
                    sink.error(t);
                }
            };

            var factory = EventSources.createFactory(HttpUtil.getClient());
            var eventSource = factory.newEventSource(request, listener);

            sink.onDispose(eventSource::cancel);
        });
    }

    /**
     * 使用 Responses API 进行非流式查询
     */
    @Override
    public Output query(List<AgentMessage> messages, Class<?> outputFormat) {
        var requestBody = buildRequestBody(messages, outputFormat, null);
        var requestJson = JSONUtil.parse(requestBody);
        requestJson.remove("stream");

        var request = new Request.Builder()
                .url("%s/responses".formatted(this.url))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestJson.toString(), MediaType.parse("application/json")))
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
     * 构建请求体
     */
    @Override
    protected String buildRequestBody(List<AgentMessage> memory, Class<?> outputFormat, List<Tool<?>> tools) {
        var body = JSONUtil.create();
        body.put("model", llmType);
        body.put("stream", true);

        var input = convertMessagesToInput(memory, tools);
        body.set("input", input);

        if (outputFormat != null) {
            var text = JSONUtil.create();
            var format = JSONUtil.create();
            format.put("type", "json_schema");

            var jsonSchema = JSONUtil.create();
            jsonSchema.put("name", outputFormat.getSimpleName());
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", JSONUtil.parse(schemaGenerator.generateSchema(outputFormat).toString()));

            format.set("json_schema", jsonSchema);
            text.set("format", format);
            body.set("text", text);
        }

        if (tools != null && !tools.isEmpty()) {
            body.set("tools", buildToolsJson(tools));
        }

        if (llmConfig.containsKey("temperature")) {
            body.put("temperature", ((Number) llmConfig.get("temperature")).doubleValue());
        }

        if (llmConfig.containsKey("max_tokens")) {
            body.put("max_output_tokens", ((Number) llmConfig.get("max_tokens")).intValue());
        }

        if (llmConfig.containsKey("top_p")) {
            body.put("top_p", ((Number) llmConfig.get("top_p")).doubleValue());
        }

        if (llmConfig.containsKey("thinking")) {
            var reasoning = JSONUtil.create();
            reasoning.put("effort", "high");
            body.set("reasoning", reasoning);
        }

        return body.toString();
    }

    /**
     * 将 Messages 转换为 Responses API 的 input 格式
     */
    private ArrayNode convertMessagesToInput(List<AgentMessage> memory, List<Tool<?>> tools) {
        var inputArray = JSONUtil.createArray();

        for (var item : memory) {
            switch (item.role) {
                case AgentMessage.ROLE.USER:
                    var userMessage = JSONUtil.create();
                    userMessage.put("type", "message");
                    userMessage.put("role", "user");

                    var userContent = buildContent(item);
                    userMessage.set("content", userContent);
                    inputArray.add(userMessage);
                    break;

                case AgentMessage.ROLE.ASSISTANT:
                    var assistantMessage = JSONUtil.create();
                    assistantMessage.put("type", "message");
                    assistantMessage.put("role", "assistant");
                    assistantMessage.put("content", item.text);
                    inputArray.add(assistantMessage);
                    break;

                case AgentMessage.ROLE.SYSTEM:
                    var systemMessage = JSONUtil.create();
                    systemMessage.put("type", "message");
                    systemMessage.put("role", "system");
                    systemMessage.put("content", item.text);
                    inputArray.add(systemMessage);
                    break;

                case AgentMessage.ROLE.TOOL:
                    if (item.toolCalls != null) {
                        for (var toolCall : item.toolCalls) {
                            var toolOutput = JSONUtil.create();
                            toolOutput.put("type", "function_call_output");
                            toolOutput.put("call_id", toolCall.getId());
                            toolOutput.put("output", toolCall.getResult().getLlm());
                            inputArray.add(toolOutput);
                        }
                    }
                    break;
            }
        }

        return inputArray;
    }

    /**
     * 构建消息内容
     */
    private JsonNode buildContent(AgentMessage item) {
        var result = JSONUtil.create();
        switch (item.type) {
            case AgentMessage.TYPE.TEXT:
                result.put("type", "input_text");
                result.put("text", item.text);
                break;
            case AgentMessage.TYPE.IMAGE:
                var content = JSONUtil.createArray();
                if (item.files != null) {
                    for (var image : item.files) {
                        content.add(JSONUtil.create()
                                .put("type", "image_url")
                                .set("image_url", JSONUtil.create().put("url", image)));
                    }
                }
                if (item.getText() != null) {
                    content.add(JSONUtil.create().put("type", "input_text").put("text", item.getText()));
                }
                return content;
            case AgentMessage.TYPE.AUDIO:
                var audioContent = JSONUtil.createArray();
                if (item.audio != null) {
                    audioContent.add(JSONUtil.create()
                            .put("type", "input_audio")
                            .set("input_audio", JSONUtil.create().put("data", item.audio)));
                }
                if (item.getText() != null) {
                    audioContent.add(JSONUtil.create().put("type", "input_text").put("text", item.getText()));
                }
                return audioContent;
            case AgentMessage.TYPE.VIDEO:
                var videoContent = JSONUtil.createArray();
                if (item.video != null) {
                    videoContent.add(JSONUtil.create()
                            .put("type", "video_url")
                            .put("fps", item.getFps())
                            .set("video_url", JSONUtil.create().put("url", item.video)));
                }
                if (item.getText() != null) {
                    videoContent.add(JSONUtil.create().put("type", "input_text").put("text", item.getText()));
                }
                return videoContent;
            default:
                result.put("type", "input_text");
                result.put("text", item.text);
                break;
        }
        return result;
    }

    /**
     * 解析响应
     */
    @Override
    protected Output parseOutput(ObjectNode data, boolean isStream) {
        var output = new Output();
        output.isDelta = isStream;

        var idNode = data.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            output.setId(idNode.asString());
        }

        output.type = data.path("type").asText("response");

        var errorNode = data.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            output.setError(errorNode.path("message").asText());
        }

        var outputItems = data.path("output");
        if (!outputItems.isMissingNode() && !outputItems.isNull()) {
            var outputChoices = new ArrayList<Output.Choice>();
            var choice = new Output.Choice();
            var toolCalls = new ArrayList<Output.ToolCall>();

            for (var item : outputItems) {
                var itemType = item.path("type").asText();
                switch (itemType) {
                    case "message":
                        var contentItems = item.path("content");
                        for (var contentItem : contentItems) {
                            var contentType = contentItem.path("type").asText();
                            switch (contentType) {
                                case "output_text":
                                    var textNode = contentItem.path("text");
                                    if (!textNode.isMissingNode() && !textNode.isNull()) {
                                        choice.setText(textNode.asString());
                                    }
                                    var reasoningNode = contentItem.path("annotations");
                                    break;
                                case "refusal":
                                    var refusalNode = contentItem.path("refusal");
                                    if (!refusalNode.isMissingNode() && !refusalNode.isNull()) {
                                        choice.setText(refusalNode.asString());
                                    }
                                    break;
                            }
                        }
                        break;
                    case "function_call":
                        var toolCall = Output.ToolCall.builder()
                                .id(item.path("call_id").asString())
                                .name(item.path("name").asString())
                                .arguments(item.path("arguments").asString())
                                .build();
                        toolCalls.add(toolCall);
                        break;
                    case "reasoning":
                        var reasoningSummary = item.path("summary");
                        if (!reasoningSummary.isMissingNode() && !reasoningSummary.isArray()) {
                            for (var summaryItem : reasoningSummary) {
                                var textNode = summaryItem.path("text");
                                if (!textNode.isMissingNode() && !textNode.isNull()) {
                                    choice.setThinking(textNode.asString());
                                }
                            }
                        }
                        break;
                }
            }

            if (!toolCalls.isEmpty()) {
                choice.setToolCall(toolCalls);
            }
            outputChoices.add(choice);
            output.setChoices(outputChoices);
        }

        var usageNode = data.path("usage");
        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
            var usage = Output.Usage.builder()
                    .promptTokens(usageNode.path("input_tokens").asInt())
                    .completionTokens(usageNode.path("output_tokens").asInt())
                    .totalTokens(usageNode.path("total_tokens").asInt(-1))
                    .build();

            if (usage.getTotalTokens() == -1) {
                usage.setTotalTokens(usage.getPromptTokens() + usage.getCompletionTokens());
            }

            var reasoningTokensNode = usageNode.path("output_tokens_details").path("reasoning_tokens");
            if (!reasoningTokensNode.isMissingNode() && !reasoningTokensNode.isNull()) {
                usage.setThinkingTokens(reasoningTokensNode.asInt());
            }

            output.setUsage(usage);
        }

        var statusNode = data.path("status");
        if (!statusNode.isMissingNode() && !statusNode.isNull()) {
            var status = statusNode.asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                var finishChoice = output.getChoices().isEmpty() ? new Output.Choice() : output.getChoices().get(0);
                finishChoice.setFinishReason(status);
            }
        }

        return output;
    }
}
