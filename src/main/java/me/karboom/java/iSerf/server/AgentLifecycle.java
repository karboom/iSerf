package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Agent 生命周期管理，定义 Agent 的创建、查询、删除操作。
 * 由 ContainerServer 注入 localAgents 后，实现类通过 protected 字段访问本地 Agent 缓存。
 */
public abstract class AgentLifecycle {

    /**
     * 本地 Agent 缓存，由 ContainerServer 在 init 中注入
     */
    protected Map<String, Agent> localAgents;

    /**
     * 由 ContainerServer 调用，注入本地 Agent 缓存引用
     *
     * @param localAgents 本地 Agent 缓存
     */
    void init(Map<String, Agent> localAgents) {
        this.localAgents = localAgents;
    }

    /**
     * Agent 工厂方法
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params Agent 构造参数
     * @return Agent 实例
     */
    public abstract Agent createAgent(ContainerServer.Context ctx, ObjectNode params);

    /**
     * 按照条件查找智能体列表
     *
     * @param ctx    请求上下文
     * @param params 查询条件
     * @return 匹配的 Agent 列表
     */
    public abstract List<Agent> listAgent(ContainerServer.Context ctx, ObjectNode params);

    /**
     * 删除 Agent
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 删除参数（含 target agent ID）
     * @return 被删除的 Agent 实例，若不存在则返回 null
     */
    public abstract Agent removeAgent(ContainerServer.Context ctx, ObjectNode params);

    /**
     * 编辑 Agent
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 编辑参数（含 target agent ID 及要修改的字段）
     * @return 编辑后的 Agent 实例，若不存在则返回 null
     */
    public abstract Agent editAgent(ContainerServer.Context ctx, ObjectNode params);

    /**
     * 获取 Agent 详情
     *
     * @param ctx    请求上下文（含 transport、client、user 等信息）
     * @param params 查询参数（含 target agent ID）
     * @return Agent 实例，若不存在则返回 null
     */
    public abstract Agent detailAgent(ContainerServer.Context ctx, ObjectNode params);

    // region 序列化

    /**
     * 将 Agent 序列化为 ObjectNode
     *
     * @param agent Agent 实例
     * @return Agent 对应的 ObjectNode
     */
    public ObjectNode serializeAgent(Agent agent) {
        return JSONUtil.convert(agent);
    }

    // endregion
}
