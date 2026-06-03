package me.karboom.java.iSerf.llm.text;

import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Message;
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
public class OpenAIResponse implements IText {
    public String llmType;
    public Map<String, Object> llmConfig;
    protected String apiKey;
    protected String url;
    protected Integer maxRetries;
    private final SchemaGenerator schemaGenerator;

    public OpenAIResponse(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
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
     * 使用 Responses API 进行流式对话
     */
    @Override
    public Flux<Output> send(List<Message> memory, Class<?> outputFormat, List<Tool<?>> tools) {
        return Flux.create(sink -> {
            var requestBody = buildRequestBody(memory, outputFormat, tools, true);

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
                        var output = parseOutput(JSONUtil.parse(data), true, type);
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
    public Output query(List<Message> messages, Class<?> outputFormat) {
        var requestBody = buildRequestBody(messages, outputFormat, null, false);
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
            return parseOutput(responseJson, false, "response.completed");
        } catch (Exception e) {
            throw ErrorUtil.make("Query error: %s".formatted(e.getMessage()));
        }
    }

    /**
     * 批量查询（Responses API 暂不支持原生 batch，使用 Chat Completion batch API）
     */
    @Override
    public String batch(List<List<Message>> messageBatch, Class<?> outputFormat) {
        var jsonlBuilder = new StringBuilder();
        for (var i = 0; i < messageBatch.size(); i++) {
            var messages = messageBatch.get(i);
            var requestBody = buildRequestBody(messages, outputFormat, null, false);
            var requestJson = JSONUtil.parse(requestBody);
            requestJson.remove("stream");

            var requestNode = JSONUtil.create();
            requestNode.put("custom_id", "request-%d".formatted(i));
            requestNode.put("method", "POST");
            requestNode.put("url", "/v1/responses");
            requestNode.set("body", requestJson);

            jsonlBuilder.append(requestNode.toString()).append("\n");
        }

        var mediaType = MediaType.parse("application/jsonl");
        var fileBody = RequestBody.create(jsonlBuilder.toString().getBytes(), mediaType);
        var fileMultipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "batch.jsonl", fileBody)
                .addFormDataPart("purpose", "batch")
                .build();

        var fileRequest = new Request.Builder()
                .url("%s/files".formatted(this.url))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .post(fileMultipartBody)
                .build();

        String fileId;
        try (var fileResponse = HttpUtil.getClient().newCall(fileRequest).execute()) {
            if (!fileResponse.isSuccessful()) {
                var body = fileResponse.body();
                var bodyStr = body != null ? body.string() : "null";
                log.error(" batch file upload failed code={} body={} ", fileResponse.code(), bodyStr);
                throw ErrorUtil.make("File upload failed: %s".formatted(fileResponse.code()));
            }
            var fileResponseBody = fileResponse.body();
            if (fileResponseBody == null) {
                throw ErrorUtil.make("File upload response is null");
            }
            var fileResponseJson = JSONUtil.parse(fileResponseBody.string());
            var fileIdNode = fileResponseJson.path("id");
            if (fileIdNode.isMissingNode() || fileIdNode.isNull()) {
                throw ErrorUtil.make("File upload response missing id");
            }
            fileId = fileIdNode.asString();
        } catch (Exception e) {
            throw ErrorUtil.make("File upload error: %s".formatted(e.getMessage()));
        }

        var batchRequestBody = JSONUtil.create();
        batchRequestBody.put("input_file_id", fileId);
        batchRequestBody.put("endpoint", "/v1/responses");
        batchRequestBody.put("completion_window", "24h");

        var batchRequest = new Request.Builder()
                .url("%s/batches".formatted(this.url))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(batchRequestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (var batchResponse = HttpUtil.getClient().newCall(batchRequest).execute()) {
            if (!batchResponse.isSuccessful()) {
                var body = batchResponse.body();
                var bodyStr = body != null ? body.string() : "null";
                log.error(" batch creation failed code={} body={} ", batchResponse.code(), bodyStr);
                throw ErrorUtil.make("Batch creation failed: %s".formatted(batchResponse.code()));
            }
            var batchResponseBody = batchResponse.body();
            if (batchResponseBody == null) {
                throw ErrorUtil.make("Batch creation response is null");
            }
            var batchResponseJson = JSONUtil.parse(batchResponseBody.string());
            var batchIdNode = batchResponseJson.path("id");
            if (batchIdNode.isMissingNode() || batchIdNode.isNull()) {
                throw ErrorUtil.make("Batch creation response missing id");
            }
            return batchIdNode.asString();
        } catch (Exception e) {
            throw ErrorUtil.make("Batch creation error: %s".formatted(e.getMessage()));
        }
    }

    /**
     * 查询批量任务状态
     */
    @Override
    public BatchTaskInfo taskStatus(String taskId) {
        var request = new Request.Builder()
                .url("%s/batches/%s".formatted(this.url, taskId))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .addHeader("Content-Type", "application/json")
                .get()
                .build();

        try (var response = HttpUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                var body = response.body();
                var bodyStr = body != null ? body.string() : "null";
                log.error(" taskStatus failed code={} body={} ", response.code(), bodyStr);
                throw ErrorUtil.make("Task status query failed: %s".formatted(response.code()));
            }
            var responseBody = response.body();
            if (responseBody == null) {
                throw ErrorUtil.make("Task status response is null");
            }
            var responseJson = JSONUtil.parse(responseBody.string());

            var statusNode = responseJson.path("status");
            var apiStatus = statusNode.isMissingNode() || statusNode.isNull() ? "unknown" : statusNode.asText();
            var mappedStatus = switch (apiStatus) {
                case "completed" -> BatchTaskInfo.STATUS.DONE;
                case "failed" -> BatchTaskInfo.STATUS.ERROR;
                case "expired" -> BatchTaskInfo.STATUS.EXPIRED;
                case "cancelled" -> BatchTaskInfo.STATUS.CANCELLED;
                default -> BatchTaskInfo.STATUS.DOING;
            };

            var taskStatus = BatchTaskInfo.builder()
                    .id(responseJson.path("id").asText())
                    .status(mappedStatus)
                    .build();

            var outputFilesNode = responseJson.path("output_file_id");
            if (!outputFilesNode.isMissingNode() && !outputFilesNode.isNull()) {
                taskStatus.setSuccessResultId(outputFilesNode.asText());
            }

            var errorFilesNode = responseJson.path("error_file_id");
            if (!errorFilesNode.isMissingNode() && !errorFilesNode.isNull()) {
                taskStatus.setErrorResultId(errorFilesNode.asText());
            }

            return taskStatus;
        } catch (Exception e) {
            throw ErrorUtil.make("Task status query error: %s".formatted(e.getMessage()));
        }
    }

    /**
     * 获取批量任务结果
     */
    @Override
    public List<Output> taskResult(BatchTaskInfo task) {
        var downloadRequest = new Request.Builder()
                .url("%s/files/%s/content".formatted(this.url, task.getSuccessResultId()))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .get()
                .build();

        try (var response = HttpUtil.getClient().newCall(downloadRequest).execute()) {
            if (!response.isSuccessful()) {
                var body = response.body();
                var bodyStr = body != null ? body.string() : "null";
                log.error(" taskResult download failed code={} body={} ", response.code(), bodyStr);
                throw ErrorUtil.make("Task result download failed: %s".formatted(response.code()));
            }
            var responseBody = response.body();
            if (responseBody == null) {
                throw ErrorUtil.make("Task result response is null");
            }

            var content = responseBody.string();
            return parseOutputBatch(content);
        } catch (Exception e) {
            throw ErrorUtil.make("Task result download error: %s".formatted(e.getMessage()));
        }
    }

    /**
     * 构建请求体
     */
    private String buildRequestBody(List<Message> memory, Class<?> outputFormat, List<Tool<?>> tools, boolean stream) {
        var body = JSONUtil.create();
        body.put("model", llmType);
        body.put("stream", stream);

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
    private ArrayNode convertMessagesToInput(List<Message> memory, List<Tool<?>> tools) {
        var inputArray = JSONUtil.createArray();

        for (var item : memory) {
            switch (item.role) {
                case Message.ROLE.USER:
                    var userMessage = JSONUtil.create();
                    userMessage.put("type", "message");
                    userMessage.put("role", "user");

                    var userContent = buildContent(item);
                    userMessage.set("content", userContent);
                    inputArray.add(userMessage);
                    break;

                case Message.ROLE.ASSISTANT:
                    var assistantMessage = JSONUtil.create();
                    assistantMessage.put("type", "message");
                    assistantMessage.put("role", "assistant");
                    assistantMessage.put("content", item.text);
                    inputArray.add(assistantMessage);
                    break;

                case Message.ROLE.SYSTEM:
                    var systemMessage = JSONUtil.create();
                    systemMessage.put("type", "message");
                    systemMessage.put("role", "system");
                    systemMessage.put("content", item.text);
                    inputArray.add(systemMessage);
                    break;

                case Message.ROLE.TOOL:
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
    private JsonNode buildContent(Message item) {
        var result = JSONUtil.create();
        switch (item.type) {
            case Message.TYPE.TEXT:
                result.put("type", "input_text");
                result.put("text", item.text);
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
                    content.add(JSONUtil.create().put("type", "input_text").put("text", item.getText()));
                }
                return content;
            case Message.TYPE.AUDIO:
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
            case Message.TYPE.VIDEO:
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
     * 构建工具 JSON（Responses API 格式）
     */
    public ArrayNode buildToolsJson(List<Tool<?>> tools) {
        var toolsArray = JSONUtil.createArray();
        for (var tool : tools) {
            var toolNode = JSONUtil.create();
            toolNode.put("type", "function");

            var function = JSONUtil.create();
            function.put("name", tool.name);
            function.put("description", tool.description);
            function.set("parameters", JSONUtil.parse(schemaGenerator.generateSchema(tool.paramType).toString()));

            toolNode.set("function", function);
            toolsArray.add(toolNode);
        }
        return toolsArray;
    }

    /**
     * 解析批量输出
     */
    private List<Output> parseOutputBatch(String data) {
        var outputs = new ArrayList<Output>();
        var lines = data.split("\n");
        for (var line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                var jsonLine = JSONUtil.parse(line);
                var bodyNode = jsonLine.path("response").path("body");
                if (!bodyNode.isMissingNode() && !bodyNode.isNull()) {
                    var output = parseOutput((ObjectNode) bodyNode, false, "response.completed");
                    if (output != null) {
                        outputs.add(output);
                    }
                }
            }
        }
        return outputs;
    }

    /**
     * 解析响应
     */
    private Output parseOutput(ObjectNode data, boolean isStream, String eventType) {
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