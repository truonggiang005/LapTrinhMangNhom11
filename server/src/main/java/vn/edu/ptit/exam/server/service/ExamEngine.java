package vn.edu.ptit.exam.server.service;

import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.OptionDto;
import vn.edu.ptit.exam.common.dto.QuestionDto;
import vn.edu.ptit.exam.common.model.Question;
import vn.edu.ptit.exam.common.model.QuestionOption;

import java.util.*;

/**
 * Core engine for deterministic question shuffling and deterministic scoring.
 * Invariant 1: Client question DTOs NEVER contain correct answer flags.
 * Invariant 2: Shuffling is 100% deterministic using Random(seed).
 * Invariant 5: Deterministic scoring with 2-decimal rounding.
 */
public class ExamEngine {

    /**
     * Builds and shuffles questions and their options deterministically using Random(seed).
     * Any server with the same questions and seed produces the EXACT same exam layout.
     */
    public static List<QuestionDto> buildShuffledQuestions(List<Question> originalQuestions, long seed,
                                                           Map<String, AnswerItemDto> savedAnswers) {
        if (originalQuestions == null || originalQuestions.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Sort questions by ID to guarantee identical starting order across all 4 servers
        List<Question> sortedQuestions = new ArrayList<>(originalQuestions);
        sortedQuestions.sort(Comparator.comparing(Question::getId));

        // 2. Initialize Random with the deterministic seed
        Random random = new Random(seed);

        // 3. Shuffle questions
        Collections.shuffle(sortedQuestions, random);

        // 4. Construct QuestionDto list
        List<QuestionDto> dtoList = new ArrayList<>(sortedQuestions.size());
        String[] standardKeys = {"A", "B", "C", "D", "E", "F"};

        for (int i = 0; i < sortedQuestions.size(); i++) {
            Question q = sortedQuestions.get(i);

            // Sort options by ID before shuffling to guarantee identical starting order
            List<QuestionOption> sortedOptions = new ArrayList<>(q.getOptions());
            sortedOptions.sort(Comparator.comparing(QuestionOption::getId));

            // Shuffle options with the same Random sequence
            Collections.shuffle(sortedOptions, random);

            List<OptionDto> optionDtos = new ArrayList<>(sortedOptions.size());
            for (int j = 0; j < sortedOptions.size(); j++) {
                QuestionOption opt = sortedOptions.get(j);
                String displayKey = j < standardKeys.length ? standardKeys[j] : String.valueOf((char)('A' + j));
                // Invariant 1: OptionDto only has id, optionKey, and content (NO is_correct!)
                optionDtos.add(new OptionDto(opt.getId(), displayKey, opt.getContent()));
            }

            QuestionDto qDto = new QuestionDto(q.getId(), i + 1, q.getContent(), optionDtos);

            // Attach saved answers if recovering
            if (savedAnswers != null && savedAnswers.containsKey(q.getId())) {
                AnswerItemDto saved = savedAnswers.get(q.getId());
                qDto.setSelectedOptionId(saved.getSelectedOptionId());
                qDto.setMarked(saved.isMarked());
            }

            dtoList.add(qDto);
        }

        return dtoList;
    }

    /**
     * Invariant 5: Calculates score deterministically.
     * score = (correct_count / total_questions) * max_score, rounded to 2 decimal places.
     */
    public static ScoringResult calculateScore(Map<String, String> answerKey,
                                               Map<String, AnswerItemDto> studentAnswers,
                                               double maxScore) {
        if (answerKey == null || answerKey.isEmpty()) {
            return new ScoringResult(0.0, 0, 0);
        }

        int totalQuestions = answerKey.size();
        int correctCount = 0;

        for (Map.Entry<String, String> entry : answerKey.entrySet()) {
            String questionId = entry.getKey();
            String correctOptionId = entry.getValue();

            AnswerItemDto studentAnswer = studentAnswers != null ? studentAnswers.get(questionId) : null;
            if (studentAnswer != null && correctOptionId != null &&
                    correctOptionId.equals(studentAnswer.getSelectedOptionId())) {
                correctCount++;
            }
        }

        double rawScore = ((double) correctCount / totalQuestions) * maxScore;
        double roundedScore = Math.round(rawScore * 100.0) / 100.0;

        return new ScoringResult(roundedScore, correctCount, totalQuestions);
    }

    public static class ScoringResult {
        private final double score;
        private final int correctCount;
        private final int totalQuestions;

        public ScoringResult(double score, int correctCount, int totalQuestions) {
            this.score = score;
            this.correctCount = correctCount;
            this.totalQuestions = totalQuestions;
        }

        public double getScore() {
            return score;
        }

        public int getCorrectCount() {
            return correctCount;
        }

        public int getTotalQuestions() {
            return totalQuestions;
        }
    }
}
