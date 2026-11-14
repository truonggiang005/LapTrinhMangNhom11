package vn.edu.ptit.exam.client.event;

/**
 * Event listener interface for UI integration.
 * All callbacks are executed on background threads; the JavaFX UI layer
 * must use Platform.runLater(...) when updating UI controls.
 */
public interface ExamClientListener {

    /**
     * Fired when TCP connection to Load Balancer / Server is established.
     */
    void onConnected(String host, int port);

    /**
     * Fired when connection drops or is closed.
     */
    void onDisconnected(String reason);

    /**
     * Fired during offline retry cycle before next connection attempt.
     */
    void onReconnecting(int attemptCount, int delaySeconds);

    /**
     * Fired every second during an active exam with remaining seconds.
     */
    void onClockTick(long remainingSeconds);

    /**
     * Fired at key remaining time thresholds (e.g. 5 minutes, 1 minute).
     */
    void onTimeWarning(String message, long remainingSeconds);

    /**
     * Fired when remaining exam time reaches zero (triggers auto-submit).
     */
    void onTimeUp();

    /**
     * Fired when answer auto-save status changes (e.g. "Đang lưu...", "Đã lưu lúc HH:mm:ss", "Chưa lưu – đang thử lại").
     */
    void onSaveStatusChanged(String statusText, boolean isError);

    /**
     * Fired when START_EXAM request is queued at Load Balancer.
     */
    void onQueuePosition(int position, int estimatedWaitSeconds);

    /**
     * Fired when Load Balancer switches client to a backup server due to failover.
     */
    void onServerSwitching(String reason);

    /**
     * Fired when another device/client logs in with the same account (SESSION_REPLACED).
     */
    void onSessionReplaced(String reason);

    /**
     * Fired when an unexpected error or server error occurs.
     */
    void onError(int code, String message);
}
