package me.karboom.java.iSerf.llm.embedding;

import cn.hutool.core.util.StrUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.util.JSONUtil;
import okhttp3.*;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Todo 虚拟线程引入
 */
@Slf4j
public class Dashscope extends Base {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();
    
    // Dashscope API base URL
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/";

    Dashscope(String type, Map<String, Object> config, String apiKey) {
        super(type, config, apiKey);
    }

    @Override
    @SneakyThrows
    public Output convert(List<Input> input) {
        var model = this.type; // type is actually the model name
        var apiKey = this.apiKey;
        var url = buildUrl(model);
        
        // 构建请求体
        var requestBody = buildRequestBody(input, model);
        var requestJson = JSONUtil.stringify(requestBody);
        
        log.debug("<convert> request body: {}", requestJson);
        
        var request = new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestJson, JSON))
            .build();
            
        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            
            var responseBody = response.body().string();
            log.debug("<convert> response body: {}", responseBody);
            
            var responseJson = JSONUtil.parse(responseBody);
            return parseResponse(responseJson);
        }
    }

    @Override
    public String batchConvert(List<Input> input) {
        // Dashscope API 不直接支持批量异步任务，返回空字符串表示不支持
        return "";
    }
    
    private String buildUrl(String model) {
        if (model.startsWith("text-embedding")) {
            return BASE_URL + "text-embedding/text-embedding";
        } else {
            return BASE_URL + "multimodal-embedding/multimodal-embedding";
        }
    }
    
    private Object buildRequestBody(List<Input> inputs, String model) {
        var requestBody = new java.util.HashMap<String, Object>();
        
        // 使用传入的model作为模型名称
        requestBody.put("model", model);
        
        if (model.startsWith("text-embedding")) {
            // 文本向量接口
            var inputList = new ArrayList<String>();
            for (var input : inputs) {
                if (StrUtil.isNotBlank(input.getText())) {
                    inputList.add(input.getText());
                }
            }
            var inputObject = new java.util.HashMap<String, Object>();
            inputObject.put("texts", inputList);
            requestBody.put("input", inputObject);
        } else {
            // 多模态向量接口
            var inputList = new ArrayList<Map<String, String>>();
            for (var input : inputs) {
                var inputMap = new java.util.HashMap<String, String>();
                if (StrUtil.isNotBlank(input.getText())) {
                    inputMap.put("text", input.getText());
                }
                if (StrUtil.isNotBlank(input.getImage())) {
                    inputMap.put("image", input.getImage());
                }
                if (StrUtil.isNotBlank(input.getVideo())) {
                    inputMap.put("video", input.getVideo());
                }
                if (!inputMap.isEmpty()) {
                    inputList.add(inputMap);
                }
            }
            var inputObject = new java.util.HashMap<String, Object>();
            inputObject.put("contents", inputList);
            requestBody.put("input", inputObject);
        }
        
        return requestBody;
    }
    
    private Output parseResponse(ObjectNode responseJson) {
        var usageBuilder = Output.Usage.builder();
        var resultList = new ArrayList<Output.Result>();
        
        // 解析usage
        var usageNode = responseJson.path("usage");
        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
            var inputTokens = usageNode.path("input_tokens");
            if (!inputTokens.isMissingNode() && !inputTokens.isNull()) {
                usageBuilder.inputTokens(inputTokens.asInt());
            }
        }
        
        // 解析结果
        var outputNode = responseJson.path("output");
        if (!outputNode.isMissingNode() && !outputNode.isNull()) {
            var embeddingsNode = outputNode.path("embeddings");
            if (!embeddingsNode.isMissingNode() && !embeddingsNode.isNull() && embeddingsNode.isArray()) {
                for (var embeddingElement : embeddingsNode) {
                    var resultBuilder = Output.Result.builder();
                    
                    var embeddingNode = embeddingElement.path("embedding");
                    if (!embeddingNode.isMissingNode() && !embeddingNode.isNull() && embeddingNode.isArray()) {
                        var embeddingList = new ArrayList<Double>();
                        for (var element : embeddingNode) {
                            embeddingList.add(element.asDouble());
                        }
                        resultBuilder.embedding(embeddingList);
                    }
                    
                    var objectType = embeddingElement.path("object_type");
                    if (!objectType.isMissingNode() && !objectType.isNull()) {
                        resultBuilder.type(objectType.asText());
                    }
                    
                    resultList.add(resultBuilder.build());
                }
            }
        }
        
        return Output.builder()
            .usage(usageBuilder.build())
            .result(resultList)
            .build();
    }
}
