package vn.edu.ptit.exam.client.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;

import java.util.HashMap;
import java.util.Map;

/**
 * Snapshot of an active exam attempt saved to local disk for offline resilience and crash recovery.
 * Stored as attempt_<attemptId>.json.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocalAttemptData {

    private String attemptId;
    private String examId;
    private String examTitle;
    private String subject;
    private int durationMinutes;
    private long startTime;
    private long deadline;
    private String token;
    private Map<String, AnswerItemDto> answers = new HashMap<>();
    private long lastSavedTime;
    private boolean isCompleted;

    public LocalAttemptData() {
    }

    public LocalAttemptData(String attemptId, String examId, String examTitle, String subject,
                            int durationMinutes, long startTime, long deadline, String token) {
        this.attemptId = attemptId;
        this.examId = examId;
        this.examTitle = examTitle;
        this.subject = subject;
        this.durationMinutes = durationMinutes;
        this.startTime = startTime;
        this.deadline = deadline;
        this.token = token;
        this.lastSavedTime = System.currentTimeMillis();
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public String getExamId() {
        return examId;
    }

    public void setExamId(String examId) {
        this.examId = examId;
    }

    public String getExamTitle() {
        return examTitle;
    }

    public void setExamTitle(String examTitle) {
        this.examTitle = examTitle;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getDeadline() {
        return deadline;
    }

    public void setDeadline(long deadline) {
        this.deadline = deadline;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Map<String, AnswerItemDto> getAnswers() {
        return answers;
    }

    public void setAnswers(Map<String, AnswerItemDto> answers) {
        this.answers = answers;
    }

    public long getLastSavedTime() {
        return lastSavedTime;
    }

    public void setLastSavedTime(long lastSavedTime) {
        this.lastSavedTime = lastSavedTime;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public void setCompleted(boolean completed) {
        isCompleted = completed;
    }
}
