package com.insurance.training.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.exam.entity.ExamPaper;
import com.insurance.training.exam.entity.ExamPaperQuestion;
import com.insurance.training.exam.mapper.ExamPaperMapper;
import com.insurance.training.exam.mapper.ExamPaperQuestionMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题目「是否被已发布试卷引用」的唯一判定处。
 *
 * <p>为什么单独抽出：T1（题库）与 T2（组卷）都要用它，
 * 若各写一份必然漂移，出现「T1 允许编辑、T2 声称锁定」的矛盾。
 *
 * <p>判定口径（与任务包一致）：存在一条 {@code exam_paper_question}，
 * 其所属 {@code exam_paper.status='PUBLISHED'}。草稿试卷不锁定题目——
 * 草稿尚未生效，锁它会阻碍正常组卷调试。
 */
@Component
public class QuestionReferenceChecker {

    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamPaperMapper paperMapper;

    public QuestionReferenceChecker(ExamPaperQuestionMapper paperQuestionMapper,
                                    ExamPaperMapper paperMapper) {
        this.paperQuestionMapper = paperQuestionMapper;
        this.paperMapper = paperMapper;
    }

    public boolean isReferencedByPublished(long questionId) {
        List<Long> paperIds = paperQuestionMapper.selectList(
                        Wrappers.<ExamPaperQuestion>lambdaQuery()
                                .eq(ExamPaperQuestion::getQuestionId, questionId))
                .stream().map(ExamPaperQuestion::getPaperId).distinct().toList();
        if (paperIds.isEmpty()) {
            return false;
        }
        Long published = paperMapper.selectCount(Wrappers.<ExamPaper>lambdaQuery()
                .in(ExamPaper::getId, paperIds)
                .eq(ExamPaper::getStatus, "PUBLISHED"));
        return published != null && published > 0;
    }

    /** 批量版本：列表场景避免 N+1。 */
    public Set<Long> referencedByPublished(Set<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return Set.of();
        }
        List<ExamPaperQuestion> links = paperQuestionMapper.selectList(
                Wrappers.<ExamPaperQuestion>lambdaQuery()
                        .in(ExamPaperQuestion::getQuestionId, questionIds));
        if (links.isEmpty()) {
            return Set.of();
        }
        List<Long> paperIds = links.stream().map(ExamPaperQuestion::getPaperId).distinct().toList();
        Set<Long> publishedPaperIds = paperMapper.selectList(Wrappers.<ExamPaper>lambdaQuery()
                        .in(ExamPaper::getId, paperIds)
                        .eq(ExamPaper::getStatus, "PUBLISHED"))
                .stream().map(ExamPaper::getId).collect(Collectors.toSet());
        return links.stream()
                .filter(l -> publishedPaperIds.contains(l.getPaperId()))
                .map(ExamPaperQuestion::getQuestionId)
                .collect(Collectors.toSet());
    }
}
