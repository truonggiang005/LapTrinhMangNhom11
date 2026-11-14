package vn.edu.ptit.exam.server.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.server.dao.*;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.handler.*;
import vn.edu.ptit.exam.server.lb.LoadBalancer;
import vn.edu.ptit.exam.server.router.MessageRouter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main TCP Business Server instance (Server 1, 2, 3, or 4).
 * Runs on port 900N, manages business thread pool, dedicated PING thread,
 * background 10s expired attempts scanner, and database pool.
 */
public class ExamServer {

    private static final Logger log = LoggerFactory.getLogger(ExamServer.class);

    private final int serverIndex;
    private final String serverId;
    private final int port;

    private DatabaseManager dbManager;
    private UserDao userDao;
    private AuditLogDao auditLogDao;
    private ExamDao examDao;
    private SyncLogDao syncLogDao;
    private QuestionDao questionDao;
    private AttemptDao attemptDao;
    private SubmitHandler submitHandler;
    private MessageRouter router;

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private Thread acceptThread;

    // Business thread pool
    private ThreadPoolExecutor businessThreadPool;

    // Invariant 7: Dedicated single thread for PING/PONG handling (never blocked by business pool saturation)
    private ExecutorService pingExecutor;

    // Background scheduled scanner for expired attempts (10s interval)
    private ScheduledExecutorService expiredScanner;

    // Client tracking
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final Set<ClientSession> activeSessions = ConcurrentHashMap.newKeySet();

    // Embedded Load Balancer (Server 1 only)
    private LoadBalancer loadBalancer;

    // Known DOWN servers list (Luồng 8 Bước 9)
    private final Set<String> downServerIds = ConcurrentHashMap.newKeySet();

    // Synchronization manager
    private vn.edu.ptit.exam.server.sync.SyncManager syncManager;

    public ExamServer(int serverIndex) {
        this.serverIndex = serverIndex;
        Config config = Config.getInstance();
        this.serverId = config.getServerId(serverIndex);
        this.port = config.getServerPort(serverIndex);
    }

    public synchronized void start() throws IOException {
        if (running) {
            log.warn("Server {} is already running on port {}", serverId, port);
            return;
        }

        log.info("Starting {} on port {}...", serverId, port);
        Config config = Config.getInstance();

        // 1. Initialize Database connection pool and DAOs
        this.dbManager = new DatabaseManager(serverIndex);
        this.userDao = new UserDao(dbManager);
        this.auditLogDao = new AuditLogDao(dbManager);
        this.examDao = new ExamDao(dbManager);
        this.syncLogDao = new SyncLogDao(dbManager, serverId);
        this.questionDao = new QuestionDao(dbManager);
        this.attemptDao = new AttemptDao(dbManager, syncLogDao);

        // Start Synchronization Manager
        try {
            this.syncManager = new vn.edu.ptit.exam.server.sync.SyncManager(serverIndex, serverId, dbManager, syncLogDao.getWriter());
            this.syncManager.start();
        } catch (Exception e) {
            log.error("Failed to start SyncManager on {}: {}", serverId, e.getMessage(), e);
        }

        // 2. Initialize Handlers and Message Router
        this.router = new MessageRouter();
        this.submitHandler = new SubmitHandler(attemptDao, questionDao, auditLogDao, serverId, syncManager);

        router.registerHandler(MessageType.PING, new PingHandler(this));
        router.registerHandler(MessageType.LOGIN, new LoginHandler(userDao, auditLogDao, serverId));
        router.registerHandler(MessageType.LIST_EXAMS, new ListExamsHandler(examDao));
        router.registerHandler(MessageType.START_EXAM, new StartExamHandler(attemptDao, questionDao, serverId));
        router.registerHandler(MessageType.SAVE_ANSWERS, new SaveAnswersHandler(attemptDao));
        router.registerHandler(MessageType.SUBMIT, submitHandler);
        router.registerHandler(MessageType.GET_RESULT, new GetResultHandler(attemptDao));
        router.registerHandler(MessageType.LIST_HISTORY, new ListHistoryHandler(attemptDao));

        ResumeRequestHandler resumeHandler = new ResumeRequestHandler(attemptDao, questionDao, serverId, syncManager);
        router.registerHandler(MessageType.RESUME_REQUEST, resumeHandler);
        router.registerHandler(MessageType.RESUME_SESSION, resumeHandler);

        // Stage 8: Lecturer & Admin Handlers
        QuestionHandler questionHandler = new QuestionHandler(questionDao, syncLogDao.getWriter());
        router.registerHandler(MessageType.QUESTION_LIST, questionHandler);
        router.registerHandler(MessageType.QUESTION_CREATE, questionHandler);
        router.registerHandler(MessageType.QUESTION_UPDATE, questionHandler);
        router.registerHandler(MessageType.QUESTION_DELETE, questionHandler);

        ExamAdminHandler examAdminHandler = new ExamAdminHandler(examDao, questionDao, syncLogDao.getWriter(), syncManager, serverId);
        router.registerHandler(MessageType.EXAM_CREATE, examAdminHandler);
        router.registerHandler(MessageType.EXAM_MONITOR, examAdminHandler);
        router.registerHandler(MessageType.GET_SCORES, examAdminHandler);
        router.registerHandler(MessageType.GET_STATS, examAdminHandler);

        RetakeHandler retakeHandler = new RetakeHandler(examDao, syncLogDao.getWriter());
        router.registerHandler(MessageType.EXAM_RETAKE_REQUEST, retakeHandler);
        router.registerHandler(MessageType.EXAM_RETAKE_LIST, retakeHandler);
        router.registerHandler(MessageType.EXAM_RETAKE_DECIDE, retakeHandler);

        UserAdminHandler userAdminHandler = new UserAdminHandler(userDao, syncLogDao.getWriter());
        router.registerHandler(MessageType.USER_LIST, userAdminHandler);
        router.registerHandler(MessageType.USER_CREATE, userAdminHandler);
        router.registerHandler(MessageType.USER_UPDATE, userAdminHandler);
        router.registerHandler(MessageType.USER_RESET_PASSWORD, userAdminHandler);
        router.registerHandler(MessageType.USER_LOCK, userAdminHandler);
        router.registerHandler(MessageType.USER_BATCH, userAdminHandler);

        LogQueryHandler logQueryHandler = new LogQueryHandler(auditLogDao, syncLogDao,
                () -> (loadBalancer != null ? loadBalancer.getDistributionLogger() : null));
        router.registerHandler(MessageType.LOG_QUERY, logQueryHandler);

        // 3. Initialize Thread Pools
        // Business Thread Pool: 10 core threads, 50 max threads, 100 queue capacity
        this.businessThreadPool = new ThreadPoolExecutor(
                10, 50, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, serverId + "-Business-Worker-" + counter.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        // Dedicated PING Thread: 1 dedicated thread with highest responsiveness
        this.pingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, serverId + "-Dedicated-PING-Thread");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });

        // Background scanner for expired attempts: runs every 10s (Luồng 5 Bước 10 & Luồng 8 Bước 9)
        long scanInterval = config.getExamScanExpiredIntervalMs();
        long lateTolerance = config.getExamLateToleranceMs();
        this.expiredScanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, serverId + "-Expired-Exam-Scanner");
            t.setDaemon(true);
            return t;
        });
        this.expiredScanner.scheduleAtFixedRate(() -> {
            try {
                // 1. Scan and submit local expired attempts
                List<Attempt> expiredList = attemptDao.scanExpiredAttempts(lateTolerance);
                for (Attempt a : expiredList) {
                    submitHandler.autoSubmitExpiredAttempt(a);
                }

                // 2. Scan and reassign orphaned expired attempts from DOWN servers (Luồng 8 Bước 9)
                if (!downServerIds.isEmpty()) {
                    List<Attempt> orphaned = attemptDao.scanExpiredAttemptsForDownServers(new ArrayList<>(downServerIds), lateTolerance);
                    for (Attempt a : orphaned) {
                        boolean reassigned = attemptDao.reassignAttempt(a.getId(), serverId);
                        if (reassigned) {
                            a.setServerId(serverId);
                            submitHandler.autoSubmitExpiredAttempt(a);
                            log.info("Auto-submitted orphaned expired attempt {} of down server on {}", a.getId(), serverId);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error during scheduled expired attempt scan on {}: {}", serverId, e.getMessage());
            }
        }, scanInterval, scanInterval, TimeUnit.MILLISECONDS);

        // 4. Open TCP ServerSocket
        this.serverSocket = new ServerSocket(port);
        this.running = true;

        // 5. Start connection accept loop
        this.acceptThread = new Thread(this::acceptLoop, serverId + "-Acceptor-Thread");
        this.acceptThread.setDaemon(false);
        this.acceptThread.start();

        // 6. Start embedded Load Balancer on port 9000 if this is Server 1
        if (serverIndex == 1) {
            try {
                this.loadBalancer = new LoadBalancer();
                this.loadBalancer.start();
                log.info("Server 1 embedded Load Balancer started on port {}", loadBalancer.getPort());
            } catch (Exception e) {
                log.error("Failed to start embedded Load Balancer on Server 1: {}", e.getMessage(), e);
            }
        }

        log.info("{} successfully started and listening on port {}", serverId, port);
    }

    private void acceptLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for low-latency JSON messages

                ClientSession session = new ClientSession(clientSocket, this);
                Thread sessionThread = new Thread(session, serverId + "-Client-" + clientSocket.getRemoteSocketAddress());
                sessionThread.setDaemon(true);
                sessionThread.start();
            } catch (IOException e) {
                if (running) {
                    log.error("Error accepting connection on {}: {}", serverId, e.getMessage());
                }
            }
        }
    }

    public void submitBusinessTask(Runnable task) {
        businessThreadPool.execute(task);
    }

    public void submitPingTask(Runnable task) {
        pingExecutor.execute(task);
    }

    public void onClientConnected(ClientSession session) {
        activeSessions.add(session);
        activeConnections.incrementAndGet();
    }

    public void onClientDisconnected(ClientSession session) {
        activeSessions.remove(session);
        activeConnections.decrementAndGet();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        log.info("Stopping {}...", serverId);
        running = false;

        // Stop embedded Load Balancer if running
        if (loadBalancer != null) {
            loadBalancer.stop();
            loadBalancer = null;
        }

        // Stop SyncManager
        if (syncManager != null) {
            try {
                syncManager.stop();
            } catch (Exception e) {
                log.warn("Error stopping SyncManager on {}: {}", serverId, e.getMessage());
            }
            syncManager = null;
        }

        // Close server socket to stop accepting
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warn("Error closing server socket: {}", e.getMessage());
        }

        // Close all active client sessions
        for (ClientSession session : activeSessions) {
            session.close();
        }
        activeSessions.clear();

        // Shutdown thread pools
        if (expiredScanner != null) {
            expiredScanner.shutdownNow();
        }
        if (businessThreadPool != null) {
            businessThreadPool.shutdownNow();
        }
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
        }

        // Close DB connections
        if (dbManager != null) {
            dbManager.close();
        }

        log.info("{} has been stopped", serverId);
    }

    // --- Getters for Monitoring and Testing ---

    public int getServerIndex() {
        return serverIndex;
    }

    public String getServerId() {
        return serverId;
    }

    public LoadBalancer getLoadBalancer() {
        return loadBalancer;
    }

    public vn.edu.ptit.exam.server.sync.SyncManager getSyncManager() {
        return syncManager;
    }

    public int getPort() {
        return port;
    }

    public MessageRouter getRouter() {
        return router;
    }

    public DatabaseManager getDbManager() {
        return dbManager;
    }

    public UserDao getUserDao() {
        return userDao;
    }

    public ExamDao getExamDao() {
        return examDao;
    }

    public QuestionDao getQuestionDao() {
        return questionDao;
    }

    public AttemptDao getAttemptDao() {
        return attemptDao;
    }

    public SyncLogDao getSyncLogDao() {
        return syncLogDao;
    }

    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    public int getActiveConnectionCount() {
        return activeConnections.get();
    }

    public int getActiveBusinessThreadCount() {
        return businessThreadPool != null ? businessThreadPool.getActiveCount() : 0;
    }

    public int getBusinessQueueSize() {
        return businessThreadPool != null ? businessThreadPool.getQueue().size() : 0;
    }

    public void updateDownServers(List<String> list) {
        downServerIds.clear();
        if (list != null) {
            downServerIds.addAll(list);
        }
    }

    public Set<String> getDownServerIds() {
        return downServerIds;
    }

    public boolean isRunning() {
        return running;
    }
}
