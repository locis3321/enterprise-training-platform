package com.insurance.training.exam.vo;

import com.insurance.training.exam.entity.Question;
import com.insurance.training.exam.entity.QuestionOption;

import java.util.List;

/**
 * 题目视图（管理端）。{@code referencedByPublished} 供前端在点击前禁用编辑/删除按钮，
 * 而不是等用户点了才收到 409。
 */
public record QuestionVo(
        long id,
        long courseId,
        String type,
        String stem,
        String status,
        boolean referencedByPublished,
        List<OptionItem> options
) {

    public record OptionItem(long id, String label, String content, boolean correct) {
    }

    public static QuestionVo of(Question q, List<QuestionOption> options, boolean referencedByPublished) {
        return new QuestionVo(q.getId(), q.getCourseId(), q.getType(), q.getStem(), q.getStatus(),
                referencedByPublished,
                options.stream()
                        .map(o -> new OptionItem(o.getId(), o.getLabel(), o.getContent(),
                                Integer.valueOf(1).equals(o.getIsCorrect())))
                        .toList());
    }
}
