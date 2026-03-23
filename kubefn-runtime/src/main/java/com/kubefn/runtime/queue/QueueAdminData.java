package com.kubefn.runtime.queue;

import java.util.List;

public record QueueAdminData(
    List<QueueEntry> queues
) {
    public record QueueEntry(
        String topic, int depth, int consumers,
        long processed, long errors, boolean hasDeadLetter
    ) {}
}
