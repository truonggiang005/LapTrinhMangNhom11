package vn.edu.ptit.exam.common.model;

public class QuestionOption {

    private String id;
    private String questionId;
    private String optionKey;
    private String content;
    private boolean isCorrect;
    private int orderNum;

    public QuestionOption() {
    }

    public QuestionOption(String id, String questionId, String optionKey, String content, boolean isCorrect, int orderNum) {
        this.id = id;
        this.questionId = questionId;
        this.optionKey = optionKey;
        this.content = content;
        this.isCorrect = isCorrect;
        this.orderNum = orderNum;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
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

    public boolean isCorrect() {
        return isCorrect;
    }

    public void setCorrect(boolean correct) {
        isCorrect = correct;
    }

    public int getOrderNum() {
        return orderNum;
    }

    public void setOrderNum(int orderNum) {
        this.orderNum = orderNum;
    }
}
