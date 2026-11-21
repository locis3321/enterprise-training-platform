package com.insurance.training.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.exam.dto.PaperCreateRequest;
import com.insurance.training.exam.entity.ExamPaper;
import com.insurance.training.exam.entity.ExamPaperQuestion;
import com.insurance.training.exam.entity.Question;
import com.insurance.training.exam.entity.QuestionOption;
import com.insurance.training.exam.mapper.ExamPaperMapper;
import com.insurance.training.exam.mapper.ExamPaperQuestionMapper;
import com.insurance.training.exam.mapper.QuestionMapper;
import com.insurance.training.exam.mapper.QuestionOptionMapper;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.service.PackageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 组卷。
 *
 * <p>核心规则：
 * <ul>
 *   <li>试卷挂在**培训包**上（不挂包版本）——题目与版本无关，包发新版本不需要重新组卷；</li>
 *   <li>抽题只取 {@code status=ACTIVE} 的题目；数量不足时**明确拒绝并报实际可用数**，不静默少抽；</li>
 *   <li>总分 = 各题分值之和，**不强制 100**；及格分存百分比；</li>
 *   <li>发布后锁定：题目集合、分值、时长、及格分均不可改（改题会让进行中的考试突变）。</li>
 * </ul>
 */
@Service
public class ExamPaperService {

    private final ExamPaperMapper paperMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final CourseVersionMapper courseVersionMapper;
    private final TrainingPackageMapper packageMapper;
    private final com.insurance.training.training.mapper.TrainingPackageVersionMapper packageVersionMapper;
    private final PackageService packageService;
    private final AuditService auditService;

    public ExamPaperService(ExamPaperMapper paperMapper, ExamPaperQuestionMapper paperQuestionMapper,
                            QuestionMapper questionMapper, QuestionOptionMapper optionMapper,
                            PackageCourseMapper packageCourseMapper,
                            CourseVersionMapper courseVersionMapper, TrainingPackageMapper packageMapper,
                            com.insurance.training.training.mapper.TrainingPackageVersionMapper packageVersionMapper,
                            PackageService packageService, AuditService auditService) {
        this.paperMapper = paperMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.courseVersionMapper = courseVersionMapper;
        this.packageMapper = packageMapper;
        this.packageVersionMapper = packageVersionMapper;
        this.packageService = packageService;
        this.auditService = auditService;
    }

    /**
     * 组卷并保存为草稿。
     *
     * <p>抽题在服务端完成（前端只给「课程 + 数量」），避免客户端指定具体题目造成可预测性。
     */
    @Transactional
    public ExamPaper create(CurrentUser user, PaperCreateRequest request, String ip) {
        DataScopeGuard.requireAdmin();
        TrainingPackage pkg = packageService.getWritable(user, request.packageId());

        // 一个包一套试卷：已存在则拒绝，提示去编辑或复制后重编
        ExamPaper existing = paperMapper.selectOne(Wrappers.<ExamPaper>lambdaQuery()
                .eq(ExamPaper::getPackageId, pkg.getId()));
        if (existing != null) {
            throw new BizException(ErrorCode.CONFLICT, "该培训包已有试卷，请直接编辑，或复制后重编");
        }

        List<Long> packageCourseIds = coursesOfPackage(pkg);
        List<ExamPaperQuestion> links = new ArrayList<>();
        int sortNo = 1;
        for (PaperCreateRequest.DrawRule rule : request.draws()) {
            if (!packageCourseIds.contains(rule.courseId())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "课程 " + rule.courseId() + " 不属于该培训包");
            }
            int available = countActiveQuestions(rule.courseId());
            if (available < rule.count()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "课程 " + rule.courseId() + " 题库仅有 " + available + " 道可用题目，"
                                + "无法抽取 " + rule.count() + " 道");
            }
            List<Long> picked = pickRandomQuestionIds(rule.courseId(), rule.count());
            for (Long questionId : picked) {
                ExamPaperQuestion link = new ExamPaperQuestion();
                link.setQuestionId(questionId);
                link.setScore(1);   // 默认每题 1 分，可后续改
                link.setSortNo(sortNo++);
                links.add(link);
            }
        }
        if (links.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "抽题结果为空，请检查抽题数量");
        }

        ExamPaper paper = new ExamPaper();
        paper.setInsuranceCompanyId(pkg.getInsuranceCompanyId());
        paper.setPackageId(pkg.getId());
        paper.setTitle(request.title().trim());
        paper.setDurationMinutes(request.durationMinutes() == null ? 60 : request.durationMinutes());
        paper.setPassScore(request.passScore() == null ? 90 : request.passScore());
        paper.setStatus("DRAFT");
        paper.setCreatedByAdminId(user.id());
        paperMapper.insert(paper);

        for (ExamPaperQuestion link : links) {
            link.setPaperId(paper.getId());
            paperQuestionMapper.insert(link);
        }
        auditService.record(user, "EXAM_PAPER_CREATED", "exam_paper", paper.getId(),
                "packageId=" + pkg.getId() + ",questions=" + links.size(), ip);
        return paper;
    }

    /**
     * 逐题设置分值（草稿阶段）。
     *
     * <p>发布后拒绝：分值改动会让同一次考试的总分口径变化，历史成绩无法解释。
     */
    @Transactional
    public void updateScores(CurrentUser user, long paperId, List<ScoreItem> items, String ip) {
        DataScopeGuard.requireAdmin();
        ExamPaper paper = requireWritableDraft(user, paperId);
        for (ScoreItem item : items) {
            if (item.score() == null || item.score() <= 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分值必须是正整数");
            }
            int updated = paperQuestionMapper.update(null,
                    Wrappers.<ExamPaperQuestion>lambdaUpdate()
                            .set(ExamPaperQuestion::getScore, item.score())
                            .eq(ExamPaperQuestion::getPaperId, paper.getId())
                            .eq(ExamPaperQuestion::getQuestionId, item.questionId()));
            if (updated == 0) {
                throw new BizException(ErrorCode.NOT_FOUND,
                        "题目 " + item.questionId() + " 不在该试卷中");
            }
        }
        auditService.record(user, "EXAM_PAPER_SCORES_UPDATED", "exam_paper", paperId,
                "count=" + items.size(), ip);
    }

    /** 修改时长与及格分（草稿阶段）。 */
    @Transactional
    public ExamPaper updateSettings(CurrentUser user, long paperId, Integer durationMinutes,
                                    Integer passScore, String ip) {
        DataScopeGuard.requireAdmin();
        ExamPaper paper = requireWritableDraft(user, paperId);
        if (durationMinutes != null && (durationMinutes < 1 || durationMinutes > 600)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "考试时长必须在 1-600 分钟之间");
        }
        if (passScore != null && (passScore < 1 || passScore > 100)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "及格百分比必须在 1-100 之间");
        }
        paperMapper.update(null, Wrappers.<ExamPaper>lambdaUpdate()
                .set(durationMinutes != null, ExamPaper::getDurationMinutes, durationMinutes)
                .set(passScore != null, ExamPaper::getPassScore, passScore)
                .eq(ExamPaper::getId, paperId));
        auditService.record(user, "EXAM_PAPER_SETTINGS_UPDATED", "exam_paper", paperId,
                "duration=" + durationMinutes + ",pass=" + passScore, ip);
        return paperMapper.selectById(paperId);
    }

    /** 发布：状态 CAS，0 行说明已被其他操作改变。 */
    @Transactional
    public ExamPaper publish(CurrentUser user, long paperId, String ip) {
        DataScopeGuard.requireAdmin();
        ExamPaper paper = requireWritableDraft(user, paperId);
        if (paperQuestionMapper.selectCount(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "试卷没有题目，不能发布");
        }
        int updated = paperMapper.update(null, Wrappers.<ExamPaper>lambdaUpdate()
                .set(ExamPaper::getStatus, "PUBLISHED")
                .eq(ExamPaper::getId, paperId)
                .eq(ExamPaper::getStatus, "DRAFT"));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "试卷状态已被其他操作改变，请刷新后重试");
        }
        auditService.record(user, "EXAM_PAPER_PUBLISHED", "exam_paper", paperId, null, ip);
        return paperMapper.selectById(paperId);
    }

    /**
     * 取消发布：{@code PUBLISHED → DRAFT}，恢复可编辑。
     *
     * <p>只影响「能否新开考」。已在进行中的考试不受影响，可继续作答直到交卷或超时——
     * 它们用的是 attempt 内快照，与试卷当前状态无关；已产生的历史成绩同样保留。
     */
    @Transactional
    public ExamPaper unpublish(CurrentUser user, long paperId, String ip) {
        DataScopeGuard.requireAdmin();
        ExamPaper paper = requireVisible(user, paperId);
        if (!"PUBLISHED".equals(paper.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "试卷当前不是已发布状态，无需取消发布");
        }
        int updated = paperMapper.update(null, Wrappers.<ExamPaper>lambdaUpdate()
                .set(ExamPaper::getStatus, "DRAFT")
                .eq(ExamPaper::getId, paperId)
                .eq(ExamPaper::getStatus, "PUBLISHED"));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "试卷状态已被其他操作改变，请刷新后重试");
        }
        auditService.record(user, "EXAM_PAPER_UNPUBLISHED", "exam_paper", paperId, null, ip);
        return paperMapper.selectById(paperId);
    }

    /**
     * 复制试卷：题目集合与分值照抄，生成为目标培训包的草稿，与源试卷完全独立。
     *
     * <p>因为一个包只能有一套试卷，复制必须指定另一个包。典型用途是跨集团复用同一套题，
     * 各自维护互不影响。
     */
    @Transactional
    public ExamPaper copy(CurrentUser user, long paperId, long targetPackageId, String title, String ip) {
        DataScopeGuard.requireAdmin();
        ExamPaper source = requireVisible(user, paperId);
        TrainingPackage target = packageService.getWritable(user, targetPackageId);
        if (target.getId().equals(source.getPackageId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "复制目标不能是源试卷所属的培训包，请选择其他培训包");
        }
        if (paperMapper.selectCount(Wrappers.<ExamPaper>lambdaQuery()
                .eq(ExamPaper::getPackageId, target.getId())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "目标培训包已有试卷，请选择其他培训包");
        }

        List<ExamPaperQuestion> links = questionsOf(paperId);
        if (links.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "源试卷没有题目，无法复制");
        }

        ExamPaper copy = new ExamPaper();
        copy.setInsuranceCompanyId(target.getInsuranceCompanyId());
        copy.setPackageId(target.getId());
        copy.setTitle(title == null || title.isBlank() ? source.getTitle() + "（副本）" : title.trim());
        copy.setDurationMinutes(source.getDurationMinutes());
        copy.setPassScore(source.getPassScore());
        copy.setStatus("DRAFT");
        copy.setCreatedByAdminId(user.id());
        paperMapper.insert(copy);

        for (ExamPaperQuestion link : links) {
            ExamPaperQuestion cloned = new ExamPaperQuestion();
            cloned.setPaperId(copy.getId());
            cloned.setQuestionId(link.getQuestionId());
            cloned.setScore(link.getScore());
            cloned.setSortNo(link.getSortNo());
            paperQuestionMapper.insert(cloned);
        }
        auditService.record(user, "EXAM_PAPER_COPIED", "exam_paper", copy.getId(),
                "sourceId=" + paperId + ",targetPackageId=" + target.getId(), ip);
        return copy;
    }

    /** 试卷题目与分值（管理端查看与判分使用）。 */
    public List<ExamPaperQuestion> questionsOf(long paperId) {
        return paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .orderByAsc(ExamPaperQuestion::getSortNo));
    }

    /**
     * 试卷题目分页（管理端展示用）。
     *
     * <p>题目可能很多，整包下发既慢又难浏览；每行补上题干、题型与选项数，
     * 让管理员不必再拿题目 id 去题库里反查。
     */
    public com.insurance.training.common.response.PageResult<PaperQuestionItem> questionPage(
            CurrentUser user, long paperId, Long page, Long pageSize) {
        DataScopeGuard.requireAdmin();
        requireVisible(user, paperId);
        com.insurance.training.common.web.PageQuery pq =
                com.insurance.training.common.web.PageQuery.of(page, pageSize, 200);

        var result = paperQuestionMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        pq.page(), pq.pageSize()),
                Wrappers.<ExamPaperQuestion>lambdaQuery()
                        .eq(ExamPaperQuestion::getPaperId, paperId)
                        .orderByAsc(ExamPaperQuestion::getSortNo));
        if (result.getRecords().isEmpty()) {
            return com.insurance.training.common.response.PageResult.empty(pq.page(), pq.pageSize());
        }

        List<Long> questionIds = result.getRecords().stream()
                .map(ExamPaperQuestion::getQuestionId).toList();
        Map<Long, Question> questionById = questionMapper.selectList(
                        Wrappers.<Question>lambdaQuery().in(Question::getId, questionIds))
                .stream().collect(Collectors.toMap(Question::getId, q -> q));
        Map<Long, Long> optionCount = optionMapper.selectList(
                        Wrappers.<QuestionOption>lambdaQuery().in(QuestionOption::getQuestionId, questionIds))
                .stream().collect(Collectors.groupingBy(QuestionOption::getQuestionId, Collectors.counting()));

        List<PaperQuestionItem> items = result.getRecords().stream()
                .map(link -> {
                    Question q = questionById.get(link.getQuestionId());
                    return new PaperQuestionItem(link.getId(), link.getQuestionId(), link.getSortNo(),
                            link.getScore(),
                            q == null ? "（题目已删除）" : q.getStem(),
                            q == null ? null : q.getType(),
                            q == null ? 0 : optionCount.getOrDefault(q.getId(), 0L).intValue(),
                            q == null ? null : q.getStatus());
                })
                .toList();
        return com.insurance.training.common.response.PageResult.of(items, result.getTotal(),
                pq.page(), pq.pageSize());
    }

    /**
     * 试卷内的一道题（管理端展示）。
     *
     * @param stem         题干；题目被删除时为占位文案
     * @param type         SINGLE / MULTIPLE
     * @param optionsCount 选项数量
     * @param status       题目状态 ACTIVE / INACTIVE
     */
    public record PaperQuestionItem(long id, long questionId, Integer sortNo, Integer score,
                                    String stem, String type, int optionsCount, String status) {
    }

    /** 试卷总分 = 各题分值之和。 */
    public int totalScore(long paperId) {
        return questionsOf(paperId).stream().mapToInt(ExamPaperQuestion::getScore).sum();
    }

    /** 按培训包取试卷（一个包最多一套），没有则返回 null。 */
    public ExamPaper findByPackage(CurrentUser user, long packageId) {
        DataScopeGuard.requireAdmin();
        packageService.getVisible(user, packageId);
        return paperMapper.selectOne(Wrappers.<ExamPaper>lambdaQuery()
                .eq(ExamPaper::getPackageId, packageId));
    }

    public ExamPaper requireVisible(CurrentUser user, long paperId) {        ExamPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "试卷不存在");
        }
        if (!user.isSuperAdmin()
                && (user.companyId() == null
                || !user.companyId().equals(paper.getInsuranceCompanyId()))) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他集团的试卷");
        }
        return paper;
    }

    private ExamPaper requireWritableDraft(CurrentUser user, long paperId) {
        ExamPaper paper = requireVisible(user, paperId);
        if (!"DRAFT".equals(paper.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "试卷已发布，不能再修改；如需调整请复制后重编");
        }
        return paper;
    }

    /**
     * 包内课程 id 列表（取当前生效版本 {@code publishedVersionNo} 对应的包版本）。
     *
     * <p>抽题必须基于学员实际会学到的版本：若取草稿版本，抽到的课程可能还没发布。
     */
    private List<Long> coursesOfPackage(TrainingPackage pkg) {
        TrainingPackageVersion version = packageVersionMapper.selectOne(
                Wrappers.<TrainingPackageVersion>lambdaQuery()
                        .eq(TrainingPackageVersion::getPackageId, pkg.getId())
                        .eq(TrainingPackageVersion::getVersionNo, pkg.getPublishedVersionNo()));
        if (version == null) {
            throw new BizException(ErrorCode.CONFLICT, "培训包尚未发布版本，无法组卷");
        }
        List<Long> versionIds = packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                        .eq(PackageCourse::getPackageVersionId, version.getId()))
                .stream().map(PackageCourse::getCourseVersionId).distinct().toList();
        if (versionIds.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "培训包内没有课程，无法组卷");
        }
        return courseVersionMapper.selectList(Wrappers.<CourseVersion>lambdaQuery()
                        .in(CourseVersion::getId, versionIds))
                .stream().map(CourseVersion::getCourseId).distinct().toList();
    }

    private int countActiveQuestions(long courseId) {
        Long count = questionMapper.selectCount(Wrappers.<Question>lambdaQuery()
                .eq(Question::getCourseId, courseId)
                .eq(Question::getStatus, "ACTIVE"));
        return count == null ? 0 : count.intValue();
    }

    /** 随机抽题：ORDER BY RAND() 在小题库可接受；大题库应改为按 id 采样。 */
    private List<Long> pickRandomQuestionIds(long courseId, int count) {
        List<Question> all = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                .eq(Question::getCourseId, courseId)
                .eq(Question::getStatus, "ACTIVE"));
        List<Question> shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled);
        return shuffled.stream().limit(count).map(Question::getId).toList();
    }

    /** 单题分值设置项。 */
    public record ScoreItem(Long questionId, Integer score) {
    }
}
