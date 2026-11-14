package vn.edu.ptit.exam.server.lb;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Representation and real-time operational state of a business server in the Load Balancer.
 */
public class BackendServerInfo {

    public static final long WARMUP_DURATION_MS = 30_000L; // 30 seconds

    private final int serverId;
    private final String host;
    private final int port;

    private volatile ServerStatus status = ServerStatus.UNKNOWN;
    private volatile int baseWeight; // Server 1 = 1, Server 2-4 = 2
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private volatile int consecutivePongs = 0;
    private volatile int consecutiveFails = 0;
    private volatile long upSinceTime = 0L;
    private volatile long lastPongTime = 0L;
    private volatile long clockSkewMs = 0L;
    private volatile long avgResponseTimeMs = 0L;

    public BackendServerInfo(int serverId, String host, int port, int baseWeight) {
        this.serverId = serverId;
        this.host = host;
        this.port = port;
        this.baseWeight = baseWeight;
    }

    /**
     * Calculates the effective weight considering the 30-second warm-up period.
     * When a server transitions to UP, its effective weight scales linearly from ~0 to baseWeight over 30 seconds.
     */
    public double getEffectiveWeight() {
        if (status != ServerStatus.UP) {
            return 0.0;
        }
        long now = System.currentTimeMillis();
        long uptime = now - upSinceTime;
        if (uptime >= WARMUP_DURATION_MS || upSinceTime == 0L) {
            return baseWeight;
        }
        double factor = Math.max(0.05, (double) uptime / WARMUP_DURATION_MS);
        return Math.max(0.1, baseWeight * factor);
    }

    public synchronized void recordPong(long serverTime, long rttMs, long localTime) {
        this.lastPongTime = localTime;
        this.consecutiveFails = 0;
        this.consecutivePongs++;
        this.clockSkewMs = serverTime - localTime;

        // Exponential moving average for response time
        if (this.avgResponseTimeMs == 0) {
            this.avgResponseTimeMs = rttMs;
        } else {
            this.avgResponseTimeMs = (long) (this.avgResponseTimeMs * 0.8 + rttMs * 0.2);
        }

        // Rule: 3 consecutive PONGs -> transition to UP
        if (consecutivePongs >= 3 && status != ServerStatus.UP) {
            this.status = ServerStatus.UP;
            this.upSinceTime = System.currentTimeMillis();
        }
    }

    public synchronized void recordFailure() {
        this.consecutivePongs = 0;
        this.consecutiveFails++;

        // Rule: 3 consecutive missed heartbeats -> transition to DOWN
        if (consecutiveFails >= 3 && status != ServerStatus.DOWN) {
            this.status = ServerStatus.DOWN;
            this.upSinceTime = 0L;
        }
    }

    public void forceStatus(ServerStatus newStatus) {
        this.status = newStatus;
        if (newStatus == ServerStatus.UP) {
            this.upSinceTime = System.currentTimeMillis();
            this.consecutivePongs = 3;
            this.consecutiveFails = 0;
        } else if (newStatus == ServerStatus.DOWN) {
            this.upSinceTime = 0L;
            this.consecutivePongs = 0;
            this.consecutiveFails = 3;
        }
    }

    public int getServerId() {
        return serverId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public ServerStatus getStatus() {
        return status;
    }

    public void setStatus(ServerStatus status) {
        this.status = status;
    }

    public int getBaseWeight() {
        return baseWeight;
    }

    public void setBaseWeight(int baseWeight) {
        this.baseWeight = baseWeight;
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public void incrementConnections() {
        activeConnections.incrementAndGet();
    }

    public void decrementConnections() {
        activeConnections.updateAndGet(c -> Math.max(0, c - 1));
    }

    public int getConsecutivePongs() {
        return consecutivePongs;
    }

    public int getConsecutiveFails() {
        return consecutiveFails;
    }

    public long getUpSinceTime() {
        return upSinceTime;
    }

    public void setUpSinceTime(long upSinceTime) {
        this.upSinceTime = upSinceTime;
    }

    public long getLastPongTime() {
        return lastPongTime;
    }

    public long getClockSkewMs() {
        return clockSkewMs;
    }

    public long getAvgResponseTimeMs() {
        return avgResponseTimeMs;
    }
}
