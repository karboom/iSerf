package me.karboom.java.iSerf.llm.rerank;

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
 * 百炼平台
 * 文档地址：https://bailian.console.aliyun.com/cn-beijing/?tab=api#/api/?type=model&url=2780056
 */
@Slf4j
public class Dashscope extends Base {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();

    // Dashscope Rerank API base URL
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    Dashscope(String llmType, Map<String, Object> llmConfig, String apiKey, String url) {
        super(llmType, llmConfig, apiKey, url);
    }

    @Override
    @SneakyThrows
    public Output rank(Input input) {
        var apiKey = this.apiKey;

        // 构建请求体
        var requestBody = buildRequestBody(input);
        var requestJson = JSONUtil.stringify(requestBody);

        log.debug("<rank> request body: {}", requestJson);

        var request = new Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestJson, JSON))
            .build();

        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            var responseBody = response.body().string();
            log.debug("<rank> response body: {}", responseBody);

            var responseJson = JSONUtil.parse(responseBody);
            return parseResponse(responseJson, input);
        }
    }

    private Object buildRequestBody(Input input) {
        var requestBody = new java.util.HashMap<String, Object>();

        // 设置 model
        if (StrUtil.isNotBlank(this.llmType)) {
            requestBody.put("model", this.llmType);
        } else {
            requestBody.put("model", "gte-rerank");
        }

        // 设置 input 对象
        var inputObject = new java.util.HashMap<String, Object>();

        // 设置 query
        if (StrUtil.isNotBlank(input.getQuery())) {
            inputObject.put("query", input.getQuery());
        }

        // 判断是否为多模态模型 qwen3-vl-rerank
        var isMultimodal = this.llmType != null && this.llmType.contains("vl");

        if (isMultimodal) {
            // 多模态模型：documents 为对象数组，包含 text、image、video 字段
            var documents = new ArrayList<Map<String, String>>();
            if (input.getDocuments() != null) {
                for (var doc : input.getDocuments()) {
                    var docMap = new java.util.HashMap<String, String>();
                    if (StrUtil.isNotBlank(doc.getContent())) {
                        var type = doc.getType();
                        if ("image".equals(type)) {
                            docMap.put("image", doc.getContent());
                        } else if ("video".equals(type)) {
                            docMap.put("video", doc.getContent());
                        } else {
                            docMap.put("text", doc.getContent());
                        }
                    }
                    if (!docMap.isEmpty()) {
                        documents.add(docMap);
                    }
                }
            }
            inputObject.put("documents", documents);

            // 设置 parameters 对象（多模态模型需要）
            var parameters = new java.util.HashMap<String, Object>();
            parameters.put("return_documents", true);
            if (input.getTopN() != null) {
                parameters.put("top_n", input.getTopN());
            }
            parameters.put("fps", 1.0);
            requestBody.put("parameters", parameters);
        } else {
            // 普通文本模型：documents 为字符串数组
            var documents = new ArrayList<String>();
            if (input.getDocuments() != null) {
                for (var doc : input.getDocuments()) {
                    if (StrUtil.isNotBlank(doc.getContent())) {
                        documents.add(doc.getContent());
                    }
                }
            }
            inputObject.put("documents", documents);
        }

        requestBody.put("input", inputObject);

        return requestBody;
    }

    private Output parseResponse(ObjectNode responseJson, Input input) {
        var resultList = new ArrayList<Output.Result>();

        // 解析结果
        var outputNode = responseJson.path("output");
        if (!outputNode.isMissingNode() && !outputNode.isNull()) {
            var resultsNode = outputNode.path("results");
            if (!resultsNode.isMissingNode() && !resultsNode.isNull() && resultsNode.isArray()) {
                for (var resultElement : resultsNode) {
                    var resultBuilder = Output.Result.builder();

                    var indexNode = resultElement.path("index");
                    if (!indexNode.isMissingNode() && !indexNode.isNull()) {
                        var index = indexNode.asInt();
                        resultBuilder.index(index);

                        // 根据 index 从输入中获取对应的 document
                        if (input.getDocuments() != null && index < input.getDocuments().size()) {
                            resultBuilder.input(input.getDocuments().get(index));
                        }
                    }

                    // API 返回的字段名是 relevance_score
                    var scoreNode = resultElement.path("relevance_score");
                    if (!scoreNode.isMissingNode() && !scoreNode.isNull()) {
                        resultBuilder.score(String.valueOf(scoreNode.asDouble()));
                    }

                    resultList.add(resultBuilder.build());
                }
            }
        }

        return Output.builder()
            .list(resultList)
            .build();
    }
}