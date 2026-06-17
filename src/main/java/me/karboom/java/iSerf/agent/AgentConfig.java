package me.karboom.java.iSerf.agent;

import lombok.Data;
import me.karboom.java.iSerf.agent.llmProvider.ILlmProvider;
import me.karboom.java.iSerf.agent.persistence.IPersistence;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.billing.ILedger;
import me.karboom.java.iSerf.schedule.ISchedule;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent 配置类
 */
@Data
public class AgentConfig {
    /**
     * Agent 元数据
     */
    private AgentMetadata metadata;

    /**
     * 系统提示词
     */
    private String prompt;

    /**
     * LLM 实例
     */
    private ILlmProvider llm;

    /**
     * 工具列表
     */
    private List<Tool<?>> tools;

    /**
     * 持久化实现，null 则使用 NonePersistence
     */
    private IPersistence persistence;

    /**
     * 计费账本，null 则使用 Config 默认账本
     */
    private ILedger ledger;

    /**
     * 工作目录，文件系统工具以此目录为根
     */
    private Path workDir;

    /**
     * 定时任务调度器，null 则不启用
     */
    private ISchedule schedule;
}
