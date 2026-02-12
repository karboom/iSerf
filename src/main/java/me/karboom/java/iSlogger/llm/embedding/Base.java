package me.karboom.java.iSlogger.llm.embedding;


import java.util.List;
import java.util.Map;

public abstract class Base {
    protected final String type;
    protected final Map<String, Object> config;
    protected final String apiKey;

    Base(String type, Map<String, Object> config, String apiKey) {
        this.type = type;
        this.config = config;
        this.apiKey = apiKey;
    }
    abstract public Output convert(List<Input> input);


    /**
     * 批量装换向量
     * @param input
     * @return 异步任务ID
     */
    abstract public String batchConvert(List<Input> input);
}
