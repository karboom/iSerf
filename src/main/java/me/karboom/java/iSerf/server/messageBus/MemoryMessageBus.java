package me.karboom.java.iSerf.server.messageBus;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 内存消息总线，适用于单节点或测试场景。
 * publish 直接同步调用所有 subscriber 的 listener。
 */
@Slf4j
public class MemoryMessageBus implements IMessageBus {

    private final Map<String, List<Consumer<String>>> topicSubscribers = new ConcurrentHashMap<>();

    @Override
    public void publish(String topic, String message) {
        log.debug("publish topic=%s message=%s".formatted(topic, message));
        var subscribers = topicSubscribers.get(topic);
        if (subscribers != null) {
            subscribers.forEach(listener -> {
                try {
                    listener.accept(message);
                } catch (Exception e) {
                    log.error("publish error in subscriber for topic=%s: %s".formatted(topic, e.getMessage()), e);
                }
            });
        }
    }

    @Override
    public void subscribe(String topic, Consumer<String> listener) {
        log.debug("subscribe topic=%s".formatted(topic));
        topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(listener);
    }
}
