package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Team 生命周期管理，定义 Team 的创建、查询、删除操作。
 * 通过 ctx.container 访问 ContainerServer 实例及其缓存（如 localTeams）。
 */
public abstract class TeamLifecycle {

    /**
     * Team 工厂方法
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params Team 构造参数
     * @return Team 实例
     */
    public abstract Team createTeam(ContainerServer.Context ctx, ObjectNode params);

    /**
     * 按照条件查找 Team 列表
     *
     * @param ctx    请求上下文
     * @param params 查询条件
     * @return 匹配的 Team 列表
     */
    public abstract List<Team> listTeam(ContainerServer.Context ctx, ObjectNode params);

    /**
     * 删除 Team
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 删除参数（含 target team ID）
     * @return 被删除的 Team 实例，若不存在则返回 null
     */
    public abstract Team removeTeam(ContainerServer.Context ctx, ObjectNode params);

    /**
     * 编辑 Team
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 编辑参数（含 target team ID 及要修改的字段）
     * @return 编辑后的 Team 实例，若不存在则返回 null
     */
    public abstract Team editTeam(ContainerServer.Context ctx, ObjectNode params);

    /**
     * 获取 Team 详情
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 查询参数（含 target team ID）
     * @return Team 实例，若不存在则返回 null
     */
    public abstract Team detailTeam(ContainerServer.Context ctx, ObjectNode params);

    // region 序列化

    /**
     * 将 Team 序列化为 ObjectNode
     *
     * @param team Team 实例
     * @return Team 对应的 ObjectNode
     */
    public ObjectNode serializeTeam(Team team) {
        return JSONUtil.convert(team);
    }

    // endregion
}
