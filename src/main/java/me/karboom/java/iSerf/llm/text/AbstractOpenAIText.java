package me.karboom.java.iSerf.llm.text;

import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.util.ErrorUtil;
import me.karboom.java.iSerf.util.HttpUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import okhttp3.*;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 API 的抽象基类
 * 提取 OpenAI / OpenAIResponse 的公共逻辑：batch、taskStatus、taskResult、buildToolsJson、parseOutputBatch
 */
public abstract class AbstractOpenAIText implements IText {

    public String llmType;
    public Map<String, Object> llmConfig;
    protected String apiKey;
    protected String url;
    protected Integer maxRetries;
    protected final SchemaGenerator schemaGenerator;

    public AbstractOpenAIText(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        this.llmType = llmType;
        this.llmConfig = llmConfig;
        this.apiKey = apiKey;
        this.url = url;
        this.maxRetries = maxRetries;

        var configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED));
        configBuilder.forFields().withRequiredCheck(fieldScope -> true);
        this.schemaGenerator = new SchemaGenerator(configBuilder.build());
    }

    // region abstract methods

    /**
     * 构建请求体
     */
    protected abstract String buildRequestBody(List<Message> messages, Class<?> outputFormat, List<Tool<?>> tools);

    /**
     * 解析响应
     */
    protected abstract Output parseOutput(ObjectNode data, boolean isStream);

    /**
     * Chat 接口路径
     */
    protected abstract String chatEndpoint();

    /**
     * Batch 接口路径（用于 batch JSONL 中的 url 字段）
     */
    protected abstract String batchEndpoint();

    // endregion

    // region batch api

    @Override
    public String batch(List<List<Message>> messageBatch, Class<?> outputFormat) {
        var jsonlBuilder = new StringBuilder();
        for (var i = 0; i < messageBatch.size(); i++) {
            var messages = messageBatch.get(i);
            var requestBody = buildRequestBody(messages, outputFormat, null);
            var requestJson = JSONUtil.parse(requestBody);
            requestJson.put("stream", false);

            var requestNode = JSONUtil.create();
            requestNode.put("custom_id", "request-%d".formatted(i));
            requestNode.put("method", "POST");
            requestNode.put("url", batchEndpoint());
            requestNode.set("body", requestJson);

            jsonlBuilder.append(requestNode.toString()).append("\n");
        }

        // 第一步：上传文件
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
                throw ErrorUtil.make("File upload failed: %s body: %s".formatted(fileResponse.code(), bodyStr));
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

        // 第二步：创建 batch 任务
        var batchRequestBody = JSONUtil.create();
        batchRequestBody.put("input_file_id", fileId);
        batchRequestBody.put("endpoint", batchEndpoint());
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
                throw ErrorUtil.make("Batch creation failed: %s body: %s".formatted(batchResponse.code(), bodyStr));
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
                throw ErrorUtil.make("Task status query failed: %s body: %s".formatted(response.code(), bodyStr));
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
                throw ErrorUtil.make("Task result download failed: %s body: %s".formatted(response.code(), bodyStr));
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

    // endregion

    // region common utils

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

    protected List<Output> parseOutputBatch(String data) {
        var outputs = new ArrayList<Output>();
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

    // endregion
}
