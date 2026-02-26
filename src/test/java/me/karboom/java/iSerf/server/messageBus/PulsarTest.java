package me.karboom.java.iSerf.server.messageBus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PulsarTest {

    private static Pulsar pulsar;
    private static final String PULSAR_URL = "pulsar://localhost:6650";

    @BeforeAll
    static void setUp() {
        pulsar = new Pulsar(PULSAR_URL);
    }

    @AfterAll
    static void tearDown() {
        if (pulsar != null) {
            // 清理资源
        }
    }

    @Test
    void testPublishAndSubscribe() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var topic = "test-topic";
            var message = "Hello, Pulsar!";

            var receivedLatch = new CountDownLatch(1);
            var receivedMessage = new AtomicBoolean(false);

            // 启动订阅线程
            var subscriberThread = new Thread(() -> {
                try {
                    pulsar.subscribe(topic, msg -> {
                        System.out.println("收到消息：" + msg);
                        if (message.equals(msg)) {
                            receivedMessage.set(true);
                        }
                        receivedLatch.countDown();
                    });
                } catch (Exception e) {
                    System.out.println("订阅异常：" + e.getMessage());
                }
            });
            subscriberThread.setDaemon(true);
            subscriberThread.start();

            // 等待订阅者启动
            Thread.sleep(1000);

            // 发布消息
            pulsar.publish(topic, message);
            System.out.println("消息已发送：" + message);

            // 等待接收消息
            assertTrue(receivedLatch.await(10, TimeUnit.SECONDS), "应在 10 秒内收到消息");
            assertTrue(receivedMessage.get(), "收到的消息应与发送的消息一致");
        });
    }

    @Test
    void testMultipleMessages() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var topic = "test-multi-topic";
            var messages = List.of("消息 1", "消息 2", "消息 3");

            var receivedLatch = new CountDownLatch(messages.size());
            var receivedMessages = new ArrayList<String>();

            // 启动订阅线程
            var subscriberThread = new Thread(() -> {
                try {
                    pulsar.subscribe(topic, msg -> {
                        System.out.println("收到消息：" + msg);
                        synchronized (receivedMessages) {
                            receivedMessages.add(msg);
                        }
                        receivedLatch.countDown();
                    });
                } catch (Exception e) {
                    System.out.println("订阅异常：" + e.getMessage());
                }
            });
            subscriberThread.setDaemon(true);
            subscriberThread.start();

            // 等待订阅者启动
            Thread.sleep(1000);

            // 发布多条消息
            for (var msg : messages) {
                pulsar.publish(topic, msg);
                System.out.println("消息已发送：" + msg);
            }

            // 等待接收所有消息
            assertTrue(receivedLatch.await(10, TimeUnit.SECONDS), "应在 10 秒内收到所有消息");
            
            synchronized (receivedMessages) {
                assertEquals(messages.size(), receivedMessages.size(), "应收到所有消息");
                for (int i = 0; i < messages.size(); i++) {
                    assertEquals(messages.get(i), receivedMessages.get(i), "第 " + i + " 条消息应一致");
                }
            }
        });
    }
}