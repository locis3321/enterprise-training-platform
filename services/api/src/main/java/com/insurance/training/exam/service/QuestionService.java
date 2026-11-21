package com.insurance.training.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.exam.dto.QuestionRequest;
import com.insurance.training.exam.entity.Question;
import com.insurance.training.exam.entity.QuestionOption;
import com.insurance.training.exam.mapper.QuestionMapper;
import com.insurance.training.exam.mapper.QuestionOptionMapper;
import com.insurance.training.exam.vo.QuestionVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 题库：按课程维护单选/多选题。
 *
 * <p>核心校验（业务事实，不推断）：
 * <ul>
 *   <li>选项 2–5 个，标签 A–E 按顺序生成；</li>
 *   <li>单选恰好 1 个正确答案；多选至少 2 个（1 个应改用单选，不静默转类型）。</li>
 * </ul>
 *
 * <p>引用边界（与 T2 严格对齐）：被 <b>PUBLISHED</b> 试卷引用的题目禁止编辑与删除；
 * 被 DRAFT 试卷引用则允许（草稿尚未生效）。判定下沉到
 * {@link QuestionReferenceChecker}，避免 T1/T2 两处口径漂移。
 */
@Service
public class QuestionService {

    private static final int MAX_OPTIONS = 5;
    private static final String[] LABELS = {"A", "B", "C", "D", "E"};

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final QuestionReferenceChecker referenceChecker;
    private final CourseMapper courseMapper;
    private final AuditService auditService;

    public QuestionService(QuestionMapper questionMapper, QuestionOptionMapper optionMapper,
                           QuestionReferenceChecker referenceChecker, CourseMapper courseMapper,
                           AuditService auditService) {
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.referenceChecker = referenceChecker;
        this.courseMapper = courseMapper;
        this.auditService = auditService;
    }

    /** 新建题目。课程必须在当前登录者的可见范围内。 */
    @Transactional
    public QuestionVo create(CurrentUser user, long courseId, QuestionRequest request, String ip) {
        DataScopeGuard.requireAdmin();
        requireVisibleCourse(user, courseId);
        validateAnswers(request);

        Question entity = new Question();
        entity.setCourseId(courseId);
        entity.setType(request.type());
        entity.setStem(request.stem().trim());
        entity.setStatus("ACTIVE");
        entity.setCreatedByAdminId(user.id());
        questionMapper.insert(entity);

        insertOptions(entity.getId(), request);
        auditService.record(user, "QUESTION_CREATED", "question", entity.getId(),
                "courseId=" + courseId + ",type=" + request.type(), ip);
        return detail(entity.getId());
    }

    /**
     * 修改题目。被已发布试卷引用时**同样允许**修改。
     *
     * <p>判分读的是 {@code exam_attempt_question} 里的题目快照（开考时写入），
     * 与当前题库内容无关：已开考/已交卷的场次用旧内容判定，之后的新考试用新内容。
     * 因此这里不再拦截，否则「题目写错了却改不了」会成为死结。
     */
    @Transactional
    public QuestionVo update(CurrentUser user, long questionId, QuestionRequest request, String ip) {
        DataScopeGuard.requireAdmin();
        Question existing = requireQuestion(questionId);
        requireVisibleCourse(user, existing.getCourseId());
        validateAnswers(request);

        questionMapper.update(null, Wrappers.<Question>lambdaUpdate()
                .set(Question::getType, request.type())
                .set(Question::getStem, request.stem().trim())
                .eq(Question::getId, questionId));

        // 选项整体替换：先删后插，保证与请求完全一致（无残留）
        optionMapper.delete(Wrappers.<QuestionOption>lambdaQuery()
                .eq(QuestionOption::getQuestionId, questionId));
        insertOptions(questionId, request);

        auditService.record(user, "QUESTION_UPDATED", "question", questionId, null, ip);
        return detail(questionId);
    }

    /**
     * 删除题目。被已发布试卷引用时拒绝。
     *
     * <p>与「编辑」不同：删除会让试卷少一题，总分与已定分值和历史成绩对不上，
     * 因此仍拦截，并给出可行路径（取消发布后重编，或改为停用）。
     */
    @Transactional
    public void delete(CurrentUser user, long questionId, String ip) {
        DataScopeGuard.requireAdmin();
        Question existing = requireQuestion(questionId);
        requireVisibleCourse(user, existing.getCourseId());
        if (referenceChecker.isReferencedByPublished(questionId)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该题已被已发布试卷引用，不能删除；可先取消发布试卷后重编，或改为停用");
        }
        optionMapper.delete(Wrappers.<QuestionOption>lambdaQuery()
                .eq(QuestionOption::getQuestionId, questionId));
        questionMapper.deleteById(questionId);
        auditService.record(user, "QUESTION_DELETED", "question", questionId, null, ip);
    }

    /**
     * 停用 / 启用。
     *
     * <p>停用只影响「能否被新抽题选中」，不影响已发布试卷与进行中考试的判分
     * （那些用 attempt 快照）。因此被已发布试卷引用时**允许**停用。
     */
    @Transactional
    public QuestionVo changeStatus(CurrentUser user, long questionId, String status, String ip) {
        DataScopeGuard.requireAdmin();
        Question existing = requireQuestion(questionId);
        requireVisibleCourse(user, existing.getCourseId());
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 ACTIVE 或 INACTIVE");
        }
        questionMapper.update(null, Wrappers.<Question>lambdaUpdate()
                .set(Question::getStatus, status)
                .eq(Question::getId, questionId));
        auditService.record(user, "ACTIVE".equals(status) ? "QUESTION_ENABLED" : "QUESTION_DISABLED",
                "question", questionId, null, ip);
        return detail(questionId);
    }

    /** 题目分页。列表带 referencedByPublished，供前端禁用按钮。 */
    public com.insurance.training.common.response.PageResult<QuestionVo> page(
            CurrentUser user, long courseId, String type, String status, Long page, Long pageSize) {
        DataScopeGuard.requireAdmin();
        requireVisibleCourse(user, courseId);
        com.insurance.training.common.web.PageQuery pq =
                com.insurance.training.common.web.PageQuery.of(page, pageSize, 200);

        var wrapper = Wrappers.<Question>lambdaQuery()
                .eq(Question::getCourseId, courseId)
                .orderByDesc(Question::getId);
        if (type != null && !type.isBlank()) {
            wrapper.eq(Question::getType, type);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Question::getStatus, status);
        }
        var result = questionMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pq.page(), pq.pageSize()),
                wrapper);
        if (result.getRecords().isEmpty()) {
            return com.insurance.training.common.response.PageResult.empty(pq.page(), pq.pageSize());
        }

        List<Long> ids = result.getRecords().stream().map(Question::getId).toList();
        Map<Long, List<QuestionOption>> optionsByQuestion = optionMapper.selectList(
                        Wrappers.<QuestionOption>lambdaQuery()
                                .in(QuestionOption::getQuestionId, ids)
                                .orderByAsc(QuestionOption::getSortNo))
                .stream().collect(java.util.stream.Collectors.groupingBy(QuestionOption::getQuestionId));
        java.util.Set<Long> referenced = referenceChecker.referencedByPublished(
                new java.util.HashSet<>(ids));

        List<QuestionVo> items = result.getRecords().stream()
                .map(q -> QuestionVo.of(q, optionsByQuestion.getOrDefault(q.getId(), List.of()),
                        referenced.contains(q.getId())))
                .toList();
        return com.insurance.training.common.response.PageResult.of(items, result.getTotal(),
                pq.page(), pq.pageSize());
    }

    /** 题目详情（含选项与引用标记）。 */
    public QuestionVo detail(long questionId) {
        Question q = requireQuestion(questionId);
        List<QuestionOption> options = optionMapper.selectList(Wrappers.<QuestionOption>lambdaQuery()
                .eq(QuestionOption::getQuestionId, questionId)
                .orderByAsc(QuestionOption::getSortNo));
        return QuestionVo.of(q, options, referenceChecker.isReferencedByPublished(questionId));
    }

    public Question requireQuestion(long questionId) {
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "题目不存在");
        }
        return q;
    }

    /** 课程可见性：复用 CourseService 的数据范围校验。 */
    public Course requireVisibleCourse(CurrentUser user, long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        DataScopeGuard.checkCourseWrite(user, course.getInsuranceCompanyId(), course.getOwnerEnterpriseId());
        return course;
    }

    /**
     * 校验题型与正确答案数量。
     *
     * <p>多选只给 1 个正确答案时**不静默转单选**：组卷时行为会与管理员预期不符，
     * 直接拒绝并提示改类型。
     */
    private void validateAnswers(QuestionRequest request) {
        List<QuestionRequest.OptionRequest> options = request.options();
        if (options.size() < 2 || options.size() > MAX_OPTIONS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "选项数量必须在 2-" + MAX_OPTIONS + " 之间");
        }
        long correctCount = options.stream().filter(QuestionRequest.OptionRequest::correct).count();
        if ("SINGLE".equals(request.type())) {
            if (correctCount != 1) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "单选题必须且只能有 1 个正确答案，当前 " + correctCount + " 个");
            }
        }
        else {
            if (correctCount < 2) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "多选题至少需要 2 个正确答案；若只有 1 个正确项请改用单选");
            }
        }
    }

    private void insertOptions(long questionId, QuestionRequest request) {
        List<QuestionOption> rows = new ArrayList<>();
        for (int i = 0; i < request.options().size(); i++) {
            QuestionRequest.OptionRequest opt = request.options().get(i);
            QuestionOption row = new QuestionOption();
            row.setQuestionId(questionId);
            row.setLabel(LABELS[i]);
            row.setContent(opt.content().trim());
            row.setIsCorrect(opt.correct() ? 1 : 0);
            row.setSortNo(i + 1);
            rows.add(row);
        }
        rows.forEach(optionMapper::insert);
    }
}
