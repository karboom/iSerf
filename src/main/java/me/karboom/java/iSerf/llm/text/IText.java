package me.karboom.java.iSerf.llm.text;

import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.agent.AgentMessage;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 文本大模型接口
 */
public interface IText {
    /**
     * 用于持续交流
     */
    Flux<Output> send(List<AgentMessage> messages, Class<?> outputFormat, List<Tool<?>> tools);

    /**
     * 用于工具查询
     */
    Output query(List<AgentMessage> messages, Class<?> outputFormat);

    /**
     * 批量工具查询
     * @return 批量任务 ID
     */
    String batch(List<List<AgentMessage>> messageBatch, Class<?> outputFormat);

    /**
     * 查看批量任务状态
     */
    BatchTaskInfo taskStatus(String taskId);

    /**
     * 查看批量任务结果
     */
    List<Output> taskResult(BatchTaskInfo task);
}
