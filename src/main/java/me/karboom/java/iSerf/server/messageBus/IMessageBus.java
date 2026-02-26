package me.karboom.java.iSerf.server.messageBus;

import java.util.function.Consumer;
import java.util.function.Function;

public interface IMessageBus {

    /**
     * 向特定主题发送消息
     * @param topic
     * @param message
     */
    public abstract void publish(String topic, String message);

    /**
     * 订阅特定主题，listener调用成功后需要ack
     * @param topic
     * @param listener
     */
    public abstract void subscribe(String topic, Consumer<String> listener);

}
