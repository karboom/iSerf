package me.karboom.java.iSlogger.llm.text;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.memory.Item;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI
 */
public class OpenAI extends BaseLLM {
    private OpenAIClient client;

    public OpenAI(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        super(llmType, llmConfig, apiKey, url, maxRetries);
        
        // 初始化 OpenAI 客户端
        var builder = OpenAIOkHttpClient.builder()
                .apiKey(this.apiKey)
                .baseUrl(this.url)
                ;

        // 如果提供了最大重试次数，设置重试次数
        if (this.maxRetries != null) {
            builder.maxRetries(this.maxRetries);
        }

        this.client = builder.build();
    }

    /**
     * 发送消息到 OpenAI API
     *
     * @param messages     消息列表
     * @param outputFormat 输出格式类
     * @param tools        工具列表
     * @return 流式响应
     */
    @Override
    public Flux<ChatCompletionChunk> send(List<Item> messages, Class<?> outputFormat, List<Tool> tools) {
        // 构建请求参数
        var paramsBuilder = ChatCompletionCreateParams.builder()
                .model(llmType);

        if (outputFormat != null) {
            paramsBuilder.responseFormat(outputFormat);
        }

        // 添加工具调用
        if (tools != null && !tools.isEmpty()) {
            for (var tool : tools) {
                // 构建函数参数
                var functionParamsBuilder = FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"));

                // 添加参数属性和提取必需参数列表
                if (tool.parameters != null && !tool.parameters.isEmpty()) {
                    var propertiesMap = new java.util.HashMap<String, Map<String, String>>();
                    var requiredList = new ArrayList<String>();

                    for (var param : tool.parameters) {
                        // 构建参数属性
                        var paramMap = new java.util.HashMap<String, String>();
                        paramMap.put("type", param.type);
                        if (param.description != null) {
                            paramMap.put("description", param.description);
                        }
                        propertiesMap.put(param.name, paramMap);

                        // 提取必需参数
                        if (param.required != null && param.required) {
                            requiredList.add(param.name);
                        }
                    }

                    // 添加参数属性
                    functionParamsBuilder.putAdditionalProperty(
                            "properties", 
                            JsonValue.from(propertiesMap)
                    );

                    // 添加必需参数列表
                    if (!requiredList.isEmpty()) {
                        functionParamsBuilder.putAdditionalProperty(
                                "required", 
                                JsonValue.from(requiredList)
                        );
                    }
                }

                functionParamsBuilder.putAdditionalProperty(
                        "additionalProperties", 
                        JsonValue.from(false)
                );

                // 构建函数定义
                var functionDefinition = FunctionDefinition.builder()
                        .name(tool.name)
                        .description(tool.description)
                        .parameters(functionParamsBuilder.build())
                        .build();

                // 添加工具到请求参数
                paramsBuilder.addTool(
                        ChatCompletionFunctionTool.builder()
                                .function(functionDefinition)
                                .build()
                );
            }
        }

        // 添加消息
        for (var item : messages) {
            ChatCompletionMessageParam param;
            switch (item.role) {
                case "user":
                    paramsBuilder.addUserMessage(item.text);
                    break;
                case "assistant":
//                    paramsBuilder.addAssistantMessage(item.text);
                    paramsBuilder.addMessage(ChatCompletionAssistantMessageParam.builder()
                                    .content(item.text)
                            .build());
                    break;
                case "system":
                    paramsBuilder.addSystemMessage(item.text);
                    break;
                case "tool":
                    paramsBuilder.addMessage(ChatCompletionToolMessageParam.builder()

                            .build());
                    break;
                default:
                    break;
            }
        }

        // 从配置中读取并设置参数
        if (llmConfig.containsKey("temperature")) {
            paramsBuilder.temperature(((Number) llmConfig.get("temperature")).doubleValue());
        }

        if (llmConfig.containsKey("max_tokens")) {
            paramsBuilder.maxCompletionTokens(((Number) llmConfig.get("max_tokens")).intValue());
        }

        if (llmConfig.containsKey("top_p")) {
            paramsBuilder.topP(((Number) llmConfig.get("top_p")).doubleValue());
        }

        var params = paramsBuilder.build();

        // 发送流式请求
        var res =  client.async().chat().completions().createStreaming(params);

        return  Flux.create(sink -> {
            res.subscribe(sink::next)
                    .onCompleteFuture()
                    .whenComplete((result, error) -> {
                        if (error == null) {
                            sink.complete();
                        } else {
                            sink.error(error);
                        }
                    });
        });
    }

}
