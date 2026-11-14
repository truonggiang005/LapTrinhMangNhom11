package vn.edu.ptit.exam.server.net;

import vn.edu.ptit.exam.common.dto.AuthToken;

import java.net.Socket;

/**
 * Context of a connected client connection.
 */
public class SocketContext {

    private final String connectionId;
    private final Socket socket;
    private final String remoteAddress;
    private AuthToken authenticatedToken;

    public SocketContext(String connectionId, Socket socket) {
        this.connectionId = connectionId;
        this.socket = socket;
        this.remoteAddress = socket.getRemoteSocketAddress() != null 
                ? socket.getRemoteSocketAddress().toString() 
                : "UNKNOWN";
    }

    public String getConnectionId() {
        return connectionId;
    }

    public Socket getSocket() {
        return socket;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public AuthToken getAuthenticatedToken() {
        return authenticatedToken;
    }

    public void setAuthenticatedToken(AuthToken authenticatedToken) {
        this.authenticatedToken = authenticatedToken;
    }

    public boolean isAuthenticated() {
        return authenticatedToken != null && !authenticatedToken.isExpired();
    }
}
