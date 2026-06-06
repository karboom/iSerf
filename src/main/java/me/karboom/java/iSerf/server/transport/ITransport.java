package me.karboom.java.iSerf.server.transport;

/**
 * 通信传输协议接口，定义传输层的生命周期和多协议互通能力
 */
public interface ITransport {

    /**
     * 启动传输协议服务
     */
    void start();

    /**
     * 停止传输协议服务
     */
    void stop();

    /**
     * 获取传输协议唯一标识
     *
     * @return transportId
     */
    String getTransportId();

    /**
     * 向指定客户端发送事件
     *
     * @param clientHandle 客户端句柄
     * @param event        事件名
     * @param bodyJson     事件数据 JSON 字符串
     */
    void sendToClient(Object clientHandle, String event, String bodyJson);

    /**
     * 根据客户端句柄获取当前用户信息
     * @param clientHandle
     * @return
     */
    Object getUser(Object clientHandle);

    /**
     * 断开连接
     */
    void closeClient(Object clientHandle);
}