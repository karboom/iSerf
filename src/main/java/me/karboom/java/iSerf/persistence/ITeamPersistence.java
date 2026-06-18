package me.karboom.java.iSerf.persistence;

import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.team.Team;

import java.util.List;
import java.util.Map;

/**
 * Team 持久化接口
 */
public interface ITeamPersistence {

    /**
     * 加载 Team 完整状态
     *
     * @param metadata Team 元数据
     * @return Team 快照，包含所有持久化数据
     */
    TeamSnapshot load(AgentMetadata metadata);

    /**
     * 彻底删除 Team 的所有持久化数据
     *
     * @param team Team 实例
     */
    void remove(Team team);

    // region Metadata

    /**
     * 全量覆盖元数据
     *
     * @param team Team 实例
     */
    void syncMetadata(Team team);

    // endregion

    // region Task

    /**
     * 全量覆盖任务列表
     *
     * @param team Team 实例
     */
    void syncTasks(Team team);

    // endregion

    // region Search

    /**
     * 全局搜索 Team
     * 根据关键字匹配 orgId、userId、teamId，返回匹配的 Team 快照列表
     *
     * @param params 搜索参数
     * @return 匹配的 Team 快照列表
     */
    List<TeamSnapshot> search(Map<String, Object> params);

    // endregion

    // region Lifecycle

    /**
     * 创建 Team 持久化记录
     * 初始化目录结构和空文件
     *
     * @param team Team 实例
     * @return true=新建成功，false=已存在
     */
    Boolean create(Team team);

    // endregion
}
