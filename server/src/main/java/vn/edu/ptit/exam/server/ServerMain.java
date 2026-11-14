package vn.edu.ptit.exam.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.server.net.ExamServer;

/**
 * Entry point for starting an ExamServer instance.
 * Usage: java -jar server.jar [serverIndex 1-4]
 */
public class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    public static void main(String[] args) {
        int serverIndex = 1;
        if (args.length > 0) {
            try {
                serverIndex = Integer.parseInt(args[0]);
                if (serverIndex < 1 || serverIndex > 4) {
                    System.err.println("Server index must be between 1 and 4. Defaulting to 1.");
                    serverIndex = 1;
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid server index: " + args[0] + ". Defaulting to 1.");
                serverIndex = 1;
            }
        }

        final ExamServer server = new ExamServer(serverIndex);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered. Stopping server...");
            server.stop();
        }, "Server-Shutdown-Hook"));

        try {
            server.start();
            log.info("==================================================================");
            log.info("Server {} is RUNNING on port {}", server.getServerId(), server.getPort());
            log.info("Press Ctrl+C to stop.");
            log.info("==================================================================");
        } catch (Exception e) {
            log.error("Fatal error while starting Server {}: {}", serverIndex, e.getMessage(), e);
            System.exit(1);
        }
    }
}
