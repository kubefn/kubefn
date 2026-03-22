package io.kubefn.runtime.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages graceful drain during hot-swap. Tracks in-flight requests
 * per group revision and blocks unload until all requests complete or timeout.
 *
 * <p>Flow:
 * <ol>
 *   <li>Function receives request → {@link #acquireRequest(String)}</li>
 *   <li>Function completes → {@link #releaseRequest(String)}</li>
 *   <li>Hot-swap initiated → {@link #drainAndWait(String, long)}</li>
 *   <li>Drain blocks new requests and waits for in-flight to complete</li>
 *   <li>After drain, classloader can safely unload</li>
 * </ol>
 */
public class DrainManager {

    private static final Logger log = LoggerFactory.getLogger(DrainManager.class);

    private final ConcurrentHashMap<String, GroupDrainState> states = new ConcurrentHashMap<>();

    /**
     * Acquire a request slot for a group. Returns false if the group is draining.
     */
    public boolean acquireRequest(String groupName) {
        GroupDrainState state = states.computeIfAbsent(groupName, k -> new GroupDrainState());
        if (state.draining) {
            return false; // Reject — group is draining
        }
        state.inFlightCount.incrementAndGet();
        return true;
    }

    /**
     * Release a request slot when the request completes.
     */
    public void releaseRequest(String groupName) {
        GroupDrainState state = states.get(groupName);
        if (state != null) {
            int remaining = state.inFlightCount.decrementAndGet();
            if (remaining <= 0 && state.draining) {
                state.lock.lock();
                try {
                    state.drained.signalAll();
                } finally {
                    state.lock.unlock();
                }
            }
        }
    }

    /**
     * Initiate drain for a group and wait for all in-flight requests to complete.
     *
     * @param groupName the group to drain
     * @param timeoutMs maximum time to wait for drain
     * @return true if drained successfully, false if timed out
     */
    public boolean drainAndWait(String groupName, long timeoutMs) {
        GroupDrainState state = states.computeIfAbsent(groupName, k -> new GroupDrainState());
        state.draining = true;

        int inFlight = state.inFlightCount.get();
        if (inFlight <= 0) {
            log.info("Group '{}' drained immediately (no in-flight requests)", groupName);
            states.remove(groupName);
            return true;
        }

        log.info("Draining group '{}': {} in-flight requests, timeout {}ms",
                groupName, inFlight, timeoutMs);

        state.lock.lock();
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (state.inFlightCount.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.warn("Drain timeout for group '{}': {} requests still in-flight",
                            groupName, state.inFlightCount.get());
                    states.remove(groupName);
                    return false;
                }
                state.drained.await(remaining, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Drain interrupted for group '{}'", groupName);
            states.remove(groupName);
            return false;
        } finally {
            state.lock.unlock();
        }

        log.info("Group '{}' drained successfully", groupName);
        states.remove(groupName);
        return true;
    }

    /**
     * Check if a group is currently draining.
     */
    public boolean isDraining(String groupName) {
        GroupDrainState state = states.get(groupName);
        return state != null && state.draining;
    }

    /**
     * Get in-flight count for a group.
     */
    public int inFlightCount(String groupName) {
        GroupDrainState state = states.get(groupName);
        return state != null ? state.inFlightCount.get() : 0;
    }

    private static class GroupDrainState {
        final AtomicInteger inFlightCount = new AtomicInteger(0);
        volatile boolean draining = false;
        final ReentrantLock lock = new ReentrantLock();
        final Condition drained = lock.newCondition();
    }
}
