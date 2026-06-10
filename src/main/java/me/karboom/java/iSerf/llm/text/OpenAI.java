package me.karboom.java.iSerf.llm.text;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.agent.tool.Tool;
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
    public Flux<Output> send(List<Message> memory, Class<?> outputFormat, List<Tool<?>> tools) {
        return Flux.create(sink -> {
            var requestBody = buildRequestBody(memory, outputFormat, tools);

            var request = new Request.Builder()
                    .url("%s/chat/completions".formatted(this.url))
                    .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            var listener = new EventSourceListener() {
                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    System.out.println("open");
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
//                    System.out.println("source " + data);
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
                    System.out.println("close");
//                    sink.complete();
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    sink.error(t);
                }
            };

            var factory = EventSources.createFactory(HttpUtil.getClient());
            var eventSource = factory.newEventSource(request, listener);

            sink.onDispose(eventSource::cancel);
        });
    }

    @Override
    public Output query(List<Message> messages, Class<?> outputFormat) {
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
            if (!response.isSuccessful()) {
                throw new RuntimeException("Query failed: " + response.code());
            }
            var responseBody = response.body();
            if (responseBody == null) {
                throw new RuntimeException("Query response is null");
            }
            var responseJson = JSONUtil.parse(responseBody.string());
            return parseOutput(responseJson, false);
        } catch (Exception e) {
            throw new RuntimeException("Query error", e);
        }
    }

    @Override
    protected String buildRequestBody(List<Message> memory, Class<?> outputFormat, List<Tool<?>> tools) {
        var body = JSONUtil.create();
        body.put("model", llmType);
        body.put("stream", true);

        if (outputFormat != null) {
            var responseFormat = JSONUtil.create();
            responseFormat.put("type", "json_schema");

            var jsonSchema = JSONUtil.create();
            jsonSchema.put("name", outputFormat.getSimpleName());
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", JSONUtil.parse(schemaGenerator.generateSchema(outputFormat).toString()));

            responseFormat.set("json_schema", jsonSchema);
            body.set("response_format", responseFormat);
        }

        if (tools != null && !tools.isEmpty()) {
            body.set("tools", buildToolsJson(tools));
        }

        var messagesArray = JSONUtil.createArray();
        for (var item : memory) {
            switch (item.role) {
                case Message.ROLE.USER:
                    var userMessage = JSONUtil.create();
                    userMessage.put("role", "user");

                    switch (item.type) {
                        case Message.TYPE.TEXT:
                            userMessage.put("content", item.text);
                            break;
                        case Message.TYPE.IMAGE:
                            var content = JSONUtil.createArray();
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

                        case Message.TYPE.AUDIO:
                            var audioContent = JSONUtil.createArray();
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

                        case Message.TYPE.VIDEO:
                            var videoContent = JSONUtil.createArray();
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

                case Message.ROLE.ASSISTANT:
                    var assistantMessage = JSONUtil.create();
                    assistantMessage.put("role", "assistant");

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

                case Message.ROLE.SYSTEM:
                    messagesArray.add(JSONUtil.create()
                            .put("role", "system")
                            .put("content", item.text));
                    break;

                case Message.ROLE.TOOL:
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
            body.put("temperature", ((Number) llmConfig.get("temperature")).doubleValue());
        }

        if (llmConfig.containsKey("max_tokens")) {
            body.put("max_tokens", ((Number) llmConfig.get("max_tokens")).intValue());
        }

        if (llmConfig.containsKey("top_p")) {
            body.put("top_p", ((Number) llmConfig.get("top_p")).doubleValue());
        }

        var streamOptions = JSONUtil.create();
        streamOptions.put("include_usage", true);
        body.set("stream_options", streamOptions);

        if (llmConfig.containsKey("thinking")) {
            body.put("enable_thinking", true);
        }

        return body.toString();
    }

    private ObjectNode buildParamNode(Tool.Parameter param) {
        var paramNode = JSONUtil.create();
        paramNode.put("type", param.type);
        if (param.description != null) {
            paramNode.put("description", param.description);
        }

        if (param.properties != null && !param.properties.isEmpty()) {
            var properties = JSONUtil.create();
            var required = JSONUtil.createArray();

            for (var subParam : param.properties) {
                var subParamNode = buildParamNode(subParam);
                properties.set(subParam.name, subParamNode);

                if (subParam.required != null && subParam.required) {
                    required.add(subParam.name);
                }
            }

            paramNode.set("properties", properties);
            if (required.size() > 0) {
                paramNode.set("required", required);
            }
        }

        return paramNode;
    }

    /**
     * 解析响应body对象
     * @param data
     * @param isStream
     * @return
     */
    @Override
    protected Output parseOutput(ObjectNode data, boolean isStream) {
        var output = new Output();

        var idNode = data.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            output.setId(idNode.asString());
        }
        output.type = "chat.completion.chunk";
        output.isDelta = isStream;

        var choicesNode = data.path("choices");
        if (!choicesNode.isEmpty()) {
            var outputChoices = new ArrayList<Output.Choice>();
            for (var choiceNode : choicesNode) {
                var outputChoice = new Output.Choice();

                var messageContentNode = choiceNode.path(isStream ? "delta": "message");
                if (!messageContentNode.isEmpty()) {
                    var contentNode = messageContentNode.path("content");
                    if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                        outputChoice.text = contentNode.asString();
                    }

                    var reasoningContentNode = messageContentNode.path("reasoning_content");
                    if (!reasoningContentNode.isMissingNode() && !reasoningContentNode.isNull()) {
                        outputChoice.thinking = reasoningContentNode.asText();
                    }

                    var toolCallsNode = messageContentNode.path("tool_calls");
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
                    outputChoice.finishReason = finishReasonNode.asText();
                }

                outputChoices.add(outputChoice);
            }
            output.choices = outputChoices;
        }

        var usageNode = data.path("usage");
        if (!usageNode.isEmpty()) {
            var usage = Output.Usage.builder()
                    .promptTokens(usageNode.path("prompt_tokens").asInt())
                    .completionTokens(usageNode.path("completion_tokens").asInt())
                    .totalTokens(usageNode.path("total_tokens").asInt())
                    .build();

            var detailNode = usageNode.path("prompt_tokens_details");

            output.setUsage(usage);
        }

        return output;
    }

}
