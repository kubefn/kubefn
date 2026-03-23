package com.kubefn.runtime.queue;

import com.kubefn.api.FnQueue;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory queue engine that scans loaded functions for @FnQueue annotations
 * and wires them up as consumers. Uses LinkedBlockingQueue per topic as the
 * default "memory" adapter.
 */
public class FnQueueEngine {

    private static final Logger log = LoggerFactory.getLogger(FnQueueEngine.class);

    private final Map<String, LinkedBlockingQueue<String>> queues = new ConcurrentHashMap<>();
    private final Map<String, List<ConsumerEntry>> consumers = new ConcurrentHashMap<>();
    private final Map<String, TopicStats> stats = new ConcurrentHashMap<>();
    private final List<Thread> consumerThreads = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Register a handler that has an @FnQueue annotation as a consumer.
     */
    public void register(String functionName, KubeFnHandler handler) {
        FnQueue annotation = handler.getClass().getAnnotation(FnQueue.class);
        if (annotation == null) {
            return;
        }

        String topic = annotation.topic();
        queues.computeIfAbsent(topic, k -> new LinkedBlockingQueue<>());
        stats.computeIfAbsent(topic, k -> new TopicStats(annotation.deadLetterTopics().length > 0));

        // Create DLQ topics
        for (String dlqTopic : annotation.deadLetterTopics()) {
            queues.computeIfAbsent(dlqTopic, k -> new LinkedBlockingQueue<>());
            stats.computeIfAbsent(dlqTopic, k -> new TopicStats(false));
        }

        ConsumerEntry entry = new ConsumerEntry(
            functionName, handler, annotation.concurrency(),
            annotation.batchSize(), annotation.pollIntervalMs(),
            annotation.deadLetterTopics()
        );

        consumers.computeIfAbsent(topic, k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
        log.info("Registered queue consumer: {} on topic [{}] concurrency={} batchSize={}",
                functionName, topic, annotation.concurrency(), annotation.batchSize());
    }

    /**
     * Publish a message to a topic. Creates the topic queue if it does not exist.
     */
    public void publish(String topic, String message) {
        LinkedBlockingQueue<String> queue = queues.computeIfAbsent(topic, k -> new LinkedBlockingQueue<>());
        stats.computeIfAbsent(topic, k -> new TopicStats(false));
        queue.offer(message);
        log.debug("Published message to topic [{}], queue depth: {}", topic, queue.size());
    }

    /**
     * Start all consumer threads.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            log.warn("Queue engine already started");
            return;
        }

        log.info("Starting FnQueueEngine with {} topics", consumers.size());

        for (Map.Entry<String, List<ConsumerEntry>> topicConsumers : consumers.entrySet()) {
            String topic = topicConsumers.getKey();
            for (ConsumerEntry entry : topicConsumers.getValue()) {
                for (int i = 0; i < entry.concurrency; i++) {
                    Thread thread = Thread.ofVirtual()
                        .name("queue-" + topic + "-" + entry.functionName + "-" + i)
                        .start(() -> consumerLoop(topic, entry));
                    consumerThreads.add(thread);
                }
            }
        }
    }

    /**
     * Shut down all consumer threads and wait for them to finish.
     */
    public void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Shutting down FnQueueEngine");

        for (Thread thread : consumerThreads) {
            thread.interrupt();
        }
        for (Thread thread : consumerThreads) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        consumerThreads.clear();
    }

    /**
     * Returns admin data about all queue topics.
     */
    public QueueAdminData getQueueStats() {
        List<QueueAdminData.QueueEntry> entries = new ArrayList<>();

        for (Map.Entry<String, LinkedBlockingQueue<String>> queueEntry : queues.entrySet()) {
            String topic = queueEntry.getKey();
            LinkedBlockingQueue<String> queue = queueEntry.getValue();
            TopicStats topicStats = stats.getOrDefault(topic, new TopicStats(false));

            int consumerCount = 0;
            List<ConsumerEntry> topicConsumers = consumers.get(topic);
            if (topicConsumers != null) {
                for (ConsumerEntry ce : topicConsumers) {
                    consumerCount += ce.concurrency;
                }
            }

            entries.add(new QueueAdminData.QueueEntry(
                topic, queue.size(), consumerCount,
                topicStats.processed.get(), topicStats.errors.get(),
                topicStats.hasDeadLetter
            ));
        }

        return new QueueAdminData(Collections.unmodifiableList(entries));
    }

    private void consumerLoop(String topic, ConsumerEntry entry) {
        LinkedBlockingQueue<String> queue = queues.get(topic);
        if (queue == null) {
            log.error("No queue found for topic [{}]", topic);
            return;
        }

        TopicStats topicStats = stats.get(topic);

        while (started.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<String> batch = new ArrayList<>(entry.batchSize);

                // Block on the first message
                String first = queue.poll(entry.pollIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);

                // Drain up to batchSize - 1 more without blocking
                for (int i = 1; i < entry.batchSize; i++) {
                    String msg = queue.poll();
                    if (msg == null) {
                        break;
                    }
                    batch.add(msg);
                }

                // Build a synthetic request with the batch as the body
                String body = batch.size() == 1 ? batch.get(0) : "[" + String.join(",", batch) + "]";
                KubeFnRequest request = new KubeFnRequest(
                    "QUEUE",
                    "/queue/" + topic,
                    "",
                    Map.of(
                        "X-KubeFn-Trigger", "queue",
                        "X-KubeFn-Topic", topic,
                        "X-KubeFn-BatchSize", String.valueOf(batch.size())
                    ),
                    Map.of(),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                );

                try {
                    entry.handler.handle(request);
                    if (topicStats != null) {
                        topicStats.processed.addAndGet(batch.size());
                    }
                    log.debug("Processed {} messages from topic [{}] via {}",
                            batch.size(), topic, entry.functionName);
                } catch (Exception e) {
                    log.error("Consumer {} failed processing batch from topic [{}]",
                            entry.functionName, topic, e);
                    if (topicStats != null) {
                        topicStats.errors.addAndGet(batch.size());
                    }

                    // Route to dead letter queue
                    for (String dlqTopic : entry.deadLetterTopics) {
                        for (String msg : batch) {
                            publish(dlqTopic, msg);
                        }
                        log.debug("Routed {} messages to DLQ topic [{}]", batch.size(), dlqTopic);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("Consumer thread for {} on topic [{}] stopped", entry.functionName, topic);
    }

    private static class ConsumerEntry {
        final String functionName;
        final KubeFnHandler handler;
        final int concurrency;
        final int batchSize;
        final long pollIntervalMs;
        final String[] deadLetterTopics;

        ConsumerEntry(String functionName, KubeFnHandler handler, int concurrency,
                      int batchSize, long pollIntervalMs, String[] deadLetterTopics) {
            this.functionName = functionName;
            this.handler = handler;
            this.concurrency = concurrency;
            this.batchSize = batchSize;
            this.pollIntervalMs = pollIntervalMs;
            this.deadLetterTopics = deadLetterTopics;
        }
    }

    private static class TopicStats {
        final AtomicLong processed = new AtomicLong(0);
        final AtomicLong errors = new AtomicLong(0);
        final boolean hasDeadLetter;

        TopicStats(boolean hasDeadLetter) {
            this.hasDeadLetter = hasDeadLetter;
        }
    }
}
