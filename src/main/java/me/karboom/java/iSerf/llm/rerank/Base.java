package me.karboom.java.iSerf.llm.rerank;

import java.util.Map;

abstract public class Base {
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

    public abstract Output rank(Input input);
}
