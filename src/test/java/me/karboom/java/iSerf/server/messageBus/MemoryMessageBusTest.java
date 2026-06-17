package me.karboom.java.iSerf.server.messageBus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MemoryMessageBusTest {

    private MemoryMessageBus messageBus;

    @BeforeEach
    void setUp() {
        messageBus = new MemoryMessageBus();
    }

    // region ========== Publish & Subscribe ==========

    @Nested
    class PublishSubscribe {

        @Test
        void testPublishToSubscribedTopic() {
            var receivedMessages = new ArrayList<String>();
            var latch = new CountDownLatch(1);

            messageBus.subscribe("test-topic", msg -> {
                receivedMessages.add(msg);
                latch.countDown();
            });

            messageBus.publish("test-topic", "Hello, World!");

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "Message should be received");
            } catch (InterruptedException e) {
                fail("Interrupted while waiting for message");
            }

            assertEquals(1, receivedMessages.size());
            assertEquals("Hello, World!", receivedMessages.get(0));
        }

        @Test
        void testPublishToNonExistentTopic() {
            assertDoesNotThrow(() -> messageBus.publish("non-existent-topic", "message"));
        }

        @Test
        void testMultipleSubscribersOnSameTopic() {
            var receivedCount = new AtomicInteger(0);
            var latch = new CountDownLatch(3);

            messageBus.subscribe("test-topic", msg -> {
                receivedCount.incrementAndGet();
                latch.countDown();
            });
            messageBus.subscribe("test-topic", msg -> {
                receivedCount.incrementAndGet();
                latch.countDown();
            });
            messageBus.subscribe("test-topic", msg -> {
                receivedCount.incrementAndGet();
                latch.countDown();
            });

            messageBus.publish("test-topic", "broadcast message");

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "All subscribers should receive the message");
            } catch (InterruptedException e) {
                fail("Interrupted while waiting for messages");
            }

            assertEquals(3, receivedCount.get());
        }

        @Test
        void testMultipleMessagesOnSameTopic() {
            var receivedMessages = new ArrayList<String>();
            var latch = new CountDownLatch(3);

            messageBus.subscribe("test-topic", msg -> {
                receivedMessages.add(msg);
                latch.countDown();
            });

            messageBus.publish("test-topic", "message-1");
            messageBus.publish("test-topic", "message-2");
            messageBus.publish("test-topic", "message-3");

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "All messages should be received");
            } catch (InterruptedException e) {
                fail("Interrupted while waiting for messages");
            }

            assertEquals(3, receivedMessages.size());
            assertEquals("message-1", receivedMessages.get(0));
            assertEquals("message-2", receivedMessages.get(1));
            assertEquals("message-3", receivedMessages.get(2));
        }

        @Test
        void testDifferentTopicsIsolated() {
            var topic1Messages = new ArrayList<String>();
            var topic2Messages = new ArrayList<String>();
            var latch = new CountDownLatch(2);

            messageBus.subscribe("topic-1", msg -> {
                topic1Messages.add(msg);
                latch.countDown();
            });
            messageBus.subscribe("topic-2", msg -> {
                topic2Messages.add(msg);
                latch.countDown();
            });

            messageBus.publish("topic-1", "message-for-topic-1");
            messageBus.publish("topic-2", "message-for-topic-2");

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "Messages should be received on respective topics");
            } catch (InterruptedException e) {
                fail("Interrupted while waiting for messages");
            }

            assertEquals(1, topic1Messages.size());
            assertEquals("message-for-topic-1", topic1Messages.get(0));
            assertEquals(1, topic2Messages.size());
            assertEquals("message-for-topic-2", topic2Messages.get(0));
        }

        @Test
        void testSubscriberExceptionDoesNotAffectOthers() {
            var receivedMessages = new ArrayList<String>();
            var latch = new CountDownLatch(1);

            messageBus.subscribe("test-topic", msg -> {
                throw new RuntimeException("Subscriber error");
            });
            messageBus.subscribe("test-topic", msg -> {
                receivedMessages.add(msg);
                latch.countDown();
            });

            assertDoesNotThrow(() -> messageBus.publish("test-topic", "test message"));

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "Second subscriber should still receive message");
            } catch (InterruptedException e) {
                fail("Interrupted while waiting for message");
            }

            assertEquals(1, receivedMessages.size());
            assertEquals("test message", receivedMessages.get(0));
        }

        @Test
        void testPublishEmptyMessage() {
            var receivedMessages = new ArrayList<String>();
            var latch = new CountDownLatch(1);

            messageBus.subscribe("test-topic", msg -> {
                receivedMessages.add(msg);
                latch.countDown();
            });

            messageBus.publish("test-topic", "");

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "Empty message should be received");
            } catch (InterruptedException e) {
                fail("Interrupted while waiting for message");
            }

            assertEquals(1, receivedMessages.size());
            assertEquals("", receivedMessages.get(0));
        }

        @Test
        void testPublishNullMessage() {
            var receivedMessages = new ArrayList<String>();
            var latch = new CountDownLatch(1);

            messageBus.subscribe("test-topic", msg -> {
                receivedMessages.add(msg);
                latch.countDown();
            });

            messageBus.publish("test-topic", null);

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "Null message should be received");
            } catch (InterruptedException e) {
                fail("Interrupted while waiting for message");
            }

            assertEquals(1, receivedMessages.size());
            assertNull(receivedMessages.get(0));
        }

        @Test
        void testSubscribeMultipleTimes() {
            var receivedCount = new AtomicInteger(0);
            var latch = new CountDownLatch(2);

            var listener = (java.util.function.Consumer<String>) msg -> {
                receivedCount.incrementAndGet();
                latch.countDown();
            };

            messageBus.subscribe("test-topic", listener);
            messageBus.subscribe("test-topic", listener);

            messageBus.publish("test-topic", "test message");

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "Both subscriptions should receive the message");
            } catch (InterruptedException e) {
                fail("Interrupted while waiting for messages");
            }

            assertEquals(2, receivedCount.get(), "Same listener subscribed twice should be called twice");
        }
    }

    // endregion

    // region ========== Synchronous Behavior ==========

    @Nested
    class SynchronousBehavior {

        @Test
        void testPublishIsSynchronous() {
            var executionOrder = new ArrayList<String>();

            messageBus.subscribe("test-topic", msg -> executionOrder.add("subscriber"));

            executionOrder.add("before-publish");
            messageBus.publish("test-topic", "message");
            executionOrder.add("after-publish");

            assertEquals(3, executionOrder.size());
            assertEquals("before-publish", executionOrder.get(0));
            assertEquals("subscriber", executionOrder.get(1));
            assertEquals("after-publish", executionOrder.get(2));
        }

        @Test
        void testPublishBlocksUntilAllSubscribersComplete() {
            var completed = new AtomicInteger(0);

            messageBus.subscribe("test-topic", msg -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completed.incrementAndGet();
            });

            messageBus.subscribe("test-topic", msg -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completed.incrementAndGet();
            });

            messageBus.publish("test-topic", "message");

            assertEquals(2, completed.get(), "Publish should block until all subscribers complete");
        }
    }

    // endregion
}
