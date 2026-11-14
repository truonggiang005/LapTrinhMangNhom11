package vn.edu.ptit.exam.server.handler;

import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.server.net.SocketContext;

/**
 * Strategy interface for handling specific Message types.
 */
public interface MessageHandler {

    /**
     * Handles an incoming message and returns the response message.
     */
    Message handle(Message request, SocketContext ctx) throws Exception;
}
