package me.karboom.java.iSerf.agent;

import me.karboom.java.iSerf.llm.text.Base;
import me.karboom.java.iSerf.tool.Tool;

import java.util.List;

public class FixedLlmProvider implements ILlmProvider {
    private final Base llm;

    public FixedLlmProvider(Base llm) {
        this.llm = llm;
    }
    @Override
    public Base get(List<Tool> tools, Class<?> outputFormat, Integer retryTimes) {
        return this.llm;
    }
}
