package vn.edu.ptit.exam.server.lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Implements load balancing algorithms: Round Robin and Weighted Least Connections.
 * Allows hot-swapping algorithms at runtime without disconnecting clients.
 */
public class ServerSelector {

    private static final Logger log = LoggerFactory.getLogger(ServerSelector.class);

    private volatile AlgorithmType algorithm = AlgorithmType.WEIGHTED_LEAST_CONNECTIONS;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    // Current weights for Smooth Weighted Round Robin
    private final Map<Integer, Double> currentWeights = new ConcurrentHashMap<>();

    public AlgorithmType getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(AlgorithmType algorithm) {
        if (algorithm != null) {
            log.info("Switching load balancing algorithm to: {}", algorithm);
            this.algorithm = algorithm;
            currentWeights.clear();
        }
    }

    /**
     * Selects an UP backend server according to the active algorithm.
     */
    public Optional<BackendServerInfo> selectServer(List<BackendServerInfo> allServers) {
        if (allServers == null || allServers.isEmpty()) {
            return Optional.empty();
        }

        List<BackendServerInfo> upServers = allServers.stream()
                .filter(s -> s.getStatus() == ServerStatus.UP)
                .collect(Collectors.toList());

        if (upServers.isEmpty()) {
            return Optional.empty();
        }

        if (upServers.size() == 1) {
            return Optional.of(upServers.get(0));
        }

        if (algorithm == AlgorithmType.ROUND_ROBIN) {
            return selectRoundRobin(upServers);
        } else {
            return selectWeightedLeastConnections(upServers);
        }
    }

    private synchronized Optional<BackendServerInfo> selectRoundRobin(List<BackendServerInfo> upServers) {
        // Smooth Weighted Round-Robin (Nginx algorithm)
        double totalWeight = 0.0;
        BackendServerInfo best = null;
        double maxCurrentWeight = Double.NEGATIVE_INFINITY;

        for (BackendServerInfo s : upServers) {
            double effectiveWeight = s.getEffectiveWeight();
            totalWeight += effectiveWeight;

            double current = currentWeights.getOrDefault(s.getServerId(), 0.0) + effectiveWeight;
            currentWeights.put(s.getServerId(), current);

            if (current > maxCurrentWeight) {
                maxCurrentWeight = current;
                best = s;
            }
        }

        if (best != null) {
            currentWeights.put(best.getServerId(), maxCurrentWeight - totalWeight);
            return Optional.of(best);
        }

        // Fallback simple round robin
        int idx = Math.abs(roundRobinCounter.getAndIncrement() % upServers.size());
        return Optional.of(upServers.get(idx));
    }

    private Optional<BackendServerInfo> selectWeightedLeastConnections(List<BackendServerInfo> upServers) {
        // Select UP server with smallest ratio: (activeConnections / effectiveWeight)
        return upServers.stream().min(Comparator
                .comparingDouble((BackendServerInfo s) -> (double) s.getActiveConnections() / Math.max(0.01, s.getEffectiveWeight()))
                .thenComparingInt(BackendServerInfo::getServerId)
        );
    }
}
