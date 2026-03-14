package me.karboom.java.iSerf.llm.video;

import java.util.Map;

/**
 *
 */
public class Volcengine extends Base{
    Volcengine(String llmType, Map<String, Object> llmConfig, String apiKey, String url) {
        super(llmType, llmConfig, apiKey, url);
    }

    @Override
    String generate() {
        return "";
    }

    @Override
    TaskInfo getTaskInfo() {
        return null;
    }
}
