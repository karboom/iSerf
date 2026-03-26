package me.karboom.java.iSerf.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSerf.agent.tool.CallResult;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Message {
    static public class TYPE {
        public static final String TEXT = "TEXT";
        public static final String IMAGE = "IMAGE";
        public static final String VIDEO = "VIDEO";
        public static final String AUDIO = "AUDIO";
        public static final String TOOL_CALLS = "TOOL_CALLS";
        public static final String MIXED = "MIXED";
        public static final String CUSTOM = "CUSTOM";
        public static final String TIP = "TIP";
        public static final String THINKING = "THINKING";
        public static final String ERROR = "ERROR";
    }

    static public class ROLE {
        public static final String ASSISTANT = "ASSISTANT";
        public static final String USER = "USER";
        public static final String SYSTEM = "SYSTEM";
        public static final String TOOL = "TOOL";
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class ToolCall {
        public String id;
        public String name;
        public HashMap<String, Object> arguments;
        public CallResult result;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    static public class Usage {
        public Integer total;

        public Integer promptTotal;

        public Integer completionTotal;
        public Integer completionThinking;
    }

    public String id;

    public String agentId;
    public String userId;

    public String role;
    public String type;

    public String text;

    /**
     * 图片列表，支持 文件、base64、http连接
     */
    public List<String> images;

    /**
     * 视频地址
     */
    public String video;
    /**
     * 音频地址
     */
    public String audio;
    /**
     * 视频帧率
     */
    public String fps;

    /**
     * 工具调用信息
     */
    public List<ToolCall> toolCalls;

    /**
     * 多个消息混合
     */
    public List<Message> mixed;

    public ObjectNode custom;

    /**
     * 是否为流式输出的片段
     */
    public Integer isSegment;

    /**
     * 格式化之后的输出对象
     */
    public Object formatted;

    /**
     * 使用量情况
     */
    public Usage usage;

    /**
     * 重要程度
     */
    public String importance;

    /**
     * 是否遗忘
     */
    public Integer isForgotten;

    /**
     * 事件ID
     */
    public String eventId;
}
