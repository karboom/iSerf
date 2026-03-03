package me.karboom.java.iSerf.llm.video;

import java.util.Map;

public abstract class Base {
    public String llmType;
    public Map<String, Object> llmConfig;
    protected String apiKey;
    protected String url;
    protected Integer maxRetries;

    Base(String llmType, Map<String, Object> llmConfig, String apiKey, String url) {
        this.llmType = llmType;
        this.llmConfig = llmConfig;
        this.apiKey = apiKey;
        this.url = url;
    }


    /**
     * 获取视频生成任务ID
     */
    abstract String generate();

    /**
     * 查询任务状态
     */
    abstract TaskInfo getTaskInfo();
}