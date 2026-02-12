package me.karboom.java.iSlogger.llm.text;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OutputBO {
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    static public class ToolCall {
        public Integer index;
        public String id;

        public String name;
        /**
         * 调用参数 JSON 字符串
         */
        public String arguments;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    static public class Choice {
        /**
         * 文本输出
         */
        public String text;
        /**
         * 思考内容
         */
        public String thinking;
        /**
         * 工具调用
         */
        public List<ToolCall> toolCall;
        /**
         * 终止原因
         */
        public String finishReason;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    static public class Usage {
        public Integer promptTokens;
        public Integer completionTokens;
        public Integer thinkingTokens;
        public Integer totalTokens;
    }

    public String id;

    public String type;

    public List<Choice> choices;

    public Usage usage;

    /**
     * 是否为增量内容
     */
    public Boolean isDelta;

    public String error;
}
