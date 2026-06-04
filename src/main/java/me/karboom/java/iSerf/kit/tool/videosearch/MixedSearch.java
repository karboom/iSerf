package me.karboom.java.iSerf.kit.tool.videosearch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.util.JSONUtil;

/**
 * 混合结构化搜索和向量匹配的搜索
 */
@Slf4j
public class MixedSearch implements FunctionWrapper<MixedSearch.Parameter> {

    public static String description = "当用户需要查询特定画面的时候，使用这个工具";

    public static class Parameter {
        @JsonPropertyDescription("结构化查询")
        @JsonProperty(required = true)
        public String sql;

        @JsonPropertyDescription("用于向量匹配的文本描述")
        @JsonProperty(required = true)
        public String textForVectorMatch;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var query = params.sql;
        var text = params.textForVectorMatch;

        log.debug("mixedSearch query: {}, text: {}", query, text);

        var result = """
                {
                    "query": "%s",
                    "text": "%s"
                }
                """.formatted(query, text);

        return CallResult.builder()
                .direct(JSONUtil.parse(result))
                .build();
    }
}