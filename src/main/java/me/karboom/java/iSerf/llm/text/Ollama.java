package me.karboom.java.iSerf.llm.text;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
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
    public Flux<Output> send(List<Message> messages, Class<?> outputFormat, List<Tool> tools) {
        return Flux.create(sink -> {
            var requestBody = buildRequestBody(messages, outputFormat, tools, true);

            var request = new Request.Builder()
                    .url("%s/api/chat".formatted(this.url))
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            var listener = new EventSourceListener() {
                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    log.debug("send - Ollama NDJSON connection opened");
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    log.debug("send - Received NDJSON line: {}", data);
                    if (data != null && !data.trim().isEmpty()) {
                        try {
                            var json = JSONUtil.parse(data);
                            var doneNode = json.path("done");
                            if (doneNode.asBoolean(false)) {
                                var output = parseOutput(json, true);
                                if (output != null) {
                                    sink.next(output);
                                }
                                sink.complete();
                                return;
                            }
                            var output = parseOutput(json, true);
                            if (output != null && output.getChoices() != null && !output.getChoices().isEmpty()) {
                                sink.next(output);
                            }
                        } catch (Exception e) {
                            log.debug("send - Parse error: {}", e.getMessage());
                        }
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    log.debug("send - NDJSON connection closed");
                    sink.complete();
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    log.debug("send - NDJSON connection failed: {}", t.getMessage());
                    sink.error(t);
                }
            };

            var factory = EventSources.createFactory(HttpUtil.getClient());
            factory.newEventSource(request, listener);
        });
    }

    @Override
    public Output query(List<Message> messages, Class<?> outputFormat) {
        var requestBody = buildRequestBody(messages, outputFormat, null, false);

        var request = new Request.Builder()
                .url("%s/api/chat".formatted(this.url))
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
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
    public String batch(List<List<Message>> messageBatch, Class<?> outputFormat) {
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

    private String buildRequestBody(List<Message> messages, Class<?> outputFormat, List<Tool> tools, boolean stream) {
        var body = JSONUtil.create();
        body.put("model", llmType);
        body.put("stream", stream);

        if (outputFormat != null) {
            body.put("format", "json");
        }

        var messagesArray = JSONUtil.createArray();
        for (var item : messages) {
            switch (item.role) {
                case Message.ROLE.USER:
                    var userMessage = JSONUtil.create();
                    userMessage.put("role", "user");
                    userMessage.put("content", item.text);
                    messagesArray.add(userMessage);
                    break;

                case Message.ROLE.ASSISTANT:
                    var assistantMessage = JSONUtil.create();
                    assistantMessage.put("role", "assistant");
                    assistantMessage.put("content", item.text);
                    messagesArray.add(assistantMessage);
                    break;

                case Message.ROLE.SYSTEM:
                    var systemMessage = JSONUtil.create();
                    systemMessage.put("role", "system");
                    systemMessage.put("content", item.text);
                    messagesArray.add(systemMessage);
                    break;

                case Message.ROLE.TOOL:
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
            options.put("temperature", ((Number) llmConfig.get("temperature")).doubleValue());
        }
        if (llmConfig.containsKey("max_tokens")) {
            options.put("num_predict", ((Number) llmConfig.get("max_tokens")).intValue());
        }
        if (llmConfig.containsKey("top_p")) {
            options.put("top_p", ((Number) llmConfig.get("top_p")).doubleValue());
        }
        if (!options.isEmpty()) {
            body.set("options", options);
        }

        return body.toString();
    }

    private Output parseOutput(ObjectNode data, boolean isStream) {
        var output = new Output();
        output.type = "chat.completion.chunk";
        output.isDelta = isStream;

        var idNode = data.path("created_at");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            output.setId(idNode.asText());
        }

        var messageNode = data.path("message");
        var contentNode = messageNode.path("content");
        
        var choices = new ArrayList<Output.Choice>();
        var choice = new Output.Choice();
        
        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
            choice.text = contentNode.asText();
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

        return output;
    }
}