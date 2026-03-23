package com.kubefn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a function as a queue consumer that processes messages from a topic
 * inside the warm JVM. The runtime will spawn consumer threads that poll
 * the configured queue adapter and invoke the function for each batch.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FnQueue {

    /** Queue or topic name to consume from. */
    String topic();

    /** Dead-letter queue topic names for failed messages. */
    String[] deadLetterTopics() default {};

    /** Number of parallel consumer threads. */
    int concurrency() default 1;

    /** Number of messages collected before invoking the function. */
    int batchSize() default 1;

    /** Polling interval in milliseconds. */
    long pollIntervalMs() default 1000;

    /** Queue adapter implementation (memory, kafka, sqs, rabbitmq). */
    String adapter() default "memory";
}
