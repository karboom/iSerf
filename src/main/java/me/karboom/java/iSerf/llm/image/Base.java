package me.karboom.java.iSerf.llm.image;

import me.karboom.java.iSerf.llm.text.BatchTaskInfo;
import me.karboom.java.iSerf.llm.text.Output;
import me.karboom.java.iSerf.memory.Item;
import me.karboom.java.iSerf.tool.Tool;
import reactor.core.publisher.Flux;

import java.util.List;
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


}