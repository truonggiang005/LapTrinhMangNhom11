package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Question data sent to Client during exam, or used for lecturer management.
 * INVARIANT 1: Absolutely NO correct answer indicators sent to client during exam!
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionDto {

    private String id;
    private int orderNum;
    private String content;
    private List<OptionDto> options;
    private String selectedOptionId; // if previously answered/recovered
    private boolean isMarked;        // if flagged for review

    // Management fields for lecturer/admin
    private String subject;
    private Integer chapter;
    private String difficulty;
    private Integer version;

    public QuestionDto() {
    }

    public QuestionDto(String id, int orderNum, String content, List<OptionDto> options) {
        this.id = id;
        this.orderNum = orderNum;
        this.content = content;
        this.options = options;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getOrderNum() {
        return orderNum;
    }

    public void setOrderNum(int orderNum) {
        this.orderNum = orderNum;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<OptionDto> getOptions() {
        return options;
    }

    public void setOptions(List<OptionDto> options) {
        this.options = options;
    }

    public String getSelectedOptionId() {
        return selectedOptionId;
    }

    public void setSelectedOptionId(String selectedOptionId) {
        this.selectedOptionId = selectedOptionId;
    }

    public boolean isMarked() {
        return isMarked;
    }

    public void setMarked(boolean marked) {
        isMarked = marked;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
