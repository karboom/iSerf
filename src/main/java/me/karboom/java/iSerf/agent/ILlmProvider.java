package me.karboom.java.iSerf.agent;

import me.karboom.java.iSerf.llm.text.Base;
import me.karboom.java.iSerf.tool.Tool;

import java.util.List;

/**
 * 通过自定义接口实现llm的动态切换
 */
public interface ILlmProvider {

    public Base get(List<Tool> tools, Class<?> outputFormat, Integer retryTimes);
}
