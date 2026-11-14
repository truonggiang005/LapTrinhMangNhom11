package vn.edu.ptit.exam.client.event;

/**
 * Adapter class implementing {@link ExamClientListener} with empty methods.
 * UI components can extend this adapter and override only the events they care about.
 */
public abstract class ExamClientAdapter implements ExamClientListener {

    @Override
    public void onConnected(String host, int port) {
    }

    @Override
    public void onDisconnected(String reason) {
    }

    @Override
    public void onReconnecting(int attemptCount, int delaySeconds) {
    }

    @Override
    public void onClockTick(long remainingSeconds) {
    }

    @Override
    public void onTimeWarning(String message, long remainingSeconds) {
    }

    @Override
    public void onTimeUp() {
    }

    @Override
    public void onSaveStatusChanged(String statusText, boolean isError) {
    }

    @Override
    public void onQueuePosition(int position, int estimatedWaitSeconds) {
    }

    @Override
    public void onServerSwitching(String reason) {
    }

    @Override
    public void onSessionReplaced(String reason) {
    }

    @Override
    public void onError(int code, String message) {
    }
}
