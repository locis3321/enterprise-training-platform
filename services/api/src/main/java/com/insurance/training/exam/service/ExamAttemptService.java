package com.insurance.training.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.exam.dto.SaveAnswerRequest;
import com.insurance.training.exam.entity.ExamAttempt;
import com.insurance.training.exam.entity.ExamAttemptAnswer;
import com.insurance.training.exam.entity.ExamAttemptQuestion;
import com.insurance.training.exam.entity.ExamPaper;
import com.insurance.training.exam.entity.ExamPaperQuestion;
import com.insurance.training.exam.entity.Question;
import com.insurance.training.exam.entity.QuestionOption;
import com.insurance.training.exam.mapper.ExamAttemptAnswerMapper;
import com.insurance.training.exam.mapper.ExamAttemptMapper;
import com.insurance.training.exam.mapper.ExamAttemptQuestionMapper;
import com.insurance.training.exam.mapper.ExamPaperMapper;
import com.insurance.training.exam.mapper.QuestionMapper;
import com.insurance.training.exam.mapper.QuestionOptionMapper;
import com.insurance.training.exam.vo.AttemptVo;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import com.insurance.training.learning.service.BusinessClock;
import com.insurance.training.learning.service.LearningProgressService;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 考试尝试：准入、开考快照、答题、交卷判分。
 *
 * <p>关键不变量：
 * <ol>
 *   <li>所有时间判定用服务端时钟，不接受客户端时间；</li>
 *   <li>开考时把题干/选项/正确答案/分值快照进 attempt —— 管理员改题或停用不影响进行中的考试；</li>
 *   <li>同一学员同一试卷最多一个进行中 attempt（数据库生成列唯一键兜底）；</li>
 *   <li>学员接口绝不下发 {@code correctJson}；</li>
 *   <li>及格判定用整数乘法 {@code score*100 >= total*passScore}，避免浮点误差。</li>
 * </ol>
 */
@Service
public class ExamAttemptService {

    private static final Logger log = LoggerFactory.getLogger(ExamAttemptService.class);

    private final ExamAttemptMapper attemptMapper;
    private final ExamAttemptQuestionMapper attemptQuestionMapper;
    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamPaperMapper paperMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final com.insurance.training.exam.mapper.ExamPaperQuestionMapper paperQuestionMapper;
    private final com.insurance.training.training.mapper.TrainingPackageVersionMapper packageVersionMapper;
    private final TrainingAssignmentMapper assignmentMapper;
    private final TrainingTaskMapper taskMapper;
    private final TrainingPackageMapper packageMapper;
    private final LearnerGuard learnerGuard;
    private final LearningProgressService progressService;
    private final BusinessClock clock;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ExamAttemptService(ExamAttemptMapper attemptMapper,
                              ExamAttemptQuestionMapper attemptQuestionMapper,
                              ExamAttemptAnswerMapper answerMapper, ExamPaperMapper paperMapper,
                              QuestionMapper questionMapper, QuestionOptionMapper optionMapper,
                              com.insurance.training.exam.mapper.ExamPaperQuestionMapper paperQuestionMapper,
                              com.insurance.training.training.mapper.TrainingPackageVersionMapper packageVersionMapper,
                              TrainingAssignmentMapper assignmentMapper, TrainingTaskMapper taskMapper,
                              TrainingPackageMapper packageMapper, LearnerGuard learnerGuard,
                              LearningProgressService progressService, BusinessClock clock,
                              AuditService auditService, ObjectMapper objectMapper) {
        this.attemptMapper = attemptMapper;
        this.attemptQuestionMapper = attemptQuestionMapper;
        this.answerMapper = answerMapper;
        this.paperMapper = paperMapper;
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.packageVersionMapper = packageVersionMapper;
        this.assignmentMapper = assignmentMapper;
        this.taskMapper = taskMapper;
        this.packageMapper = packageMapper;
        this.learnerGuard = learnerGuard;
        this.progressService = progressService;
        this.clock = clock;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 开考（幂等）。
     *
     * <p>已存在未超时的进行中 attempt 时**返回同一份卷子**（{@code resumed=true}），不新建：
     * {@code onHide} 不可靠（杀进程不触发），不恢复会让学员答案全丢。
     * 已超时的进行中 attempt 先自动交卷，再开新的。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AttemptVo start(CurrentUser user, long paperId, long assignmentId, String ip) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        // 同一作业分配上的并发开考必须串行：否则两个事务都查不到「进行中」，
        // 靠唯一键兜底时后到的事务因 REPEATABLE READ 快照看不到对方刚提交的行。
        TrainingAssignment assignment = lockAndRequireOwnAssignment(learner, assignmentId);
        TrainingTask task = requireOpenTask(assignment);
        TrainingPackage pkg = requirePackageOn(assignment.getPackageVersionId());
        ExamPaper paper = requirePublishedPaper(paperId, pkg.getId());
        requireLearningCompleted(learner, assignment, "完成全部课程学习后才能参加考试");

        LocalDateTime now = clock.now();
        ExamAttempt existing = findInProgress(assignmentId, paperId);
        if (existing != null) {
            if (!now.isBefore(existing.getDeadlineAt())) {
                // 已超时：先按超时自动交卷，再按「无进行中」处理
                submitInternal(existing, true, now);
            }
            else {
                return attemptVo(existing, true);
            }
        }

        return attemptVo(createAttempt(user, assignment, paper, now, ip), false);
    }

    /** 保存单题答案。重复保存覆盖；超时或已交卷返回 409。 */
    @Transactional
    public void saveAnswer(CurrentUser user, SaveAnswerRequest request, String ip) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        ExamAttempt attempt = requireOwnAttempt(learner, request.attemptId());
        LocalDateTime now = clock.now();
        if (!"IN_PROGRESS".equals(attempt.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "考试已交卷，不能继续作答");
        }
        if (!now.isBefore(attempt.getDeadlineAt())) {
            throw new BizException(ErrorCode.CONFLICT, "考试已超时，请交卷");
        }
        Long count = attemptQuestionMapper.selectCount(Wrappers.<ExamAttemptQuestion>lambdaQuery()
                .eq(ExamAttemptQuestion::getAttemptId, attempt.getId())
                .eq(ExamAttemptQuestion::getQuestionId, request.questionId()));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "该题不属于本次考试");
        }

        String answerJson = writeJson(request.labels() == null ? List.of() : request.labels());
        ExamAttemptAnswer existing = answerMapper.selectOne(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attempt.getId())
                .eq(ExamAttemptAnswer::getQuestionId, request.questionId()));
        if (existing == null) {
            ExamAttemptAnswer row = new ExamAttemptAnswer();
            row.setAttemptId(attempt.getId());
            row.setQuestionId(request.questionId());
            row.setAnswerJson(answerJson);
            row.setIsCorrect(0);
            row.setScore(0);
            try {
                answerMapper.insert(row);
            }
            catch (DuplicateKeyException ex) {
                // 并发保存同一题：唯一键兜底，转为更新
                updateAnswer(attempt.getId(), request.questionId(), answerJson);
            }
        }
        else {
            updateAnswer(attempt.getId(), request.questionId(), answerJson);
        }
    }

    /**
     * 交卷并判分。已交卷再调返回既有成绩（幂等），不产生第二条记录。
     *
     * @param auto true 表示切页/超时自动交卷（记 auto_submitted）
     */
    @Transactional
    public AttemptVo submit(CurrentUser user, long attemptId, boolean auto, String ip) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        ExamAttempt attempt = requireOwnAttempt(learner, attemptId);
        LocalDateTime now = clock.now();
        if (!"IN_PROGRESS".equals(attempt.getStatus())) {
            return attemptVo(attempt, false);
        }
        if (!auto && !now.isBefore(attempt.getDeadlineAt())) {
            // 超时后主动交卷：仍按已答计分，但标记为自动
            return attemptVo(submitInternal(attempt, true, now), false);
        }
        return attemptVo(submitInternal(attempt, auto, now), false);
    }

    /** 我的考试历史（同一试卷多次 attempt 全部列出）。 */
    public List<AttemptVo> history(CurrentUser user, long assignmentId) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        requireOwnAssignment(learner, assignmentId);
        return attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                        .eq(ExamAttempt::getAssignmentId, assignmentId)
                        .orderByDesc(ExamAttempt::getId))
                .stream().map(a -> attemptVo(a, false)).toList();
    }

    /** 可考的试卷（任务所属包已发布试卷）；包缺失或已下线时返回空，不抛错。 */
    public List<Long> availablePaperIds(TrainingAssignment assignment) {
        TrainingPackageVersion version = packageVersionMapper.selectById(assignment.getPackageVersionId());
        if (version == null) {
            return List.of();
        }
        TrainingPackage pkg = packageMapper.selectById(version.getPackageId());
        if (pkg == null || !"PUBLISHED".equals(pkg.getStatus())) {
            return List.of();
        }
        return paperMapper.selectList(Wrappers.<ExamPaper>lambdaQuery()
                        .eq(ExamPaper::getPackageId, pkg.getId())
                        .eq(ExamPaper::getStatus, "PUBLISHED"))
                .stream().map(ExamPaper::getId).toList();
    }

    /** 已发布试卷 id，不看包状态（用于展示「包已下线」的卡片）。 */
    private List<Long> publishedPaperIds(TrainingAssignment assignment) {
        TrainingPackageVersion version = packageVersionMapper.selectById(assignment.getPackageVersionId());
        if (version == null) {
            return List.of();
        }
        return paperMapper.selectList(Wrappers.<ExamPaper>lambdaQuery()
                        .eq(ExamPaper::getPackageId, version.getPackageId())
                        .eq(ExamPaper::getStatus, "PUBLISHED"))
                .stream().map(ExamPaper::getId).toList();
    }

    /** 任务所属培训包是否仍在线（下线后考试同步关闭）。 */
    public boolean isPackageOpen(TrainingAssignment assignment) {
        TrainingPackageVersion version = packageVersionMapper.selectById(assignment.getPackageVersionId());
        if (version == null) {
            return false;
        }
        TrainingPackage pkg = packageMapper.selectById(version.getPackageId());
        return pkg != null && "PUBLISHED".equals(pkg.getStatus());
    }

    /**
     * 我的可考任务数（首页「待考考试」用）。
     *
     * <p>判定口径与学员端可考状态完全一致：任务开启、学习已完成、包未下线、有已发布试卷；
     * 已在本任务上考过且通过的不再计入，避免「已考完还提示待考」。
     */
    public ExamOverview overview(LearnerContext learner) {
        List<TrainingAssignment> assignments = assignmentMapper.selectList(
                Wrappers.<TrainingAssignment>lambdaQuery()
                        .in(TrainingAssignment::getEmployeeId, learner.employeeIds()));
        int available = 0;
        int passedCount = 0;
        for (TrainingAssignment assignment : assignments) {
            TrainingTask task = taskMapper.selectById(assignment.getTaskId());
            if (task == null || !"PUBLISHED".equals(task.getStatus())) {
                continue;
            }
            if (!isPackageOpen(assignment) || availablePaperIds(assignment).isEmpty()) {
                continue;
            }
            if (!progressService.recalculate(assignment.getId()).completed()) {
                continue;
            }
            boolean passed = attemptMapper.selectCount(Wrappers.<ExamAttempt>lambdaQuery()
                    .eq(ExamAttempt::getAssignmentId, assignment.getId())
                    .eq(ExamAttempt::getPassed, 1)) > 0;
            if (passed) {
                passedCount++;
            }
            else {
                available++;
            }
        }
        return new ExamOverview(available, passedCount);
    }

    /** 考试总览：可考数 / 已通过数。 */
    public record ExamOverview(int pendingExams, int passedExams) {
    }

    /** 单任务的考试准入：canStart 与 reason 都由服务端给出，前端只展示。 */
    public record Availability(boolean canStart, String reason, List<Long> paperIds) {
    }

    /** 最佳已交卷成绩摘要：列表标识与成绩页共用同一口径。 */
    public record BestAttempt(long attemptId, long paperId, Integer scoreSum, Integer totalScore,
                              Integer passScore, boolean passed, LocalDateTime submittedAt) {
    }

    /**
     * 考试卡片：考试页的一块数据。
     *
     * <p>{@code attempted} 表示已交过卷（有成绩），此时前端不再提供「开始考试」；
     * {@code best} 为最高分，未考过为 {@code null}。
     */
    public record ExamCard(long assignmentId, String taskTitle, List<Long> paperIds,
                           boolean canStart, String reason, boolean attempted, BestAttempt best) {
    }

    /**
     * 考试准入判定（与 {@link #start} 的校验顺序保持一致，避免「按钮可点但开考被拒」）。
     *
     * <p>只要该任务有已发布试卷，{@code paperIds} 就照常返回——即使学习未完成，
     * 前端仍需要这张卡片来展示置灰按钮与原因，而不是让任务凭空消失。
     */
    public Availability availability(TrainingAssignment assignment) {
        if (!isPackageOpen(assignment)) {
            // 包下线后考试同步关闭；此时试卷不可考，但仍返回已发布试卷便于展示原因
            return new Availability(false, "培训包已下线，考试同步关闭",
                    publishedPaperIds(assignment));
        }
        List<Long> paperIds = availablePaperIds(assignment);
        if (paperIds.isEmpty()) {
            return new Availability(false, "该培训包还没有发布试卷", List.of());
        }
        if (!progressService.recalculate(assignment.getId()).completed()) {
            return new Availability(false, "完成全部课程学习后才能参加考试", paperIds);
        }
        TrainingTask task = taskMapper.selectById(assignment.getTaskId());
        if (task == null || !"PUBLISHED".equals(task.getStatus())) {
            return new Availability(false, "任务已关闭", paperIds);
        }
        LocalDateTime now = clock.now();
        if (now.isBefore(task.getStartAt())) {
            return new Availability(false, "任务尚未开始", paperIds);
        }
        if (!now.isBefore(task.getLearnDeadlineAt())) {
            return new Availability(false, "任务已过学习截止时间", paperIds);
        }
        return new Availability(true, null, paperIds);
    }

    /**
     * 考试页卡片：只返回有已发布试卷的任务，未配卷的任务不出现。
     */
    public List<ExamCard> examCards(LearnerContext learner) {
        List<TrainingAssignment> assignments = assignmentMapper.selectList(
                Wrappers.<TrainingAssignment>lambdaQuery()
                        .in(TrainingAssignment::getEmployeeId, learner.employeeIds()));
        List<ExamCard> cards = new ArrayList<>();
        for (TrainingAssignment assignment : assignments) {
            TrainingTask task = taskMapper.selectById(assignment.getTaskId());
            if (task == null || !"PUBLISHED".equals(task.getStatus())) {
                continue;
            }
            Availability availability = availability(assignment);
            if (availability.paperIds().isEmpty()) {
                // 包下线、未配卷、任务未开始：这些任务在考试页没有可执行动作
                continue;
            }
            BestAttempt best = bestOf(assignment.getId());
            cards.add(new ExamCard(assignment.getId(), task.getTitle(), availability.paperIds(),
                    availability.canStart(), availability.reason(), best != null, best));
        }
        return cards;
    }

    /**
     * 某任务的最佳已交卷成绩。
     *
     * <p>排序口径：百分比高 → 得分高 → 交卷时间新。只在 SUBMITTED 中取，
     * 进行中的考试不参与，也不改写历史记录。
     */
    public BestAttempt bestOf(long assignmentId) {
        List<ExamAttempt> submitted = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getAssignmentId, assignmentId)
                .eq(ExamAttempt::getStatus, "SUBMITTED"));
        return pickBest(submitted);
    }

    /**
     * 从已交卷记录中选最高分。
     *
     * <p>口径：百分比高 → 得分高 → 交卷时间新。百分比相同但总分不同时，
     * 取绝对得分高的一场（同百分比的 3/3 与 2/2 视为等价，取先比较到的更高得分）。
     */
    public static BestAttempt pickBest(List<ExamAttempt> submitted) {
        return submitted.stream()
                .filter(a -> a.getTotalScore() != null && a.getTotalScore() > 0)
                .max(java.util.Comparator
                        .comparingLong(ExamAttemptService::percentOf)
                        .thenComparingInt(a -> a.getScoreSum() == null ? 0 : a.getScoreSum())
                        .thenComparing(a -> a.getSubmittedAt() == null
                                ? LocalDateTime.MIN : a.getSubmittedAt()))
                .map(a -> new BestAttempt(a.getId(), a.getPaperId(), a.getScoreSum(), a.getTotalScore(),
                        a.getPassScore(), Integer.valueOf(1).equals(a.getPassed()), a.getSubmittedAt()))
                .orElse(null);
    }

    /** 得分百分比（整数，向零截断），用于比较与展示。 */
    private static long percentOf(ExamAttempt attempt) {
        int total = attempt.getTotalScore() == null ? 0 : attempt.getTotalScore();
        if (total <= 0) {
            return -1;
        }
        int sum = attempt.getScoreSum() == null ? 0 : attempt.getScoreSum();
        return (long) sum * 100 / total;
    }

    // ---------- 内部 ----------

    private ExamAttempt createAttempt(CurrentUser user, TrainingAssignment assignment,
                                      ExamPaper paper, LocalDateTime now, String ip) {
        List<ExamPaperQuestion> links = paperQuestionLinks(paper.getId());
        if (links.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "试卷没有题目，无法开考");
        }
        int total = links.stream().mapToInt(ExamPaperQuestion::getScore).sum();

        ExamAttempt attempt = new ExamAttempt();
        attempt.setAssignmentId(assignment.getId());
        attempt.setPaperId(paper.getId());
        attempt.setStatus("IN_PROGRESS");
        attempt.setStartedAt(now);
        attempt.setDeadlineAt(now.plus(Duration.ofMinutes(paper.getDurationMinutes())));
        attempt.setScoreSum(0);
        attempt.setTotalScore(total);
        attempt.setPassScore(paper.getPassScore());
        attempt.setPassed(0);
        attempt.setAutoSubmitted(0);
        try {
            attemptMapper.insert(attempt);
        }
        catch (DuplicateKeyException ex) {
            // 并发开考：另一请求已建，返回既有的（幂等）
            ExamAttempt concurrent = findInProgress(assignment.getId(), paper.getId());
            if (concurrent == null) {
                throw new BizException(ErrorCode.CONFLICT, "开考冲突，请重试");
            }
            return concurrent;
        }

        // 题目快照：题干、选项、正确答案、分值全部复制
        for (ExamPaperQuestion link : links) {
            ExamAttemptQuestion snapshot = buildSnapshot(attempt.getId(), link);
            attemptQuestionMapper.insert(snapshot);
        }
        auditService.record(user, "EXAM_ATTEMPT_STARTED", "exam_attempt", attempt.getId(),
                "paperId=" + paper.getId() + ",assignmentId=" + assignment.getId(), ip);
        return attempt;
    }

    private ExamAttemptQuestion buildSnapshot(long attemptId, ExamPaperQuestion link) {
        Question q = questionMapper.selectById(link.getQuestionId());
        if (q == null) {
            throw new BizException(ErrorCode.CONFLICT,
                    "试卷中的题目已被删除（questionId=" + link.getQuestionId() + "），请联系管理员");
        }
        List<QuestionOption> options = optionMapper.selectList(Wrappers.<QuestionOption>lambdaQuery()
                .eq(QuestionOption::getQuestionId, q.getId())
                .orderByAsc(QuestionOption::getSortNo));
        List<Map<String, String>> optionViews = new ArrayList<>();
        Set<String> correct = new LinkedHashSet<>();
        for (QuestionOption o : options) {
            optionViews.add(Map.of("label", o.getLabel(), "content", o.getContent()));
            if (Integer.valueOf(1).equals(o.getIsCorrect())) {
                correct.add(o.getLabel());
            }
        }

        ExamAttemptQuestion snapshot = new ExamAttemptQuestion();
        snapshot.setAttemptId(attemptId);
        snapshot.setQuestionId(q.getId());
        snapshot.setStem(q.getStem());
        snapshot.setType(q.getType());
        snapshot.setOptionsJson(writeJson(optionViews));
        snapshot.setCorrectJson(writeJson(correct.stream().sorted().toList()));
        snapshot.setScore(link.getScore());
        snapshot.setSortNo(link.getSortNo());
        return snapshot;
    }

    /** 判分：单选完全一致；多选**完全一致**（多选、少选、错选均不得分）。 */
    private ExamAttempt submitInternal(ExamAttempt attempt, boolean auto, LocalDateTime now) {
        List<ExamAttemptQuestion> questions = attemptQuestionMapper.selectList(
                Wrappers.<ExamAttemptQuestion>lambdaQuery()
                        .eq(ExamAttemptQuestion::getAttemptId, attempt.getId()));
        Map<Long, ExamAttemptAnswer> answerByQuestion = answerMapper.selectList(
                        Wrappers.<ExamAttemptAnswer>lambdaQuery()
                                .eq(ExamAttemptAnswer::getAttemptId, attempt.getId()))
                .stream().collect(Collectors.toMap(ExamAttemptAnswer::getQuestionId, a -> a));

        int scoreSum = 0;
        for (ExamAttemptQuestion q : questions) {
            ExamAttemptAnswer answer = answerByQuestion.get(q.getQuestionId());
            Set<String> chosen = answer == null ? Set.of()
                    : new LinkedHashSet<>(readJsonList(answer.getAnswerJson()));
            Set<String> correct = new LinkedHashSet<>(readJsonList(q.getCorrectJson()));
            boolean isCorrect = !chosen.isEmpty() && chosen.equals(correct);
            int score = isCorrect ? q.getScore() : 0;
            scoreSum += score;

            if (answer != null) {
                answerMapper.update(null, Wrappers.<ExamAttemptAnswer>lambdaUpdate()
                        .set(ExamAttemptAnswer::getIsCorrect, isCorrect ? 1 : 0)
                        .set(ExamAttemptAnswer::getScore, score)
                        .eq(ExamAttemptAnswer::getId, answer.getId()));
            }
        }

        int total = attempt.getTotalScore() == null ? 0 : attempt.getTotalScore();
        int passScore = attempt.getPassScore() == null ? 90 : attempt.getPassScore();
        // 整数乘法避免浮点误差
        boolean passed = total > 0 && (long) scoreSum * 100 >= (long) total * passScore;

        int updated = attemptMapper.update(null, Wrappers.<ExamAttempt>lambdaUpdate()
                .set(ExamAttempt::getStatus, "SUBMITTED")
                .set(ExamAttempt::getSubmittedAt, now)
                .set(ExamAttempt::getScoreSum, scoreSum)
                .set(ExamAttempt::getPassed, passed ? 1 : 0)
                .set(ExamAttempt::getAutoSubmitted, auto ? 1 : 0)
                .eq(ExamAttempt::getId, attempt.getId())
                .eq(ExamAttempt::getStatus, "IN_PROGRESS"));
        if (updated == 0) {
            // 并发交卷：另一请求已完成，返回最新状态
            return attemptMapper.selectById(attempt.getId());
        }
        attempt.setStatus("SUBMITTED");
        attempt.setSubmittedAt(now);
        attempt.setScoreSum(scoreSum);
        attempt.setPassed(passed ? 1 : 0);
        attempt.setAutoSubmitted(auto ? 1 : 0);
        auditService.record(null, "EXAM_ATTEMPT_SUBMITTED", "exam_attempt", attempt.getId(),
                "score=" + scoreSum + "/" + total + ",passed=" + passed + ",auto=" + auto, null);
        return attempt;
    }

    private ExamAttempt findInProgress(long assignmentId, long paperId) {
        return attemptMapper.selectOne(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getAssignmentId, assignmentId)
                .eq(ExamAttempt::getPaperId, paperId)
                .eq(ExamAttempt::getStatus, "IN_PROGRESS"));
    }

    private void updateAnswer(long attemptId, long questionId, String answerJson) {
        answerMapper.update(null, Wrappers.<ExamAttemptAnswer>lambdaUpdate()
                .set(ExamAttemptAnswer::getAnswerJson, answerJson)
                .eq(ExamAttemptAnswer::getAttemptId, attemptId)
                .eq(ExamAttemptAnswer::getQuestionId, questionId));
    }

    private TrainingAssignment requireOwnAssignment(LearnerContext learner, long assignmentId) {
        TrainingAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务分配不存在");
        }        if (!learner.owns(assignment.getEmployeeId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问他人的培训任务");
        }
        return assignment;
    }

    /** 开考专用：在事务内对分配行加锁，使同一分配的开考请求串行执行。 */
    private TrainingAssignment lockAndRequireOwnAssignment(LearnerContext learner, long assignmentId) {
        TrainingAssignment locked = assignmentMapper.selectOne(Wrappers.<TrainingAssignment>lambdaQuery()
                .eq(TrainingAssignment::getId, assignmentId)
                .last("FOR UPDATE"));
        if (locked == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务分配不存在");
        }
        if (!learner.owns(locked.getEmployeeId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问他人的培训任务");
        }
        return locked;
    }

    private TrainingTask requireOpenTask(TrainingAssignment assignment) {
        TrainingTask task = taskMapper.selectById(assignment.getTaskId());
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训任务不存在");
        }
        if (!"PUBLISHED".equals(task.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "任务已关闭，不能参加考试");
        }
        LocalDateTime now = clock.now();
        if (now.isBefore(task.getStartAt())) {
            throw new BizException(ErrorCode.CONFLICT, "任务尚未开始");
        }
        if (!now.isBefore(task.getLearnDeadlineAt())) {
            throw new BizException(ErrorCode.CONFLICT, "任务已过学习截止时间");
        }
        return task;
    }

    /** 由包版本反查包，并校验包未下线（下线后考试同步关闭）。 */
    private TrainingPackage requirePackageOn(long packageVersionId) {
        TrainingPackageVersion version = packageVersionMapper.selectById(packageVersionId);
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训包版本不存在");
        }
        TrainingPackage pkg = packageMapper.selectById(version.getPackageId());
        if (pkg == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训包不存在");
        }
        if (!"PUBLISHED".equals(pkg.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "培训包已下线，考试同步关闭");
        }
        return pkg;
    }

    private long pkgIdOf(TrainingAssignment assignment) {
        TrainingPackage pkg = requirePackageOn(assignment.getPackageVersionId());
        return pkg.getId();
    }

    private ExamPaper requirePublishedPaper(long paperId, long packageId) {
        ExamPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "试卷不存在");
        }
        if (!paper.getPackageId().equals(packageId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "该试卷不属于当前培训包");
        }
        if (!"PUBLISHED".equals(paper.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "试卷尚未发布");
        }
        return paper;
    }

    /** 学习未达 100% 时拒绝开考。 */
    private void requireLearningCompleted(LearnerContext learner, TrainingAssignment assignment,
                                          String message) {
        LearningProgressService.Progress progress = progressService.recalculate(assignment.getId());
        if (!progress.completed()) {
            throw new BizException(ErrorCode.CONFLICT, message);
        }
    }

    private ExamAttempt requireOwnAttempt(LearnerContext learner, long attemptId) {
        ExamAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "考试记录不存在");
        }
        TrainingAssignment assignment = assignmentMapper.selectById(attempt.getAssignmentId());
        if (assignment == null || !learner.owns(assignment.getEmployeeId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问他人的考试记录");
        }
        return attempt;
    }

    /** 学员视图：不下发 correctJson。 */
    private AttemptVo attemptVo(ExamAttempt attempt, boolean resumed) {
        List<AttemptVo.QuestionItem> items = attemptQuestionMapper.selectList(
                        Wrappers.<ExamAttemptQuestion>lambdaQuery()
                                .eq(ExamAttemptQuestion::getAttemptId, attempt.getId())
                                .orderByAsc(ExamAttemptQuestion::getSortNo))
                .stream()
                .map(q -> new AttemptVo.QuestionItem(q.getQuestionId(), q.getStem(), q.getType(),
                        readOptions(q.getOptionsJson()), q.getScore()))
                .toList();
        List<AttemptVo.AnswerItem> answers = answerMapper.selectList(
                        Wrappers.<ExamAttemptAnswer>lambdaQuery()
                                .eq(ExamAttemptAnswer::getAttemptId, attempt.getId()))
                .stream()
                .map(a -> new AttemptVo.AnswerItem(a.getQuestionId(), readJsonList(a.getAnswerJson())))
                .toList();
        return new AttemptVo(attempt.getId(), attempt.getPaperId(), attempt.getStatus(),
                attempt.getStartedAt(), attempt.getDeadlineAt(), attempt.getSubmittedAt(),
                attempt.getScoreSum(), attempt.getTotalScore(), attempt.getPassScore(),
                Integer.valueOf(1).equals(attempt.getPassed()),
                Integer.valueOf(1).equals(attempt.getAutoSubmitted()),
                resumed, items, answers);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "数据序列化失败");
        }
    }

    /** 解析选项快照 [{"label":"A","content":"..."}]。 */
    private List<AttemptVo.OptionView> readOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> raw = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, String>>>() {
                    });
            return raw.stream()
                    .map(m -> new AttemptVo.OptionView(m.get("label"), m.get("content")))
                    .toList();
        }
        catch (Exception ex) {
            log.warn("选项 JSON 解析失败: {}", json, ex);
            return List.of();
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        }
        catch (Exception ex) {
            log.warn("JSON 解析失败: {}", json, ex);
            return List.of();
        }
    }

    private List<ExamPaperQuestion> paperQuestionLinks(long paperId) {
        return paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .orderByAsc(ExamPaperQuestion::getSortNo));
    }
}
