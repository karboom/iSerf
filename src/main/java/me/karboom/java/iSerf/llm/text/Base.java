package me.karboom.java.iSerf.llm.text;

import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.agent.Message;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public abstract class Base {
    public String llmType;
    public Map<String, Object> llmConfig;
    protected String apiKey;
    protected String url;
    protected Integer maxRetries;

    Base(String llmType, Map<String, Object> llmConfig, String apiKey, String url, Integer maxRetries) {
        this.llmType = llmType;
        this.llmConfig = llmConfig;
        this.apiKey = apiKey;
        this.url = url;
        this.maxRetries = maxRetries;
    }

    /**
     * 用于持续交流
     */
    abstract public Flux<Output> send(List<Message> messages, Class<?> outputFormat, List<Tool> tools);

    /**
     * 用于工具查询
     */
    abstract public Output query(List<Message> messages, Class<?> outputFormat);

    /**
     * 批量工具查询
     * @return 批量任务ID
     */
    abstract public String batch(List<List<Message>> messageBatch, Class<?> outputFormat);

    /**
     * 查看批量任务状态
     */
    abstract public BatchTaskInfo taskStatus(String taskId);

    /**
     * 查看批量任务结果
     */
    abstract public List<Output> taskResult(BatchTaskInfo task);
}