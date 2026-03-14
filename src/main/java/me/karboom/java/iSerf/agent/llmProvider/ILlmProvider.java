package me.karboom.java.iSerf.agent.llmProvider;

import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.agent.tool.Tool;

import java.util.List;

/**
 * 通过自定义接口实现 llm 的动态切换
 */
public interface ILlmProvider {

    IText get(List<Tool> tools, Class<?> outputFormat, Integer retryTimes);
}
