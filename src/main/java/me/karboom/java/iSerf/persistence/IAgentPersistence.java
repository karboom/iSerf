package me.karboom.java.iSerf.persistence;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMetadata;

import java.util.List;
import java.util.Map;

/**
 * Agent 持久化接口
 * 支持全量覆盖和增量追加两种模式
 */
public interface IAgentPersistence {

    /**
     * 加载 Agent 完整状态
     *
     * @param metadata Agent 元数据
     * @return Agent 快照，包含所有持久化数据
     */
    AgentSnapshot load(AgentMetadata metadata);

    /**
     * 彻底删除 Agent 的所有持久化数据
     *
     * @param agent Agent 实例
     */
    void remove(Agent agent);

    // region Memory

    /**
     * 全量覆盖记忆
     *
     * @param agent Agent 实例
     */
    void syncMemory(Agent agent);

    /**
     * 增量追加记忆
     *
     * @param agent Agent 实例
     */
    void addMemory(Agent agent);

    // endregion

    // region Event

    /**
     * 全量覆盖事件
     *
     * @param agent Agent 实例
     */
    void syncEvent(Agent agent);

    // endregion

    // region ToolCall

    /**
     * 全量覆盖工具调用缓存
     *
     * @param agent Agent 实例
     */
    void syncToolCall(Agent agent);

    /**
     * 增量追加工具调用缓存
     *
     * @param agent Agent 实例
     */
    void addToolCall(Agent agent);

    // endregion

    // region Plan

    /**
     * 全量覆盖定时任务计划
     *
     * @param agent Agent 实例
     */
    void syncPlan(Agent agent);

    // endregion

    // region Metadata

    /**
     * 全量覆盖元数据
     *
     * @param agent Agent 实例
     */
    void syncMetadata(Agent agent);

    // endregion

    // region Search

    /**
     * 全局搜索 Agent
     * 根据关键字匹配 orgId、userId、agentId，返回匹配的 Agent 快照列表
     *
     * @param params 搜索参数
     * @return 匹配的 Agent 快照列表
     */
    List<AgentSnapshot> search(Map<String, Object> params);

    // endregion

    // region Lifecycle

    /**
     * 创建 Agent 持久化记录
     * 初始化目录结构和空文件
     *
     * @param agent Agent 实例
     * @return true=新建成功，false=已存在
     */
    Boolean create(Agent agent);

    // endregion
}
