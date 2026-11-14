package vn.edu.ptit.exam.client.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles persistent local storage of active exam attempts for offline safety and crash recovery.
 */
public class LocalAttemptStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalAttemptStorage.class);

    private final Path storageDir;
    private final ExecutorService ioExecutor;

    public LocalAttemptStorage() {
        this(Paths.get("exam_cache"));
    }

    public LocalAttemptStorage(Path storageDir) {
        this.storageDir = storageDir;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "exam-local-storage");
            t.setDaemon(true);
            return t;
        });
        ensureDirectoryExists();
    }

    private void ensureDirectoryExists() {
        try {
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
            }
        } catch (IOException e) {
            log.error("Failed to create local exam storage directory: {}", storageDir, e);
        }
    }

    private Path getFilePath(String attemptId) {
        return storageDir.resolve("attempt_" + attemptId + ".json");
    }

    /**
     * Asynchronously write snapshot to disk.
     */
    public CompletableFuture<Void> saveAttemptAsync(LocalAttemptData data) {
        if (data == null || data.getAttemptId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                ensureDirectoryExists();
                data.setLastSavedTime(System.currentTimeMillis());
                String json = JsonUtil.toJson(data);
                Path file = getFilePath(data.getAttemptId());
                Path tempFile = storageDir.resolve("attempt_" + data.getAttemptId() + ".tmp");
                Files.writeString(tempFile, json, StandardCharsets.UTF_8);
                // Atomic replace
                Files.move(tempFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                log.debug("Successfully saved local attempt snapshot for attempt {}", data.getAttemptId());
            } catch (Exception e) {
                log.error("Failed to save local snapshot for attempt {}", data.getAttemptId(), e);
            }
        }, ioExecutor);
    }

    /**
     * Synchronously load snapshot by attemptId.
     */
    public Optional<LocalAttemptData> loadAttempt(String attemptId) {
        try {
            Path file = getFilePath(attemptId);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.ofNullable(JsonUtil.fromJson(json, LocalAttemptData.class));
        } catch (Exception e) {
            log.error("Failed to load attempt from disk: {}", attemptId, e);
            return Optional.empty();
        }
    }

    /**
     * Search storage directory for an incomplete attempt.
     */
    public Optional<LocalAttemptData> findLatestIncompleteAttempt() {
        try {
            if (!Files.exists(storageDir)) {
                return Optional.empty();
            }
            File[] files = storageDir.toFile().listFiles((dir, name) -> name.startsWith("attempt_") && name.endsWith(".json"));
            if (files == null || files.length == 0) {
                return Optional.empty();
            }

            LocalAttemptData latest = null;
            for (File file : files) {
                try {
                    String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    LocalAttemptData data = JsonUtil.fromJson(json, LocalAttemptData.class);
                    if (data != null && !data.isCompleted()) {
                        if (latest == null || data.getLastSavedTime() > latest.getLastSavedTime()) {
                            latest = data;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            return Optional.ofNullable(latest);
        } catch (Exception e) {
            log.error("Error searching for incomplete attempts", e);
            return Optional.empty();
        }
    }

    /**
     * Delete or mark completed when submitted.
     */
    public void deleteAttempt(String attemptId) {
        try {
            Path file = getFilePath(attemptId);
            Files.deleteIfExists(file);
            log.debug("Deleted local attempt file for attempt {}", attemptId);
        } catch (IOException e) {
            log.warn("Failed to delete local attempt file for attempt {}", attemptId, e);
        }
    }

    public void shutdown() {
        ioExecutor.shutdown();
    }
}
