package vn.edu.ptit.exam.tools;

import com.fasterxml.jackson.databind.JsonNode;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

/**
 * Interactive command-line client to test TCP server commands (PING, LOGIN, LIST_EXAMS).
 */
public class CommandLineClient {

    private static String currentToken = null;
    private static String currentUsername = null;
    private static String currentRole = null;

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 9001;

        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
            }
        }

        System.out.println("==================================================================");
        System.out.println("  EXAM SYSTEM - COMMAND LINE CLIENT (Giai doan 2)");
        System.out.println("  Connecting to " + host + ":" + port + "...");
        System.out.println("==================================================================");

        try (Socket socket = new Socket(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("[OK] Connected to Server successfully!");
            printHelp();

            while (true) {
                System.out.print("\n[Client" + (currentUsername != null ? " (" + currentUsername + ")" : "") + "]> ");
                String input = scanner.nextLine();
                if (input == null) break;
                input = input.trim();
                if (input.isEmpty()) continue;

                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                    System.out.println("Exiting client. Goodbye!");
                    break;
                } else if ("help".equalsIgnoreCase(input)) {
                    printHelp();
                    continue;
                } else if ("ping".equalsIgnoreCase(input)) {
                    Message pingMsg = Message.request(MessageType.PING, UUID.randomUUID().toString());
                    sendAndReceive(pingMsg, writer, reader);
                } else if (input.startsWith("login")) {
                    String[] parts = input.split("\\s+");
                    if (parts.length < 3) {
                        System.out.println("Cu phap: login <username> <password>");
                        System.out.println("Vi du:  login sv_01 123456");
                        continue;
                    }
                    String username = parts[1];
                    String password = parts[2];

                    Message loginMsg = Message.request(MessageType.LOGIN, UUID.randomUUID().toString());
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("username", username);
                    payload.put("password", password);
                    loginMsg.setPayload(payload);

                    Message response = sendAndReceive(loginMsg, writer, reader);
                    if (response != null && response.isOk()) {
                        currentToken = response.getToken();
                        if (response.getPayload() != null) {
                            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(response.getPayload()));
                            currentUsername = node.has("username") ? node.get("username").asText() : username;
                            currentRole = node.has("role") ? node.get("role").asText() : "";
                            String fullName = node.has("fullName") ? node.get("fullName").asText() : "";
                            System.out.println("-> Dang nhap thanh cong: " + fullName + " (" + currentRole + ")");
                        }
                    }
                } else if (input.startsWith("list")) {
                    if (currentToken == null) {
                        System.out.println("[!] Ban chua dang nhap. Vui long dang nhap bang lenh: login <username> <password>");
                        continue;
                    }

                    String[] parts = input.split("\\s+");
                    String keyword = parts.length > 1 && !"-".equals(parts[1]) ? parts[1] : null;
                    String status = parts.length > 2 && !"-".equals(parts[2]) ? parts[2] : null;
                    int page = parts.length > 3 ? Integer.parseInt(parts[3]) : 1;
                    int pageSize = parts.length > 4 ? Integer.parseInt(parts[4]) : 10;

                    Message listMsg = Message.request(MessageType.LIST_EXAMS, UUID.randomUUID().toString());
                    listMsg.setToken(currentToken);
                    Map<String, Object> payload = new HashMap<>();
                    if (keyword != null) payload.put("keyword", keyword);
                    if (status != null) payload.put("status", status);
                    payload.put("page", page);
                    payload.put("pageSize", pageSize);
                    listMsg.setPayload(payload);

                    Message response = sendAndReceive(listMsg, writer, reader);
                    if (response != null && response.isOk() && response.getPayload() != null) {
                        printExamList(response.getPayload());
                    }
                } else if (input.startsWith("start")) {
                    if (currentToken == null) {
                        System.out.println("[!] Ban chua dang nhap. Vui long dang nhap truoc.");
                        continue;
                    }
                    String[] parts = input.split("\\s+");
                    if (parts.length < 2) {
                        System.out.println("Cu phap: start <examId>");
                        System.out.println("Vi du:  start exam-001");
                        continue;
                    }
                    String examId = parts[1];

                    Message startMsg = Message.request(MessageType.START_EXAM, UUID.randomUUID().toString());
                    startMsg.setToken(currentToken);
                    startMsg.setPayload(Map.of("examId", examId));

                    Message response = sendAndReceive(startMsg, writer, reader);
                    if (response != null && response.isOk() && response.getPayload() != null) {
                        printExamQuestions(response.getPayload());
                    }
                } else if (input.startsWith("answer")) {
                    if (currentToken == null) {
                        System.out.println("[!] Ban chua dang nhap.");
                        continue;
                    }
                    String[] parts = input.split("\\s+");
                    if (parts.length < 4) {
                        System.out.println("Cu phap: answer <attemptId> <questionId> <optionId> [marked: true/false]");
                        System.out.println("Vi du:  answer att-123 q-001 opt-001-B");
                        continue;
                    }
                    String attemptId = parts[1];
                    String qId = parts[2];
                    String optId = parts[3];
                    boolean marked = parts.length > 4 && Boolean.parseBoolean(parts[4]);

                    Message saveMsg = Message.request(MessageType.SAVE_ANSWERS, UUID.randomUUID().toString());
                    saveMsg.setToken(currentToken);
                    saveMsg.setAttemptId(attemptId);
                    saveMsg.setSeq(System.currentTimeMillis());
                    saveMsg.setPayload(Map.of(
                            "attemptId", attemptId,
                            "answers", List.of(Map.of("questionId", qId, "selectedOptionId", optId, "isMarked", marked))
                    ));

                    sendAndReceive(saveMsg, writer, reader);
                } else if (input.startsWith("submit")) {
                    if (currentToken == null) {
                        System.out.println("[!] Ban chua dang nhap.");
                        continue;
                    }
                    String[] parts = input.split("\\s+");
                    if (parts.length < 2) {
                        System.out.println("Cu phap: submit <attemptId>");
                        continue;
                    }
                    String attemptId = parts[1];

                    // Fixed requestId format for idempotent SUBMIT: SUBMIT-<attemptId>
                    Message submitMsg = new Message(MessageType.SUBMIT.name(), "SUBMIT-" + attemptId);
                    submitMsg.setToken(currentToken);
                    submitMsg.setAttemptId(attemptId);
                    submitMsg.setPayload(Map.of("attemptId", attemptId));

                    Message response = sendAndReceive(submitMsg, writer, reader);
                    if (response != null && response.isOk() && response.getPayload() != null) {
                        printSubmitResult(response.getPayload());
                    }
                } else if (input.startsWith("result")) {
                    if (currentToken == null) {
                        System.out.println("[!] Ban chua dang nhap.");
                        continue;
                    }
                    String[] parts = input.split("\\s+");
                    if (parts.length < 2) {
                        System.out.println("Cu phap: result <attemptId>");
                        continue;
                    }
                    String attemptId = parts[1];

                    Message resMsg = Message.request(MessageType.GET_RESULT, UUID.randomUUID().toString());
                    resMsg.setToken(currentToken);
                    resMsg.setAttemptId(attemptId);

                    sendAndReceive(resMsg, writer, reader);
                } else if (input.startsWith("history")) {
                    if (currentToken == null) {
                        System.out.println("[!] Ban chua dang nhap.");
                        continue;
                    }
                    Message histMsg = Message.request(MessageType.LIST_HISTORY, UUID.randomUUID().toString());
                    histMsg.setToken(currentToken);
                    sendAndReceive(histMsg, writer, reader);
                } else if (input.startsWith("raw")) {
                    String rawJson = input.substring(3).trim();
                    writer.write(rawJson);
                    writer.write("\n");
                    writer.flush();
                    System.out.println(">> Gui: " + rawJson);
                    String respLine = reader.readLine();
                    System.out.println("<< Nhan: " + respLine);
                } else {
                    System.out.println("Lenh khong hop le. Go 'help' de xem danh sach lenh.");
                }
            }

        } catch (Exception e) {
            System.err.println("[ERROR] Loi ket noi: " + e.getMessage());
        }
    }

    private static Message sendAndReceive(Message request, BufferedWriter writer, BufferedReader reader) throws Exception {
        String jsonOut = JsonUtil.toJson(request);
        System.out.println(">> Gui: " + jsonOut);
        writer.write(jsonOut);
        writer.write("\n");
        writer.flush();

        String lineIn = reader.readLine();
        if (lineIn == null) {
            System.out.println("<< Server da ngat ket noi");
            return null;
        }
        System.out.println("<< Nhan: " + lineIn);
        return JsonUtil.fromJson(lineIn, Message.class);
    }

    private static void printExamList(Object payload) {
        try {
            JsonNode root = JsonUtil.readTree(JsonUtil.toJson(payload));
            int total = root.has("total") ? root.get("total").asInt() : 0;
            int page = root.has("page") ? root.get("page").asInt() : 1;
            int pageSize = root.has("pageSize") ? root.get("pageSize").asInt() : 10;

            System.out.println("\n-------------------------------------------------------------------------------------------------");
            System.out.printf("  DANH SACH CA THI (Tong so: %d | Trang %d/%d)\n", total, page, (int) Math.ceil((double) total / pageSize));
            System.out.println("-------------------------------------------------------------------------------------------------");
            System.out.printf("| %-10s | %-32s | %-16s | %-6s | %-12s |\n", "Ma ca thi", "Ten ca thi", "Mon hoc", "Phut", "Trang thai");
            System.out.println("-------------------------------------------------------------------------------------------------");

            if (root.has("exams") && root.get("exams").isArray()) {
                for (JsonNode e : root.get("exams")) {
                    String id = e.has("id") ? e.get("id").asText() : "";
                    String title = e.has("title") ? e.get("title").asText() : "";
                    String subject = e.has("subject") ? e.get("subject").asText() : "";
                    int duration = e.has("durationMinutes") ? e.get("durationMinutes").asInt() : 0;
                    String status = e.has("status") ? e.get("status").asText() : "";
                    System.out.printf("| %-10s | %-32s | %-16s | %-6d | %-12s |\n", id, truncate(title, 32), truncate(subject, 16), duration, status);
                }
            }
            System.out.println("-------------------------------------------------------------------------------------------------");
        } catch (Exception e) {
            System.out.println("Khong the parse danh sach ca thi: " + e.getMessage());
        }
    }

    private static void printExamQuestions(Object payload) {
        try {
            JsonNode root = JsonUtil.readTree(JsonUtil.toJson(payload));
            String attemptId = root.has("attemptId") ? root.get("attemptId").asText() : "";
            String examTitle = root.has("examTitle") ? root.get("examTitle").asText() : "";
            long remSec = root.has("remainingSeconds") ? root.get("remainingSeconds").asLong() : 0;
            boolean isRecovered = root.has("recovered") && root.get("recovered").asBoolean();

            System.out.println("\n==================================================================");
            System.out.println("  VAO THI THANH CONG: " + examTitle);
            System.out.println("  Attempt ID: " + attemptId + (isRecovered ? " (KHOI PHUC BAI DANG LAM)" : ""));
            System.out.printf("  Thoi gian con lai: %d phut %d giay\n", remSec / 60, remSec % 60);
            System.out.println("==================================================================");

            if (root.has("questions") && root.get("questions").isArray()) {
                for (JsonNode q : root.get("questions")) {
                    int order = q.has("orderNum") ? q.get("orderNum").asInt() : 0;
                    String qId = q.has("id") ? q.get("id").asText() : "";
                    String content = q.has("content") ? q.get("content").asText() : "";
                    String selectedOpt = q.has("selectedOptionId") ? q.get("selectedOptionId").asText() : null;

                    System.out.printf("\nCau %d (%s)%s: %s\n", order, qId, (selectedOpt != null ? " [Da chon: " + selectedOpt + "]" : ""), content);
                    if (q.has("options") && q.get("options").isArray()) {
                        for (JsonNode opt : q.get("options")) {
                            String optId = opt.has("id") ? opt.get("id").asText() : "";
                            String key = opt.has("optionKey") ? opt.get("optionKey").asText() : "";
                            String optContent = opt.has("content") ? opt.get("content").asText() : "";
                            System.out.printf("   [%s] %s (%s)\n", key, optContent, optId);
                        }
                    }
                }
            }
            System.out.println("\nGo 'answer <attemptId> <questionId> <optionId>' de chon dap an.");
            System.out.println("Go 'submit <attemptId>' de nop bai.");
        } catch (Exception e) {
            System.out.println("Khong the parse de thi: " + e.getMessage());
        }
    }

    private static void printSubmitResult(Object payload) {
        try {
            JsonNode root = JsonUtil.readTree(JsonUtil.toJson(payload));
            String attemptId = root.has("attemptId") ? root.get("attemptId").asText() : "";
            Double score = root.has("score") && !root.get("score").isNull() ? root.get("score").asDouble() : null;
            int correct = root.has("correctCount") ? root.get("correctCount").asInt() : 0;
            int total = root.has("totalQuestions") ? root.get("totalQuestions").asInt() : 0;
            boolean isLate = root.has("late") && root.get("late").asBoolean();

            System.out.println("\n==================================================================");
            System.out.println("  KET QUA NOP BAI THI");
            System.out.println("  Attempt ID: " + attemptId);
            if (score != null) {
                System.out.printf("  DIEM THI: %.2f / 10.00\n", score);
                System.out.printf("  So cau dung: %d / %d\n", correct, total);
            } else {
                System.out.println("  DIEM THI: [Che do an diem theo quy che ca thi]");
            }
            if (isLate) {
                System.out.println("  CHU Y: Bai thi duoc ghi nhan NOP MUON (Sau deadline)");
            }
            System.out.println("==================================================================");
        } catch (Exception e) {
            System.out.println("Khong the parse ket qua nop: " + e.getMessage());
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 3) + "...";
    }

    private static void printHelp() {
        System.out.println("\nDanh sach lenh:");
        System.out.println("  ping                                    - Gui ban tin PING toi Server");
        System.out.println("  login <username> <password>             - Dang nhap (vd: login sv_01 123456)");
        System.out.println("  list [keyword] [status] [page] [size]   - Xem danh sach ca thi (vd: list - OPEN 1 10)");
        System.out.println("  start <examId>                          - Bat dau vao thi / khoi phuc bai dang lam");
        System.out.println("  answer <attemptId> <qId> <optId> [flag] - Luu dap an tra loi cho cau hoi");
        System.out.println("  submit <attemptId>                      - Nop bai thi va cham diem");
        System.out.println("  result <attemptId>                      - Xem ket qua chi tiet bai thi");
        System.out.println("  history                                 - Xem lich su cac bai thi da nop");
        System.out.println("  raw <json>                              - Gui truc tiep dong JSON tuy y");
        System.out.println("  help                                    - Xem tro giup");
        System.out.println("  exit                                    - Thoat chuong trinh");
    }
}
