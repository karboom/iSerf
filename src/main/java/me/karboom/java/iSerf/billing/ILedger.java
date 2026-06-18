package me.karboom.java.iSerf.billing;

import me.karboom.java.iSerf.agent.Agent;

import java.util.List;

public interface ILedger {
    void record(Cost cost);

    /**
     * 查询指定目标的计费记录
     * @param targetType 目标类型
     * @param targetId   目标ID
     * @return 匹配的计费记录列表
     */
    List<Cost> query(String targetType, String targetId);

    /**
     * 根据用户查询所有计费记录
     * @param userId 用户ID
     * @return 该用户的所有计费记录列表
     */
    List<Cost> queryByUser(String userId);

    // region ========== 语义方法 ==========

    /**
     * 记录内存占用
     * @param agent Agent 实例
     * @param bytes 内存占用字节数
     */
    void recordMemory(Agent agent, long bytes);

    /**
     * 记录 CPU 耗时
     * @param agent Agent 实例
     * @param nanos CPU 耗时（纳秒）
     */
    void recordCpu(Agent agent, long nanos);

    /**
     * 记录 LLM token 消耗
     * @param agent  Agent 实例
     * @param tokens token 数量
     */
    void recordToken(Agent agent, int tokens);

    /**
     * 记录存储占用
     * @param agent Agent 实例
     * @param bytes 存储占用字节数
     */
    void recordDisk(Agent agent, int bytes);

    /**
     * 记录网络流量
     * @param agent Agent 实例
     * @param bytes 网络流量字节数
     */
    void recordTraffic(Agent agent, int bytes);

    /**
     * 统计使用次数
     * @param agent Agent 实例
     * @return 使用次数
     */
    long usageCount(Agent agent);

    /**
     * 统计 token 总量
     * @param agent Agent 实例
     * @return token 总量
     */
    long totalTokens(Agent agent);

    // endregion

    // region ========== 用户维度统计 ==========

    /**
     * 统计用户所有用量
     * @param userId 用户ID
     * @return 用户用量汇总
     */
    UsageSummary queryUsageSummary(String userId);

    // endregion
}
