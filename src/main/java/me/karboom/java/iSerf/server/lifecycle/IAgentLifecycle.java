package me.karboom.java.iSerf.server.lifecycle;

import me.karboom.java.iSerf.agent.Agent;

import me.karboom.java.iSerf.server.Server;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Agent 生命周期管理，定义 Agent 的创建、查询、删除操作。
 * 通过 ctx.container 访问 ContainerServer 实例及其缓存（如 localAgents）。
 */
public interface IAgentLifecycle {

    /**
     * Agent 工厂方法
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params Agent 构造参数
     * @return Agent 实例
     */
    Agent createAgent(Server.Context ctx, ObjectNode params);

    /**
     * 按照条件查找智能体列表
     *
     * @param ctx    请求上下文
     * @param params 查询条件
     * @return 匹配的 Agent 列表
     */
    List<Agent> listAgent(Server.Context ctx, ObjectNode params);

    /**
     * 删除 Agent
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 删除参数（含 target agent ID）
     * @return 被删除的 Agent 实例，若不存在则返回 null
     */
    Agent removeAgent(Server.Context ctx, ObjectNode params);

    /**
     * 编辑 Agent
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 编辑参数（含 target agent ID 及要修改的字段）
     * @return 编辑后的 Agent 实例，若不存在则返回 null
     */
    Agent editAgent(Server.Context ctx, ObjectNode params);

    /**
     * 获取 Agent 详情
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 查询参数（含 target agent ID）
     * @return Agent 实例，若不存在则返回 null
     */
    Agent detailAgent(Server.Context ctx, ObjectNode params);

    // region 序列化

    /**
     * 将 Agent 序列化为 ObjectNode
     *
     * @param agent Agent 实例
     * @return Agent 对应的 ObjectNode
     */
    ObjectNode serializeAgent(Agent agent);

    // endregion
}
