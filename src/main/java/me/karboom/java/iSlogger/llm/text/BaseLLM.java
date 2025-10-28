package me.karboom.java.iSlogger.llm.text;

import com.openai.models.chat.completions.ChatCompletionChunk;
import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.memory.Item;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public abstract class BaseLLM {
    public String llmType;
    protected Map<String, Object> llmConfig;
    protected String apiKey;
    protected String url;
    protected Integer maxRetries;

    BaseLLM(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        this.llmType = llmType;
        this.llmConfig = llmConfig;
        this.apiKey = apiKey;
        this.url = url;
        this.maxRetries = maxRetries;
    }

    abstract public Flux<ChatCompletionChunk> send(List<Item> messages, Class<?> outputFormat, List<Tool> tools);
}