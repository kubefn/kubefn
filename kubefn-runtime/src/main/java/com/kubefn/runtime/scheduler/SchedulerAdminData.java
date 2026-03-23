package com.kubefn.runtime.scheduler;

import java.util.List;

public record SchedulerAdminData(
    List<ScheduledEntry> entries
) {
    public record ScheduledEntry(
        String functionName, String groupName, String cron,
        String timezone, boolean running, long lastRunMs,
        long nextFireMs, int totalRuns, int errors
    ) {}
}
