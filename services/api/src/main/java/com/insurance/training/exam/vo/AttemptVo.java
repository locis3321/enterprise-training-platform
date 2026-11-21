package com.insurance.training.exam.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试尝试视图（学员端）。
 *
 * <p>**绝不含正确答案**：{@link QuestionItem} 只有题干、题型、选项内容与分值。
 * 判分只在服务端进行，交卷后才返回得分。
 *
 * <p>{@code passScore} 是百分比，界面需同时展示
 * 「得分 / 总分」与百分比，避免只看到 {@code 45/50} 误判不及格。
 */
public record AttemptVo(
        long attemptId,
        long paperId,
        String status,
        LocalDateTime startedAt,
        LocalDateTime deadlineAt,
        LocalDateTime submittedAt,
        Integer scoreSum,
        Integer totalScore,
        Integer passScore,
        boolean passed,
        boolean autoSubmitted,
        /** true 表示本次是恢复既有进行中 attempt，而非新开一场。 */
        boolean resumed,
        List<QuestionItem> questions,
        List<AnswerItem> answers
) {

    public record QuestionItem(
            long questionId,
            String stem,
            String type,
            List<OptionView> options,
            Integer score
    ) {
    }

    /** 选项视图：只有标签与内容，不含正确与否。 */
    public record OptionView(String label, String content) {
    }

    public record AnswerItem(long questionId, List<String> labels) {
    }
}
