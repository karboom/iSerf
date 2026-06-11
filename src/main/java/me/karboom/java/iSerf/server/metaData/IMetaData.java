package me.karboom.java.iSerf.server.metaData;

import java.util.List;

/**
 * 集群元数据
 */
public interface IMetaData {

    /**
     * 获取集群所有节点
     * @return 节点信息
     */
    List<Node> getNodes();

    /**
     * 新增节点
     * @param node 节点信息
     */
    void addNode(Node node);

    /**
     * 删除节点
     * @param id 节点ID
     */
    void removeNode(String id);


    /**
     * 查询Agent驻留节点
     */
    String getAgentStay(String id);

    /**
     * 设置Agent驻留节点
     */
    void setAgentStay(String agentId, String nodeId);

    /**
     * 查询Team驻留节点
     */
    String getTeamStay(String id);

    /**
     * 设置Team驻留节点
     */
    void setTeamStay(String teamId, String nodeId);

    /**
     * 删除Team驻留节点
     */
    void removeTeamStay(String teamId);

    /**
     * 查询Agent订阅节点
     */
    List<String> getAgentSubscribeNodes(String id);

    /**
     * 新增订阅节点
     */
    void addAgentSubscribeNode(String agentId, String nodeId);

    /**
     * 删除订阅节点
     */
    void removeAgentSubscribeNode(String agentId, String NodeId);
}
