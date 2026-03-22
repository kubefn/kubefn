package com.kubefn.runtime.watcher;

import com.kubefn.runtime.classloader.FunctionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the functions directory for changes and triggers hot-reload.
 * Debounces rapid changes (500ms) to avoid partial-update reloads.
 *
 * <p>This enables the "edit the living system" workflow:
 * drop a new JAR → runtime detects → old classloader discarded →
 * new classloader created (born-warm) → routes re-registered.
 */
public class FunctionWatcher implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(FunctionWatcher.class);
    private static final long DEBOUNCE_MS = 500;

    private final Path functionsDir;
    private final FunctionLoader loader;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentHashMap<String, Long> lastChangeTime = new ConcurrentHashMap<>();

    public FunctionWatcher(Path functionsDir, FunctionLoader loader) {
        this.functionsDir = functionsDir;
        this.loader = loader;
    }

    @Override
    public void run() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            // Watch the root functions directory for new groups
            functionsDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            // Watch each existing group subdirectory
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(functionsDir)) {
                for (Path dir : dirs) {
                    if (Files.isDirectory(dir)) {
                        dir.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                    }
                }
            }

            log.info("Function watcher started on: {}", functionsDir);

            while (running.get()) {
                WatchKey key;
                try {
                    key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (key == null) continue;

                Path watchedDir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = watchedDir.resolve((Path) event.context());
                    handleChange(event.kind(), changed, watchService);
                }

                key.reset();
            }

        } catch (IOException e) {
            log.error("Function watcher error", e);
        }

        log.info("Function watcher stopped.");
    }

    private void handleChange(WatchEvent.Kind<?> kind, Path changed, WatchService watchService) {
        // Determine which group was affected
        String groupName = resolveGroupName(changed);
        if (groupName == null) return;

        // Debounce: wait for rapid changes to settle
        long now = System.currentTimeMillis();
        Long lastChange = lastChangeTime.get(groupName);
        lastChangeTime.put(groupName, now);

        if (lastChange != null && (now - lastChange) < DEBOUNCE_MS) {
            return; // Still debouncing
        }

        // Schedule reload after debounce window
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(DEBOUNCE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Check if more changes came during debounce
            Long latestChange = lastChangeTime.get(groupName);
            if (latestChange != null && (System.currentTimeMillis() - latestChange) < DEBOUNCE_MS) {
                return; // More changes pending, skip this reload
            }

            Path groupDir = functionsDir.resolve(groupName);

            if (kind == StandardWatchEventKinds.ENTRY_DELETE && !Files.exists(groupDir)) {
                log.info("Group directory deleted: {}", groupName);
                loader.unloadGroup(groupName);
            } else if (Files.isDirectory(groupDir)) {
                log.info("Change detected in group: {} — reloading...", groupName);

                // Register the new/changed directory for watching
                try {
                    groupDir.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException e) {
                    log.debug("Could not register watch for: {}", groupDir, e);
                }

                loader.loadGroup(groupName, groupDir);
            }
        });
    }

    private String resolveGroupName(Path changed) {
        try {
            Path relative = functionsDir.relativize(changed);
            // First component is the group name
            if (relative.getNameCount() > 0) {
                return relative.getName(0).toString();
            }
        } catch (IllegalArgumentException e) {
            // changed is not under functionsDir
        }
        return null;
    }

    public void stop() {
        running.set(false);
    }
}
