package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnswerItemDto {

    private String questionId;
    private String selectedOptionId;
    private boolean isMarked;

    public AnswerItemDto() {
    }

    public AnswerItemDto(String questionId, String selectedOptionId, boolean isMarked) {
        this.questionId = questionId;
        this.selectedOptionId = selectedOptionId;
        this.isMarked = isMarked;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
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
}
