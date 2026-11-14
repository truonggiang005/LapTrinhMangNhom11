package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExamStatsDto {

    private String examId;
    private Double averageScore;
    private Double highestScore;
    private Double lowestScore;
    private int totalAttempts;
    private Map<String, Integer> scoreDistribution; // e.g., "0-2": 5, "2-4": 10, ...
    private Map<String, Double> questionAccuracy;   // questionId -> accuracy percentage

    public ExamStatsDto() {
    }

    public ExamStatsDto(String examId, Double averageScore, Double highestScore, Double lowestScore,
                        int totalAttempts, Map<String, Integer> scoreDistribution, Map<String, Double> questionAccuracy) {
        this.examId = examId;
        this.averageScore = averageScore;
        this.highestScore = highestScore;
        this.lowestScore = lowestScore;
        this.totalAttempts = totalAttempts;
        this.scoreDistribution = scoreDistribution;
        this.questionAccuracy = questionAccuracy;
    }

    public String getExamId() {
        return examId;
    }

    public void setExamId(String examId) {
        this.examId = examId;
    }

    public Double getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(Double averageScore) {
        this.averageScore = averageScore;
    }

    public Double getHighestScore() {
        return highestScore;
    }

    public void setHighestScore(Double highestScore) {
        this.highestScore = highestScore;
    }

    public Double getLowestScore() {
        return lowestScore;
    }

    public void setLowestScore(Double lowestScore) {
        this.lowestScore = lowestScore;
    }

    public int getTotalAttempts() {
        return totalAttempts;
    }

    public void setTotalAttempts(int totalAttempts) {
        this.totalAttempts = totalAttempts;
    }

    public Map<String, Integer> getScoreDistribution() {
        return scoreDistribution;
    }

    public void setScoreDistribution(Map<String, Integer> scoreDistribution) {
        this.scoreDistribution = scoreDistribution;
    }

    public Map<String, Double> getQuestionAccuracy() {
        return questionAccuracy;
    }

    public void setQuestionAccuracy(Map<String, Double> questionAccuracy) {
        this.questionAccuracy = questionAccuracy;
    }
}
