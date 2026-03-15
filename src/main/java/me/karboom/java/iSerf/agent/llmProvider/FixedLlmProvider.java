package me.karboom.java.iSerf.agent.llmProvider;

import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.agent.tool.Tool;

import java.util.List;

public class FixedLlmProvider implements ILlmProvider {
    private final IText llm;

    public FixedLlmProvider(IText llm) {
        this.llm = llm;
    }
    @Override
    public IText get(List<Tool<?>> tools, Class<?> outputFormat, Integer retryTimes) {
        return this.llm;
    }
}
