package me.karboom.java.iSerf.server.messageBus;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.util.JSONUtil;
import org.apache.pulsar.client.api.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class Pulsar implements IMessageBus {

    private final PulsarClient client;
    private final Map<String, Producer<String>> producerCache = new ConcurrentHashMap<>();
    private final Map<String, org.apache.pulsar.client.api.Consumer<String>> consumerCache = new ConcurrentHashMap<>();

    public Pulsar(String serviceUrl) {
        try {
            this.client = PulsarClient.builder()
                    .serviceUrl(serviceUrl)
                    .build();
        } catch (PulsarClientException e) {
            throw new RuntimeException("Failed to create Pulsar client", e);
        }
    }

    @Override
    @SneakyThrows
    public void publish(String topic, String message) {
        log.debug("publish: topic={}, message={}", topic, message);
        
        var producer = producerCache.computeIfAbsent(topic, t -> {
            try {
                return client.newProducer(Schema.STRING)
                        .topic(t)
                        .create();
            } catch (PulsarClientException e) {
                throw new RuntimeException("Failed to create producer for topic: " + t, e);
            }
        });

        producer.send(message);
    }

    @Override
    @SneakyThrows
    public void subscribe(String topic, Consumer<String> listener) {
        log.debug("subscribe: topic={}", topic);
        
        var pulsarConsumer = consumerCache.computeIfAbsent(topic, t -> {
            try {
                return client.newConsumer(Schema.STRING)
                        .topic(t)
                        .subscriptionName(t + "-subscription")
                        .subscriptionType(SubscriptionType.Shared)
                        .ackTimeout(30, TimeUnit.SECONDS)
                        .subscribe();
            } catch (PulsarClientException e) {
                throw new RuntimeException("Failed to create consumer for topic: " + t, e);
            }
        });

        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    var msg = pulsarConsumer.receive();
                    try {
                        listener.accept(msg.getValue());
                        pulsarConsumer.acknowledge(msg);
                    } catch (Exception e) {
                        log.debug("subscribe: Failed to process message", e);
                        pulsarConsumer.negativeAcknowledge(msg);
                    }
                } catch (PulsarClientException e) {
                    log.debug("subscribe: Failed to receive message", e);
                }
            }
        });
    }
}