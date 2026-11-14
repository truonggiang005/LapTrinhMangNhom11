package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateExamRequest {

    private String id; // Optional (generated if null)
    private String title;
    private String subject;
    private int durationMinutes;
    private long startTime;
    private long endTime;
    private String examType = "FIXED"; // "FIXED" or "RANDOM_STRUCTURE"
    private double maxScore = 10.0;
    private String showScoreType = "IMMEDIATE"; // "IMMEDIATE", "AFTER_EXAM_CLOSED", "NEVER"

    private List<String> questionIds = new ArrayList<>();
    private List<StructureRule> structureRules = new ArrayList<>();
    private List<String> assignedStudentIds = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StructureRule {
        private Integer chapter;
        private String difficulty; // "EASY", "MEDIUM", "HARD"
        private int count;

        public StructureRule() {
        }

        public StructureRule(Integer chapter, String difficulty, int count) {
            this.chapter = chapter;
            this.difficulty = difficulty;
            this.count = count;
        }

        public Integer getChapter() {
            return chapter;
        }

        public void setChapter(Integer chapter) {
            this.chapter = chapter;
        }

        public String getDifficulty() {
            return difficulty;
        }

        public void setDifficulty(String difficulty) {
            this.difficulty = difficulty;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    public CreateExamRequest() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public String getExamType() {
        return examType;
    }

    public void setExamType(String examType) {
        this.examType = examType;
    }

    public double getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(double maxScore) {
        this.maxScore = maxScore;
    }

    public String getShowScoreType() {
        return showScoreType;
    }

    public void setShowScoreType(String showScoreType) {
        this.showScoreType = showScoreType;
    }

    public List<String> getQuestionIds() {
        return questionIds;
    }

    public void setQuestionIds(List<String> questionIds) {
        this.questionIds = questionIds;
    }

    public List<StructureRule> getStructureRules() {
        return structureRules;
    }

    public void setStructureRules(List<StructureRule> structureRules) {
        this.structureRules = structureRules;
    }

    public List<String> getAssignedStudentIds() {
        return assignedStudentIds;
    }

    public void setAssignedStudentIds(List<String> assignedStudentIds) {
        this.assignedStudentIds = assignedStudentIds;
    }
}
