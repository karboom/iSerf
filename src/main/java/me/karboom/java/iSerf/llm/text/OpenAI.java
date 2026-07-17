package me.karboom.java.iSerf.llm.text;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.HttpUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI
 */
@Slf4j
public class OpenAI extends AbstractOpenAIText {

    public OpenAI(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        super(llmType, llmConfig, apiKey, url, maxRetries);
    }

    @Override
    protected String chatEndpoint() {
        return "/v1/chat/completions";
    }

    @Override
    protected String batchEndpoint() {
        return "/v1/chat/completions";
    }

    /**
     * 使用 okhttp sse 直接调用openai入参兼容的http接口
     *
     * @param memory       记忆内容
     * @param outputFormat 输出格式
     * @param tools        工具集
     * @return 自定义数据结构
     */
    @Override
    public Flux<Output> send(List<AgentMessage> memory, Class<?> outputFormat, List<Tool<?>> tools) {
        log.debug("<send> input params | memory.size=%s, outputFormat=%s, tools=%s".formatted(memory.size(), outputFormat != null ? outputFormat.getSimpleName() : null, tools != null ? tools.size() : null));
        return Flux.create(sink -> {
            var requestBody = buildRequestBody(memory, outputFormat, tools);
            log.debug("<send> build request body | requestBody.length=%s".formatted(requestBody.length()));

            var request = new Request.Builder()
                    .url("%s/chat/completions".formatted(this.url))
                    .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            var listener = new EventSourceListener() {
                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    log.debug("<onOpen> connection opened | code=%s".formatted(response.code()));
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    log.debug("<onEvent> received event | id=%s, type=%s".formatted(id, type));
                    if (data != null) {
                        if ("[DONE]".equals(data)) {
                            log.debug("<onEvent> received done signal | data=DONE");
                            sink.complete();
                            return;
                        }
                        var output = parseOutput(JSONUtil.parse(data), true, false);
                        if (output != null) {
                            if (output.getError() != null) {
                                log.debug("<onEvent> output error | error=%s".formatted(output.getError()));
                                sink.error(new RuntimeException(output.getError()));
                            } else {
                                log.debug("<onEvent> output parsed | choices=%s".formatted(output.choices != null ? output.choices.size() : 0));
                                sink.next(output);
                            }
                        }
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    log.debug("<onClosed> connection closed |");
//                    sink.complete();
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    log.debug("<onFailure> connection failed | error=%s, responseCode=%s".formatted(t.getMessage(), response != null ? response.code() : null));
                    sink.error(t);
                }
            };

            var factory = EventSources.createFactory(HttpUtil.getClient());
            log.debug("<send> create factory |");
            var eventSource = factory.newEventSource(request, listener);
            log.debug("<send> create event source | url=%s".formatted(request.url()));

            sink.onDispose(eventSource::cancel);
        });
    }

    @Override
    public Output query(List<AgentMessage> messages, Class<?> outputFormat) {
        log.debug("<query> input params | messages.size=%s, outputFormat=%s".formatted(messages.size(), outputFormat != null ? outputFormat.getSimpleName() : null));
        var requestBody = buildRequestBody(messages, outputFormat, null);
        var requestJson = JSONUtil.parse(requestBody);
        requestJson.remove("stream");
        requestJson.remove("stream_options");

        var request = new Request.Builder()
                .url("%s/chat/completions".formatted(this.url))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestJson.toString(), MediaType.parse("application/json")))
                .build();

        try (var response = HttpUtil.getClient().newCall(request).execute()) {
            log.debug("<query> response | response.code=%s".formatted(response.code()));
            if (!response.isSuccessful()) {
                throw new RuntimeException("Query failed: " + response.code());
            }
            var responseBody = response.body();
            log.debug("<query> response body | responseBody=%s".formatted(responseBody != null));
            if (responseBody == null) {
                throw new RuntimeException("Query response is null");
            }
            var bodyString = responseBody.string();
            log.debug("<query> response body length | bodyString.length=%s".formatted(bodyString.length()));
            var responseJson = JSONUtil.parse(bodyString);
            log.debug("<query> parse response | responseJson=%s".formatted(responseJson));
            return parseOutput(responseJson, false, outputFormat != null);
        } catch (Exception e) {
            throw new RuntimeException("Query error", e);
        }
    }

    @Override
    protected String buildRequestBody(List<AgentMessage> memory, Class<?> outputFormat, List<Tool<?>> tools) {
        log.debug("<buildRequestBody> input params | memory.size=%s, outputFormat=%s, tools=%s".formatted(memory.size(), outputFormat != null ? outputFormat.getSimpleName() : null, tools != null ? tools.size() : null));
        var body = JSONUtil.create();
        body.put("model", llmType);
        body.put("stream", true);

        if (outputFormat != null) {
            log.debug("<buildRequestBody> set output format | outputFormat=%s".formatted(outputFormat.getSimpleName()));
            var responseFormat = JSONUtil.create();
            responseFormat.put("type", "json_schema");

            var jsonSchema = JSONUtil.create();
            jsonSchema.put("name", outputFormat.getSimpleName());
            jsonSchema.put("strict", true);
            var schema = schemaGenerator.generateSchema(outputFormat);
            log.debug("<buildRequestBody> generate schema | result=%s".formatted(schema));
            jsonSchema.set("schema", JSONUtil.parse(schema.toString()));

            responseFormat.set("json_schema", jsonSchema);
            body.set("response_format", responseFormat);
        }

        if (tools != null && !tools.isEmpty()) {
            log.debug("<buildRequestBody> set tools | tools.size=%s".formatted(tools.size()));
            body.set("tools", buildToolsJson(tools));
        }

        var messagesArray = JSONUtil.createArray();
        for (var item : memory) {
            log.debug("<buildRequestBody> process message | role=%s, type=%s".formatted(item.role, item.type));
            switch (item.role) {
                case AgentMessage.ROLE.USER:
                    var userMessage = JSONUtil.create();
                    userMessage.put("role", "user");

                    switch (item.type) {
                        case AgentMessage.TYPE.TEXT:
                            userMessage.put("content", item.text);
                            break;
                        case AgentMessage.TYPE.IMAGE:
                            var content = JSONUtil.createArray();
                            log.debug("<buildRequestBody> process image message | files.size=%s, text=%s".formatted(item.files != null ? item.files.size() : 0, item.getText() != null));
                            if (item.files != null) {
                                for (var image : item.files) {
                                    content.add(JSONUtil.create()
                                            .put("type", "image_url")
                                            .set("image_url", JSONUtil.create().put("url", image)));
                                }
                            }
                            if (item.getText() != null) {
                                content.add(JSONUtil.create().put("type", "text").put("text", item.getText()));
                            }
                            userMessage.set("content", content);
                            break;

                        case AgentMessage.TYPE.AUDIO:
                            var audioContent = JSONUtil.createArray();
                            log.debug("<buildRequestBody> process audio message | audio=%s, text=%s".formatted(item.audio != null, item.getText() != null));
                            if (item.audio != null) {
                                    audioContent.add(JSONUtil.create()
                                            .put("type", "input_audio")
                                            .set("input_audio", JSONUtil.create().put("data", item.audio)));
                            }
                            if (item.getText() != null) {
                                audioContent.add(JSONUtil.create().put("type", "text").put("text", item.getText()));
                            }
                            userMessage.set("content", audioContent);

                            break;

                        case AgentMessage.TYPE.VIDEO:
                            var videoContent = JSONUtil.createArray();
                            log.debug("<buildRequestBody> process video message | video=%s, fps=%s, text=%s".formatted(item.video != null, item.getFps(), item.getText() != null));
                            if (item.video != null) {
                                videoContent.add(JSONUtil.create()
                                        .put("type", "video_url")
                                                .put("fps", item.getFps())
                                        .set("video_url", JSONUtil.create().put("url", item.video)));
                            }
                            if (item.getText() != null) {
                                videoContent.add(JSONUtil.create().put("type", "text").put("text", item.getText()));
                            }
                            userMessage.set("content", videoContent);

                            break;

                    }
                    messagesArray.add(userMessage);
                    break;

                case AgentMessage.ROLE.ASSISTANT:
                    var assistantMessage = JSONUtil.create();
                    assistantMessage.put("role", "assistant");
                    log.debug("<buildRequestBody> process assistant message | toolCalls.size=%s".formatted(item.toolCalls != null ? item.toolCalls.size() : 0));

                    if (item.toolCalls != null && !item.toolCalls.isEmpty()) {
                        var toolCalls = JSONUtil.createArray();
                        for (var toolCall : item.toolCalls) {
                            var toolCallNode = JSONUtil.create();
                            toolCallNode.put("id", toolCall.getId());
                            toolCallNode.put("type", "function");
                            toolCallNode.set("function", JSONUtil.create()
                                    .put("name", toolCall.getName())
                                    .put("arguments", ""));
                            toolCalls.add(toolCallNode);
                        }
                        assistantMessage.set("tool_calls", toolCalls);
                    } else {
                        assistantMessage.put("content", item.text);
                    }
                    messagesArray.add(assistantMessage);
                    break;

                case AgentMessage.ROLE.SYSTEM:
                    messagesArray.add(JSONUtil.create()
                            .put("role", "system")
                            .put("content", item.text));
                    break;

                case AgentMessage.ROLE.TOOL:
                    log.debug("<buildRequestBody> process tool message | toolCalls.size=%s".formatted(item.toolCalls != null ? item.toolCalls.size() : 0));
                    if (item.toolCalls != null) {
                        for (var toolCall : item.toolCalls) {
                            messagesArray.add(JSONUtil.create()
                                    .put("role", "tool")
                                    .put("tool_call_id", toolCall.getId())
                                    .put("content", toolCall.getResult().getLlm()));
                        }
                    }
                    break;
            }
        }
        body.set("messages", messagesArray);

        if (llmConfig.containsKey("temperature")) {
            var temperature = ((Number) llmConfig.get("temperature")).doubleValue();
            log.debug("<buildRequestBody> set temperature | temperature=%s".formatted(temperature));
            body.put("temperature", temperature);
        }

        if (llmConfig.containsKey("max_tokens")) {
            var maxTokens = ((Number) llmConfig.get("max_tokens")).intValue();
            log.debug("<buildRequestBody> set max tokens | maxTokens=%s".formatted(maxTokens));
            body.put("max_tokens", maxTokens);
        }

        if (llmConfig.containsKey("top_p")) {
            var topP = ((Number) llmConfig.get("top_p")).doubleValue();
            log.debug("<buildRequestBody> set top p | topP=%s".formatted(topP));
            body.put("top_p", topP);
        }

        var streamOptions = JSONUtil.create();
        streamOptions.put("include_usage", true);
        body.set("stream_options", streamOptions);

        if (llmConfig.containsKey("thinking")) {
            log.debug("<buildRequestBody> enable thinking | thinking=true");
            body.put("enable_thinking", true);
        }

        log.debug("<buildRequestBody> body built | body.length=%s".formatted(body.toString().length()));
        return body.toString();
    }

    private ObjectNode buildParamNode(Tool.Parameter param) {
        log.debug("<buildParamNode> input params | name=%s, type=%s".formatted(param.name, param.type));
        var paramNode = JSONUtil.create();
        paramNode.put("type", param.type);
        if (param.description != null) {
            log.debug("<buildParamNode> set description | description=%s".formatted(param.description));
            paramNode.put("description", param.description);
        }

        if (param.properties != null && !param.properties.isEmpty()) {
            log.debug("<buildParamNode> process properties | properties.size=%s".formatted(param.properties.size()));
            var properties = JSONUtil.create();
            var required = JSONUtil.createArray();

            for (var subParam : param.properties) {
                var subParamNode = buildParamNode(subParam);
                properties.set(subParam.name, subParamNode);

                if (subParam.required != null && subParam.required) {
                    log.debug("<buildParamNode> add required | required=%s".formatted(subParam.name));
                    required.add(subParam.name);
                }
            }

            paramNode.set("properties", properties);
            if (required.size() > 0) {
                paramNode.set("required", required);
            }
        }

        log.debug("<buildParamNode> param built | name=%s, hasProperties=%s".formatted(param.name, paramNode.path("properties").isEmpty() ? false : true));
        return paramNode;
    }

    /**
     * 解析响应body对象
     * @param data
     * @param isStream
     * @param isObject 是否清理 JSON 内容（去除 markdown 代码块标记等）
     * @return
     */
    @Override
    protected Output parseOutput(ObjectNode data, boolean isStream, boolean isObject) {
        log.debug("<parseOutput> input params | isStream=%s".formatted(isStream));
        var output = new Output();

        var idNode = data.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            log.debug("<parseOutput> extract id | id=%s".formatted(idNode.asString()));
            output.setId(idNode.asString());
        }
        output.type = "chat.completion.chunk";
        output.isDelta = isStream;

        var choicesNode = data.path("choices");
        log.debug("<parseOutput> check choices | choices.empty=%s".formatted(choicesNode.isEmpty()));
        if (!choicesNode.isEmpty()) {
            var outputChoices = new ArrayList<Output.Choice>();
            for (var choiceNode : choicesNode) {
                var outputChoice = new Output.Choice();

                var messageContentNode = choiceNode.path(isStream ? "delta": "message");
                log.debug("<parseOutput> check message content | messageContentNode.empty=%s".formatted(messageContentNode.isEmpty()));
                if (!messageContentNode.isEmpty()) {
                    var contentNode = messageContentNode.path("content");
                    if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                        var contentText = contentNode.asString();
                        if (isObject) {
                            contentText = DataUtil.cleanJsonContent(contentText);
                        }
                        log.debug("<parseOutput> extract content | content=%s, isObject=%s".formatted(contentText, isObject));
                        outputChoice.text = contentText;
                    }

                    var reasoningContentNode = messageContentNode.path("reasoning_content");
                    if (!reasoningContentNode.isMissingNode() && !reasoningContentNode.isNull()) {
                        log.debug("<parseOutput> extract reasoning content | reasoningContent=%s".formatted(reasoningContentNode.asText()));
                        outputChoice.thinking = reasoningContentNode.asText();
                    }

                    var toolCallsNode = messageContentNode.path("tool_calls");
                    log.debug("<parseOutput> check tool calls | toolCallsNode.empty=%s".formatted(toolCallsNode.isEmpty()));
                    if (!toolCallsNode.isEmpty()) {
                        var toolCalls = new ArrayList<Output.ToolCall>();

                        for (var toolCall : toolCallsNode) {
                            var functionNode = toolCall.path("function");
                            var funcIdNode = toolCall.path("id");
                            var indexNode = toolCall.path("index");

                            var nameNode = functionNode.path("name");
                            var argNode = functionNode.path("arguments");


                            var object = Output.ToolCall.builder().id(funcIdNode.asString()).index(indexNode.asInt()).arguments(argNode.asString()).build();

                            if (!nameNode.isMissingNode()) {
                                object.setName(nameNode.asString());
                            }

                            toolCalls.add(object);
                        }

                        outputChoice.toolCall = toolCalls;
                    }
                }

                var finishReasonNode = choiceNode.path("finish_reason");
                if (!finishReasonNode.isEmpty()) {
                    log.debug("<parseOutput> extract finish reason | finishReason=%s".formatted(finishReasonNode.asText()));
                    outputChoice.finishReason = finishReasonNode.asText();
                }

                outputChoices.add(outputChoice);
            }
            output.choices = outputChoices;
        }

        var usageNode = data.path("usage");
        log.debug("<parseOutput> check usage | usageNode.empty=%s".formatted(usageNode.isEmpty()));
        if (!usageNode.isEmpty()) {
            var usage = Output.Usage.builder()
                    .promptTokens(usageNode.path("prompt_tokens").asInt())
                    .completionTokens(usageNode.path("completion_tokens").asInt())
                    .totalTokens(usageNode.path("total_tokens").asInt())
                    .build();

            var detailNode = usageNode.path("prompt_tokens_details");

            output.setUsage(usage);
        }

        log.debug("<parseOutput> output parsed | id=%s, choices=%s, hasUsage=%s".formatted(output.getId(), output.choices != null ? output.choices.size() : 0, output.getUsage() != null));
        return output;
    }

}
