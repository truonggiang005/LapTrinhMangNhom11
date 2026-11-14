package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Option item sent to Client.
 * INVARIANT 1: Does NOT contain correct answer field during exam for students!
 * May contain isCorrect for lecturer CRUD operations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OptionDto {

    private String id;
    private String optionKey; // A, B, C, D
    private String content;
    private Boolean isCorrect;

    public OptionDto() {
    }

    public OptionDto(String id, String optionKey, String content) {
        this.id = id;
        this.optionKey = optionKey;
        this.content = content;
    }

    public OptionDto(String id, String optionKey, String content, Boolean isCorrect) {
        this.id = id;
        this.optionKey = optionKey;
        this.content = content;
        this.isCorrect = isCorrect;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOptionKey() {
        return optionKey;
    }

    public void setOptionKey(String optionKey) {
        this.optionKey = optionKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getIsCorrect() {
        return isCorrect;
    }

    public void setIsCorrect(Boolean correct) {
        isCorrect = correct;
    }
}
