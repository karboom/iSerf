package me.karboom.java.iSlogger.llm.text;

import com.github.victools.jsonschema.generator.*;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.util.JSONUtil;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI
 */
@Slf4j
public class OpenAI extends BaseLLM {
    private final SchemaGenerator schemaGenerator;
    private final OkHttpClient httpClient;

    public OpenAI(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        super(llmType, llmConfig, apiKey, url, maxRetries);

        var configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        var config = configBuilder.forFields().withRequiredCheck(fieldScope -> true);
        this.schemaGenerator = new SchemaGenerator(configBuilder.build());

        var builder = OpenAIOkHttpClient.builder()
                .apiKey(this.apiKey)
                .baseUrl(this.url)
                .timeout(Duration.ofSeconds(60));

        if (this.maxRetries != null) {
            builder.maxRetries(this.maxRetries);
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(60))
                .connectionPool(new ConnectionPool(1000, 5, TimeUnit.MINUTES))
                .build();
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
    public Flux<OutputBO> send(List<Item> memory, Class<?> outputFormat, List<Tool> tools) {
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
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    System.out.println("source " + data);
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
//                    sink.complete();
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    sink.error(t);
                }
            };

            var factory = EventSources.createFactory(httpClient);
            var eventSource = factory.newEventSource(request, listener);

//            sink.onDispose(eventSource::cancel);
        });
    }

    @Override
    public OutputBO query(List<Item> messages, Class<?> outputFormat) {
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

        try (var response = httpClient.newCall(request).execute()) {
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
    public String batch(List<List<Item>> messageBatch, Class<?> outputFormat) {
        // 构建批量请求的JSONL格式数据
        var jsonlBuilder = new StringBuilder();
        for (var i = 0; i < messageBatch.size(); i++) {
            var messages = messageBatch.get(i);
            // 复用现有的buildRequestBody方法，但需要调整参数
            var requestBody = buildRequestBody(messages, outputFormat, null);
            // 将stream设置为false，因为batch API不支持流式响应
            var requestJson = JSONUtil.parse(requestBody);
            requestJson.put("stream", false);
            
            var requestNode = JSONUtil.create();
            requestNode.put("custom_id", "request-%d".formatted(i));
            requestNode.put("method", "POST");
            requestNode.put("url", "/v1/chat/completions");
//            requestNode.put("url", "/v1/chat/ds-test");
            requestNode.set("body", requestJson);
            
            jsonlBuilder.append(requestNode.toString()).append("\n");
        }

        // 第一步：上传文件到 /v1/files
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
        try (var fileResponse = httpClient.newCall(fileRequest).execute()) {
            if (!fileResponse.isSuccessful()) {
                throw new RuntimeException("File upload failed: " + fileResponse.code());
            }
            var fileResponseBody = fileResponse.body();
            if (fileResponseBody == null) {
                throw new RuntimeException("File upload response is null");
            }
            var fileResponseJson = JSONUtil.parse(fileResponseBody.string());
            var fileIdNode = fileResponseJson.path("id");
            if (fileIdNode.isMissingNode() || fileIdNode.isNull()) {
                throw new RuntimeException("File upload response missing id");
            }
            fileId = fileIdNode.asString();
        } catch (Exception e) {
            throw new RuntimeException("File upload error", e);
        }

        // 第二步：使用file_id创建batch任务
        var batchRequestBody = JSONUtil.create();
        batchRequestBody.put("input_file_id", fileId);
        batchRequestBody.put("endpoint", "/v1/chat/completions");
//        batchRequestBody.put("endpoint", "/v1/chat/ds-test");
        batchRequestBody.put("completion_window", "24h");

        var batchRequest = new Request.Builder()
                .url("%s/batches".formatted(this.url))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(batchRequestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (var batchResponse = httpClient.newCall(batchRequest).execute()) {
            if (!batchResponse.isSuccessful()) {
                throw new RuntimeException("Batch creation failed: " + batchResponse.code());
            }
            var batchResponseBody = batchResponse.body();
            if (batchResponseBody == null) {
                throw new RuntimeException("Batch creation response is null");
            }
            var batchResponseJson = JSONUtil.parse(batchResponseBody.string());
            var batchIdNode = batchResponseJson.path("id");
            if (batchIdNode.isMissingNode() || batchIdNode.isNull()) {
                throw new RuntimeException("Batch creation response missing id");
            }
            return batchIdNode.asString();
        } catch (Exception e) {
            throw new RuntimeException("Batch creation error", e);
        }
    }

    @Override
    public TaskStatusBO taskStatus(String taskId) {
        var request = new Request.Builder()
                .url("%s/batches/%s".formatted(this.url, taskId))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .addHeader("Content-Type", "application/json")
                .get()
                .build();

        try (var response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Task status query failed: " + response.code());
            }
            var responseBody = response.body();
            if (responseBody == null) {
                throw new RuntimeException("Task status response is null");
            }
            var responseJson = JSONUtil.parse(responseBody.string());
            
            var statusNode = responseJson.path("status");
            var apiStatus = statusNode.isMissingNode() || statusNode.isNull() ? "unknown" : statusNode.asText();
            var mappedStatus = switch (apiStatus) {
                case "completed" -> TaskStatusBO.STATUS.DONE;
                case "failed" -> TaskStatusBO.STATUS.ERROR;
                case "expired" -> TaskStatusBO.STATUS.EXPIRED;
                case "cancelled" -> TaskStatusBO.STATUS.CANCELLED;
                default -> TaskStatusBO.STATUS.DOING;
            };
            
            var taskStatus = TaskStatusBO.builder()
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
            throw new RuntimeException("Task status query error", e);
        }
    }

    @Override
    public List<OutputBO> taskResult(TaskStatusBO task) {
        // 下载输出文件
        var downloadRequest = new Request.Builder()
                .url("%s/files/%s/content".formatted(this.url, task.getSuccessResultId()))
                .addHeader("Authorization", "Bearer %s".formatted(this.apiKey))
                .get()
                .build();

        try (var response = httpClient.newCall(downloadRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Task result download failed: " + response.code());
            }
            var responseBody = response.body();
            if (responseBody == null) {
                throw new RuntimeException("Task result response is null");
            }
            
            var content = responseBody.string();
            return parseOutputBatch(content);
        } catch (Exception e) {
            throw new RuntimeException("Task result download error", e);
        }
    }
    

    private String buildRequestBody(List<Item> memory, Class<?> outputFormat, List<Tool> tools) {
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
                case Item.ROLE.USER:
                    var userMessage = JSONUtil.create();
                    userMessage.put("role", "user");

                    switch (item.type) {
                        case Item.TYPE.TEXT:
                            userMessage.put("content", item.text);
                            break;
                        case Item.TYPE.IMAGE:
                            var content = JSONUtil.createArray();
                            if (item.images != null) {
                                for (var image : item.images) {
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

                        case Item.TYPE.AUDIO:
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

                        case Item.TYPE.VIDEO:
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

                case Item.ROLE.ASSISTANT:
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

                case Item.ROLE.SYSTEM:
                    messagesArray.add(JSONUtil.create()
                            .put("role", "system")
                            .put("content", item.text));
                    break;

                case Item.ROLE.TOOL:
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

    private ArrayNode buildToolsJson(List<Tool> tools) {
        var toolsArray = JSONUtil.createArray();
        for (var tool : tools) {
            var toolNode = JSONUtil.create();
            toolNode.put("type", "function");

            var function = JSONUtil.create();
            function.put("name", tool.name);
            function.put("description", tool.description);

            var parameters = JSONUtil.create();
            parameters.put("type", "object");

            if (tool.parameters != null && !tool.parameters.isEmpty()) {
                var properties = JSONUtil.create();
                var required = JSONUtil.createArray();

                for (var param : tool.parameters) {
                    var paramNode = JSONUtil.create();
                    paramNode.put("type", param.type);
                    if (param.description != null) {
                        paramNode.put("description", param.description);
                    }
                    properties.set(param.name, paramNode);

                    if (param.required != null && param.required) {
                        required.add(param.name);
                    }
                }

                parameters.set("properties", properties);
                if (required.size() > 0) {
                    parameters.set("required", required);
                }
            }

            parameters.put("additionalProperties", false);
            function.set("parameters", parameters);
            toolNode.set("function", function);
            toolsArray.add(toolNode);
        }
        return toolsArray;
    }

    private List<OutputBO> parseOutputBatch(String data) {
        var outputs = new ArrayList<OutputBO>();
        var lines = data.split("\n");
        for (var line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                var jsonLine = JSONUtil.parse(line);
                var bodyNode = jsonLine.path("response").path("body");
                if (!bodyNode.isMissingNode() && !bodyNode.isNull()) {
                    var output = parseOutput((ObjectNode) bodyNode, false);
                    if (output != null) {
                        outputs.add(output);
                    }
                }
            }
        }
        return outputs;
    }

    /**
     * 解析响应body对象
     * @param data
     * @param isStream
     * @return
     */
    private OutputBO parseOutput(ObjectNode data, Boolean isStream) {
        var output = new OutputBO();

        var idNode = data.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            output.setId(idNode.asString());
        }
        output.type = "chat.completion.chunk";
        output.isDelta = isStream;

        var choicesNode = data.path("choices");
        if (!choicesNode.isEmpty()) {
            var outputChoices = new ArrayList<OutputBO.Choice>();
            for (var choiceNode : choicesNode) {
                var outputChoice = new OutputBO.Choice();

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
                        var toolCalls = new ArrayList<OutputBO.ToolCall>();

                        for (var toolCall : toolCallsNode) {
                            var functionNode = toolCall.path("function");
                            var funcIdNode = toolCall.path("id");
                            var indexNode = toolCall.path("index");

                            var nameNode = functionNode.path("name");
                            var argNode = functionNode.path("arguments");


                            var object = OutputBO.ToolCall.builder().id(funcIdNode.asString()).index(indexNode.asInt()).arguments(argNode.asString()).build();

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
            var usage = OutputBO.Usage.builder()
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
