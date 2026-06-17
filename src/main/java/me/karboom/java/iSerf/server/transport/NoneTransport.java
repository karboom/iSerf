package me.karboom.java.iSerf.server.transport;

import lombok.extern.slf4j.Slf4j;

/**
 * 空传输实现，所有操作均为 no-op。
 * 适用于单节点或测试场景，不需要真实传输层。
 */
@Slf4j
public class NoneTransport implements ITransport {

    private final String transportId;

    public NoneTransport() {
        this.transportId = "none-transport-" + System.nanoTime();
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getTransportId() {
        return transportId;
    }

    @Override
    public void sendToClient(Object clientHandle, String event, String bodyJson) {
    }

    @Override
    public Object getUser(Object clientHandle) {
        return null;
    }

    @Override
    public String getUserId(Object clientHandle) {
        return null;
    }

    @Override
    public void closeClient(Object clientHandle) {
    }

    @Override
    public void authorizeConnection(Object clientHandle) {
    }
}
