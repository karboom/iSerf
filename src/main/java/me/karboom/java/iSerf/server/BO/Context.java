package me.karboom.java.iSerf.server.BO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSerf.server.Server;
import me.karboom.java.iSerf.server.transport.ITransport;
import tools.jackson.databind.node.ObjectNode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Context {
    public ITransport transport;
    /**
     * 请求ID,用于异步回复的匹配
     */
    public String requestId;

    public Object client;

    public Object userData;
    /**
     * 用户标识
     */
    public String userId;
    /**
     * 请求原始数据
     */
    public ObjectNode requestData;
    /**
     * 消息来源服务器标识
     */
    public String serverId;
    /**
     * 所属容器实例，供生命周期方法访问 messageBus、metaData 等
     */
    public Server server;
    /**
     * 是否集群内部通信（来自其他节点的代理请求）
     */
    public Boolean isInternal;
}
