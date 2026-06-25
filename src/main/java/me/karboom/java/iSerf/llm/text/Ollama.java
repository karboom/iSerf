package me.karboom.java.iSerf.llm.text;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
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
 * Ollama - 本地运行的大模型
 * 使用 Ollama 原生 /api/chat 接口和 NDJSON 格式
 */
@Slf4j
public class Ollama implements IText {
    public String llmType;
    public Map<String, Object> llmConfig;
    protected String apiKey;
    protected String url;
    protected Integer maxRetries;
    private final SchemaGenerator schemaGenerator;

    public Ollama(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        this.llmType = llmType;
        this.llmConfig = llmConfig;
        this.apiKey = apiKey;
        this.url = url;
        this.maxRetries = maxRetries;

        var configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        var config = configBuilder.forFields().withRequiredCheck(fieldScope -> true);
        this.schemaGenerator = new SchemaGenerator(configBuilder.build());
    }

    @Override
    public Flux<Output> send(List<AgentMessage> messages, Class<?> outputFormat, List<Tool<?>> tools) {
        log.debug("<send> input params | messages.size=%s, outputFormat=%s, tools=%s".formatted(messages.size(), outputFormat != null ? outputFormat.getSimpleName() : null, tools != null ? tools.size() : null));
        return Flux.create(sink -> {
            var requestBody = buildRequestBody(messages, outputFormat, tools, true);
            log.debug("<send> build request body | requestBody.length=%s".formatted(requestBody.length()));

            var request = new Request.Builder()
                    .url("%s/api/chat".formatted(this.url))
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
                    if (data != null && !data.trim().isEmpty()) {
                        try {
                            var json = JSONUtil.parse(data);

                            // Ollama 错误响应
                            var errorNode = json.path("error");
                            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                                log.debug("<onEvent> error | error=%s".formatted(errorNode.asText()));
                                sink.error(new RuntimeException(errorNode.asText()));
                                return;
                            }

                            var doneNode = json.path("done");
                            if (doneNode.asBoolean(false)) {
                                var output = parseOutput(json, true, false);
                                if (output != null) {
                                    sink.next(output);
                                }
                                log.debug("<onEvent> done |");
                                sink.complete();
                                return;
                            }
                            var output = parseOutput(json, true, false);
                            if (output != null && output.getChoices() != null && !output.getChoices().isEmpty()) {
                                log.debug("<onEvent> output parsed | choices=%s".formatted(output.getChoices().size()));
                                sink.next(output);
                            }
                        } catch (Exception e) {
                            log.debug("<onEvent> parse error | error=%s".formatted(e.getMessage()));
                        }
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    log.debug("<onClosed> connection closed |");
                    sink.complete();
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
        var requestBody = buildRequestBody(messages, outputFormat, null, false);

        var request = new Request.Builder()
                .url("%s/api/chat".formatted(this.url))
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
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
    public String batch(List<List<AgentMessage>> messageBatch, Class<?> outputFormat) {
        throw new UnsupportedOperationException("Ollama does not support batch API");
    }

    @Override
    public BatchTaskInfo taskStatus(String taskId) {
        throw new UnsupportedOperationException("Ollama does not support batch API");
    }

    @Override
    public List<Output> taskResult(BatchTaskInfo task) {
        throw new UnsupportedOperationException("Ollama does not support batch API");
    }

    private String buildRequestBody(List<AgentMessage> messages, Class<?> outputFormat, List<Tool<?>> tools, boolean stream) {
        log.debug("<buildRequestBody> input params | messages.size=%s, outputFormat=%s, tools=%s, stream=%s".formatted(messages.size(), outputFormat != null ? outputFormat.getSimpleName() : null, tools != null ? tools.size() : null, stream));
        var body = JSONUtil.create();
        body.put("model", llmType);
        body.put("stream", stream);

        if (outputFormat != null) {
            log.debug("<buildRequestBody> set output format | outputFormat=%s".formatted(outputFormat.getSimpleName()));
            body.put("format", "json");
        }

        var messagesArray = JSONUtil.createArray();
        for (var item : messages) {
            switch (item.role) {
                case AgentMessage.ROLE.USER:
                    var userMessage = JSONUtil.create();
                    userMessage.put("role", "user");
                    userMessage.put("content", item.text);
                    messagesArray.add(userMessage);
                    break;

                case AgentMessage.ROLE.ASSISTANT:
                    var assistantMessage = JSONUtil.create();
                    assistantMessage.put("role", "assistant");
                    assistantMessage.put("content", item.text);
                    messagesArray.add(assistantMessage);
                    break;

                case AgentMessage.ROLE.SYSTEM:
                    var systemMessage = JSONUtil.create();
                    systemMessage.put("role", "system");
                    systemMessage.put("content", item.text);
                    messagesArray.add(systemMessage);
                    break;

                case AgentMessage.ROLE.TOOL:
                    if (item.toolCalls != null) {
                        for (var toolCall : item.toolCalls) {
                            var toolMessage = JSONUtil.create();
                            toolMessage.put("role", "user");
                            toolMessage.put("content", toolCall.getResult().getLlm());
                            messagesArray.add(toolMessage);
                        }
                    }
                    break;
            }
        }
        body.set("messages", messagesArray);

        var options = JSONUtil.create();
        if (llmConfig.containsKey("temperature")) {
            log.debug("<buildRequestBody> set temperature | temperature=%s".formatted(llmConfig.get("temperature")));
            options.put("temperature", ((Number) llmConfig.get("temperature")).doubleValue());
        }
        if (llmConfig.containsKey("max_tokens")) {
            log.debug("<buildRequestBody> set num_predict | max_tokens=%s".formatted(llmConfig.get("max_tokens")));
            options.put("num_predict", ((Number) llmConfig.get("max_tokens")).intValue());
        }
        if (llmConfig.containsKey("top_p")) {
            log.debug("<buildRequestBody> set top_p | top_p=%s".formatted(llmConfig.get("top_p")));
            options.put("top_p", ((Number) llmConfig.get("top_p")).doubleValue());
        }
        if (!options.isEmpty()) {
            body.set("options", options);
        }

        log.debug("<buildRequestBody> body built | body.length=%s".formatted(body.toString().length()));
        return body.toString();
    }

    private Output parseOutput(ObjectNode data, boolean isStream, boolean isObject) {
        log.debug("<parseOutput> input params | isStream=%s, isObject=%s".formatted(isStream, isObject));
        var output = new Output();
        output.type = "chat.completion.chunk";
        output.isDelta = isStream;

        var idNode = data.path("created_at");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            log.debug("<parseOutput> extract id | id=%s".formatted(idNode.asText()));
            output.setId(idNode.asText());
        }

        var messageNode = data.path("message");
        var contentNode = messageNode.path("content");
        
        var choices = new ArrayList<Output.Choice>();
        var choice = new Output.Choice();
        
        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
            var contentText = contentNode.asText();
            if (isObject) {
                contentText = cleanJsonContent(contentText);
            }
            log.debug("<parseOutput> extract content | content=%s, isObject=%s".formatted(contentText, isObject));
            choice.text = contentText;
        }

        var doneNode = data.path("done");
        if (!doneNode.isMissingNode() && doneNode.asBoolean(false)) {
            choice.finishReason = "stop";
            
            var promptEvalNode = data.path("prompt_eval_count");
            var evalNode = data.path("eval_count");
            
            if (!promptEvalNode.isMissingNode() || !evalNode.isMissingNode()) {
                var usage = Output.Usage.builder()
                        .promptTokens(promptEvalNode.isMissingNode() ? 0 : promptEvalNode.asInt())
                        .completionTokens(evalNode.isMissingNode() ? 0 : evalNode.asInt())
                        .totalTokens(
                                (promptEvalNode.isMissingNode() ? 0 : promptEvalNode.asInt()) +
                                (evalNode.isMissingNode() ? 0 : evalNode.asInt())
                        )
                        .build();
                output.setUsage(usage);
            }
        }

        choices.add(choice);
        output.choices = choices;

        log.debug("<parseOutput> output parsed | id=%s, choices=%s, hasUsage=%s".formatted(output.getId(), output.choices != null ? output.choices.size() : 0, output.getUsage() != null));
        return output;
    }

    /**
     * 清理 JSON 内容，去除 markdown 代码块标记等多余字符
     */
    private String cleanJsonContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // 去除开头的 ```json 或 ```
        content = content.replaceAll("^```\\w*\\s*\\n?", "");
        // 去除结尾的 ```
        content = content.replaceAll("\\n?```\\s*$", "");
        // 去除首尾空白
        return content.trim();
    }
}