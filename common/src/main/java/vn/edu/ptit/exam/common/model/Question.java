package vn.edu.ptit.exam.common.model;

import java.util.ArrayList;
import java.util.List;

public class Question {

    private String id;
    private String subject;
    private int chapter;
    private String difficulty;
    private String content;
    private boolean isDeleted;
    private int version;
    private List<QuestionOption> options = new ArrayList<>();

    public Question() {
    }

    public Question(String id, String subject, int chapter, String difficulty, String content, boolean isDeleted, int version) {
        this.id = id;
        this.subject = subject;
        this.chapter = chapter;
        this.difficulty = difficulty;
        this.content = content;
        this.isDeleted = isDeleted;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public int getChapter() {
        return chapter;
    }

    public void setChapter(int chapter) {
        this.chapter = chapter;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    public void setDeleted(boolean deleted) {
        isDeleted = deleted;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<QuestionOption> getOptions() {
        return options;
    }

    public void setOptions(List<QuestionOption> options) {
        this.options = options;
    }
}
