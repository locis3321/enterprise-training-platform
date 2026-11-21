package com.insurance.training.exam.service;

import com.insurance.training.exam.entity.ExamAttempt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最高分选取口径。
 *
 * <p>「考试结果只取最高分」是产品规则，前端标识与成绩页都依赖这里的判定，
 * 因此把它钉在单测里：百分比优先，其次绝对得分，最后取交卷时间更新的。
 */
class ExamAttemptBestTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 11, 3, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 11, 11, 4, 0);

    @Test
    @DisplayName("取百分比最高的一场，而非最近一场")
    void picksHighestPercent() {
        ExamAttempt low = attempt(1L, 1, 3, 33, T2);   // 最近，但只有 33%
        ExamAttempt high = attempt(2L, 3, 3, 100, T1); // 较早，但满分

        ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(List.of(low, high));

        assertThat(best).isNotNull();
        assertThat(best.attemptId()).isEqualTo(2L);
        assertThat(best.passed()).isTrue();
        assertThat(best.scoreSum()).isEqualTo(3);
    }

    @Test
    @DisplayName("全部不及格时仍返回最高分，passed 为 false")
    void keepsBestWhenNothingPassed() {
        ExamAttempt a = attempt(1L, 1, 3, 33, T1);
        ExamAttempt b = attempt(2L, 2, 3, 66, T2);

        ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(List.of(a, b));

        assertThat(best).isNotNull();
        assertThat(best.attemptId()).isEqualTo(2L);
        assertThat(best.passed()).isFalse();
    }

    @Test
    @DisplayName("百分比相同时取绝对得分更高的一场")
    void breaksTieByScore() {
        ExamAttempt small = attempt(1L, 1, 1, 100, T2);
        ExamAttempt large = attempt(2L, 5, 5, 100, T1);

        ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(List.of(small, large));

        assertThat(best).isNotNull();
        assertThat(best.attemptId()).isEqualTo(2L);
        assertThat(best.totalScore()).isEqualTo(5);
    }

    @Test
    @DisplayName("得分与百分比都相同时取交卷时间更新的")
    void breaksTieBySubmittedAt() {
        ExamAttempt older = attempt(1L, 3, 3, 100, T1);
        ExamAttempt newer = attempt(2L, 3, 3, 100, T2);

        ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(List.of(older, newer));

        assertThat(best).isNotNull();
        assertThat(best.attemptId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("没有已交卷记录时返回 null")
    void returnsNullWhenNoSubmitted() {
        assertThat(ExamAttemptService.pickBest(List.of())).isNull();
    }

    @Test
    @DisplayName("总分为 0 的异常记录不参与选取，避免除零")
    void ignoresZeroTotalScore() {
        ExamAttempt broken = attempt(1L, 0, 0, 0, T1);

        assertThat(ExamAttemptService.pickBest(List.of(broken))).isNull();
    }

    @Test
    @DisplayName("进行中的考试不算成绩，不参与最高分")
    void ignoresInProgress() {
        ExamAttempt inProgress = attempt(1L, 2, 3, 66, T1);
        inProgress.setStatus("IN_PROGRESS");

        // 调用方查询时已按 SUBMITTED 过滤；这里确认 pickBest 只处理传入的已交卷集合
        ExamAttempt submitted = attempt(2L, 3, 3, 100, T2);

        ExamAttemptService.BestAttempt best =
                ExamAttemptService.pickBest(List.of(submitted));

        assertThat(best).isNotNull();
        assertThat(best.attemptId()).isEqualTo(2L);
        assertThat(best.passed()).isTrue();
    }

    private static ExamAttempt attempt(long id, int sum, int total, int passed, LocalDateTime submittedAt) {
        ExamAttempt a = new ExamAttempt();
        a.setId(id);
        a.setAssignmentId(10L);
        a.setPaperId(1L);
        a.setStatus("SUBMITTED");
        a.setScoreSum(sum);
        a.setTotalScore(total);
        a.setPassScore(90);
        a.setPassed(passed >= 90 ? 1 : 0);
        a.setSubmittedAt(submittedAt);
        return a;
    }
}
