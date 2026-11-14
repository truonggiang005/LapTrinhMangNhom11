package vn.edu.ptit.exam.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.OptionDto;
import vn.edu.ptit.exam.common.dto.QuestionDto;
import vn.edu.ptit.exam.common.model.Question;
import vn.edu.ptit.exam.common.model.QuestionOption;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.service.ExamEngine;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExamEngineTest {

    @Test
    @DisplayName("Invariant 1 & 2: Shuffling must be 100% deterministic with seed, and DTOs must never contain correct answer")
    void testDeterministicShuffling() {
        List<Question> questions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Question q = new Question("q-" + i, "Subject", 1, "MEDIUM", "Question content " + i, false, 1);
            List<QuestionOption> opts = new ArrayList<>();
            opts.add(new QuestionOption("opt-" + i + "-A", "q-" + i, "A", "Answer A", i % 4 == 1, 1));
            opts.add(new QuestionOption("opt-" + i + "-B", "q-" + i, "B", "Answer B", i % 4 == 2, 2));
            opts.add(new QuestionOption("opt-" + i + "-C", "q-" + i, "C", "Answer C", i % 4 == 3, 3));
            opts.add(new QuestionOption("opt-" + i + "-D", "q-" + i, "D", "Answer D", i % 4 == 0, 4));
            q.setOptions(opts);
            questions.add(q);
        }

        long seed = 9876543210L;

        // Build exam on "Server 1"
        List<QuestionDto> server1Exam = ExamEngine.buildShuffledQuestions(questions, seed, null);

        // Build exam on "Server 2" with reverse initial question list
        List<Question> reversedList = new ArrayList<>(questions);
        Collections.reverse(reversedList);
        List<QuestionDto> server2Exam = ExamEngine.buildShuffledQuestions(reversedList, seed, null);

        // 1. Invariant 2: Verify question order is 100% identical on both servers
        assertEquals(server1Exam.size(), server2Exam.size());
        for (int i = 0; i < server1Exam.size(); i++) {
            QuestionDto q1 = server1Exam.get(i);
            QuestionDto q2 = server2Exam.get(i);
            assertEquals(q1.getId(), q2.getId(), "Question ID at index " + i + " must match");
            assertEquals(q1.getContent(), q2.getContent());

            // Verify option order is also 100% identical
            assertEquals(q1.getOptions().size(), q2.getOptions().size());
            for (int j = 0; j < q1.getOptions().size(); j++) {
                OptionDto opt1 = q1.getOptions().get(j);
                OptionDto opt2 = q2.getOptions().get(j);
                assertEquals(opt1.getId(), opt2.getId(), "Option ID at index " + j + " must match");
                assertEquals(opt1.getContent(), opt2.getContent());
            }
        }

        // 2. Invariant 1: Verify serialized JSON sent to client contains NO correct answer indicator
        String json = JsonUtil.toJson(server1Exam);
        assertFalse(json.contains("is_correct"), "JSON must NOT contain 'is_correct'");
        assertFalse(json.contains("isCorrect"), "JSON must NOT contain 'isCorrect'");
        assertFalse(json.contains("correct"), "JSON must NOT leak correct answers");
    }

    @Test
    @DisplayName("Invariant 5: Deterministic scoring with 2-decimal rounding")
    void testDeterministicScoring() {
        Map<String, String> answerKey = new HashMap<>();
        answerKey.put("q-1", "opt-1-B");
        answerKey.put("q-2", "opt-2-C");
        answerKey.put("q-3", "opt-3-A");

        // 2 out of 3 correct: 2/3 * 10 = 6.6666... -> rounded to 6.67
        Map<String, AnswerItemDto> studentAnswers = new HashMap<>();
        studentAnswers.put("q-1", new AnswerItemDto("q-1", "opt-1-B", false)); // correct
        studentAnswers.put("q-2", new AnswerItemDto("q-2", "opt-2-C", false)); // correct
        studentAnswers.put("q-3", new AnswerItemDto("q-3", "opt-3-D", false)); // wrong

        ExamEngine.ScoringResult result = ExamEngine.calculateScore(answerKey, studentAnswers, 10.0);
        assertEquals(2, result.getCorrectCount());
        assertEquals(3, result.getTotalQuestions());
        assertEquals(6.67, result.getScore(), 0.001);

        // All empty: 0.00
        ExamEngine.ScoringResult emptyResult = ExamEngine.calculateScore(answerKey, Collections.emptyMap(), 10.0);
        assertEquals(0, emptyResult.getCorrectCount());
        assertEquals(0.00, emptyResult.getScore(), 0.001);

        // 10 out of 10 correct: 10.00
        studentAnswers.put("q-3", new AnswerItemDto("q-3", "opt-3-A", false)); // corrected
        ExamEngine.ScoringResult perfectResult = ExamEngine.calculateScore(answerKey, studentAnswers, 10.0);
        assertEquals(3, perfectResult.getCorrectCount());
        assertEquals(10.00, perfectResult.getScore(), 0.001);
    }
}
