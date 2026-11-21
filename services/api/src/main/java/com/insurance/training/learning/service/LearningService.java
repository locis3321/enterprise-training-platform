package com.insurance.training.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.entity.CourseResource;
import com.insurance.training.content.entity.CourseSection;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.mapper.CourseSectionMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import com.insurance.training.learning.dto.HeartbeatRequest;
import com.insurance.training.learning.dto.HeartbeatResponse;
import com.insurance.training.learning.dto.OpenResourceRequest;
import com.insurance.training.learning.dto.OpenResourceResponse;
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.learning.entity.ResourceOpenLog;
import com.insurance.training.learning.mapper.ResourceOpenLogMapper;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.mapper.TrainingTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 学习心跳与资源打开。
 *
 * <p>计时唯一来源是服务端时钟与 {@code training_assignment.last_heartbeat_at}；
 * 客户端上报的 position/间隔一律不参与计算。客户端字段只落诊断日志。
 */
@Service
public class LearningService {

    private static final Logger log = LoggerFactory.getLogger(LearningService.class);

    /** 单次心跳最多计入的秒数：正常 30 秒间隔，超出部分不计，防「睡一晚再报」。 */
    static final int HEARTBEAT_MAX_CREDIT_SECONDS = 70;
    /** 小于该间隔视为防抖，计 0 秒但仍返回 200。 */
    static final int HEARTBEAT_MIN_CREDIT_SECONDS = 5;

    private final TrainingAssignmentMapper assignmentMapper;
    private final TrainingTaskMapper taskMapper;
    private final CourseVersionMapper courseVersionMapper;
    private final CourseSectionMapper sectionMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final CourseMapper courseMapper;
    private final CourseResourceMapper resourceMapper;
    private final LearningRecordMapper recordMapper;
    private final ResourceOpenLogMapper openLogMapper;
    private final LearnerGuard learnerGuard;
    private final LearningProgressService progressService;
    private final LearningRateLimiter rateLimiter;
    private final BusinessClock clock;

    public LearningService(TrainingAssignmentMapper assignmentMapper, TrainingTaskMapper taskMapper,
                           CourseVersionMapper courseVersionMapper, CourseSectionMapper sectionMapper,
                           CourseVersionResourceMapper bindingMapper, CourseMapper courseMapper,
                           CourseResourceMapper resourceMapper, LearningRecordMapper recordMapper,
                           ResourceOpenLogMapper openLogMapper, LearnerGuard learnerGuard,
                           LearningProgressService progressService, LearningRateLimiter rateLimiter,
                           BusinessClock clock) {
        this.assignmentMapper = assignmentMapper;
        this.taskMapper = taskMapper;
        this.courseVersionMapper = courseVersionMapper;
        this.sectionMapper = sectionMapper;
        this.bindingMapper = bindingMapper;
        this.courseMapper = courseMapper;
        this.resourceMapper = resourceMapper;
        this.recordMapper = recordMapper;
        this.openLogMapper = openLogMapper;
        this.learnerGuard = learnerGuard;
        this.progressService = progressService;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * 视频心跳。
     *
     * <p>处理顺序（4–7 在同一事务）：计算 serverDelta → clamp → **先 CAS 推进时钟**
     * → 累加明细 → 状态推进 → 重算完成。
     * CAS 是并发闸门：影响 0 行说明另一个心跳已计时，本次必须计 0。
     */
    @Transactional
    public HeartbeatResponse heartbeat(CurrentUser user, HeartbeatRequest request, String ip) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        TrainingAssignment assignment = requireAssignment(learner, request.assignmentId());
        TrainingTask task = requireLearnableTask(assignment);

        if (request.resourceId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "resourceId 不能为空");
        }
        SectionContext context = requireSectionContext(assignment, request.sectionId(), request.resourceId(),
                true);
        CourseSection section = context.section();

        // 限流：正常客户端 30 秒一次；5 秒内的重复请求一律 429（防止刷接口放大重算）
        rateLimiter.assertHeartbeatAllowed(assignment.getId());

        LocalDateTime now = clock.now();

        // 1) 计算本次可计入的服务端墙钟增量（客户端字段一律不参与）
        LocalDateTime observed = assignment.getLastHeartbeatAt();
        boolean firstHeartbeat = observed == null;
        int credit = 0;
        if (!firstHeartbeat) {
            long delta = Duration.between(observed, now).getSeconds();
            if (delta >= HEARTBEAT_MIN_CREDIT_SECONDS) {
                credit = (int) Math.min(delta, HEARTBEAT_MAX_CREDIT_SECONDS);
            }
        }

        // 2) 先 CAS 推进任务时钟（并发闸门）；0 行 = 另一心跳已计时，本次不重复计入
        boolean advanced = assignmentMapper.advanceClockIfUnchanged(assignment.getId(), observed, now) > 0;
        if (!advanced) {
            credit = 0;
        }

        // 3) 累计该视频的有效观看时长（按视频时长封顶），作为「播完」的校验依据
        Integer mediaDuration = context.resource().getDurationSeconds();
        if (credit > 0) {
            int cap = (mediaDuration == null || mediaDuration <= 0) ? Integer.MAX_VALUE : mediaDuration;
            recordMapper.accumulate(assignment.getId(), context.courseVersion().getId(),
                    section.getId(), request.resourceId(), credit, cap);
        }

        // 4) 播放完成：客户端声称 ended 时，**必须**服务端累计时长已接近视频时长，
        //    否则视为伪造（抓包重放数秒即可刷完所有视频）。未上报过时长的视频无法校验，
        //    此时只信任 ended，但会在响应标注 unverified，供后续补时长后复核。
        boolean finishedClaimed = Boolean.TRUE.equals(request.finished());
        boolean finished = false;
        boolean durationUnverified = false;
        if (finishedClaimed) {
            int watched = watchedSeconds(assignment.getId(), section.getId(), request.resourceId());
            if (mediaDuration == null || mediaDuration <= 0) {
                finished = true;
                durationUnverified = true;
            }
            else {
                finished = watched >= mediaDuration;
                if (!finished) {
                    log.warn("声称播完但服务端累计时长不足，忽略 assignment={} resource={} watched={} duration={}",
                            assignment.getId(), request.resourceId(), watched, mediaDuration);
                }
            }
        }
        if (finished) {
            recordMapper.markCompleted(assignment.getId(), context.courseVersion().getId(),
                    section.getId(), request.resourceId());
        }

        if (advanced) {
            assignmentMapper.markInProgress(assignment.getId(), now);
            assignment.setStatus("IN_PROGRESS");
        }

        LearningProgressService.Progress progress = progressService.recalculate(assignment.getId());

        // 诊断字段只落日志，不参与任何判定
        if (request.positionSeconds() != null || request.clientTickMs() != null) {
            log.debug("heartbeat diagnostic assignment={} section={} resource={} position={} clientTickMs={} finished={}",
                    assignment.getId(), section.getId(), request.resourceId(),
                    request.positionSeconds(), request.clientTickMs(), finished);
        }

        return new HeartbeatResponse(finished, durationUnverified,
                progress.completed() ? "COMPLETED" : assignment.getStatus(), progress.completed());
    }

    /**
     * 非视频资源「已打开」登记。
     *
     * <p>幂等保留首次时间；**不推进**视频时钟（否则打开 PDF 后挂机再播视频会白送时长）。
     */
    @Transactional
    public OpenResourceResponse open(CurrentUser user, OpenResourceRequest request, String ip) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        TrainingAssignment assignment = requireAssignment(learner, request.assignmentId());
        requireLearnableTask(assignment);
        SectionContext context = requireSectionContext(assignment, null, request.resourceId(), false);

        LocalDateTime now = clock.now();
        boolean firstOpen = insertOpenLog(assignment.getId(), request.resourceId(), now);
        if (firstOpen) {
            assignmentMapper.markInProgress(assignment.getId(), now);
        }
        LearningProgressService.Progress progress = progressService.recalculate(assignment.getId());
        return new OpenResourceResponse(firstOpen,
                progress.completed() ? "COMPLETED" : assignment.getStatus(), progress.completed());
    }

    /** 首次登记返回 true；唯一键冲突说明已登记过，保留首次时间。 */
    private boolean insertOpenLog(long assignmentId, long resourceId, LocalDateTime now) {
        ResourceOpenLog entity = new ResourceOpenLog();
        entity.setAssignmentId(assignmentId);
        entity.setResourceId(resourceId);
        entity.setOpenedAt(now);
        try {
            openLogMapper.insert(entity);
            return true;
        }
        catch (DuplicateKeyException ex) {
            return false;
        }
    }

    // ---------- 校验 ----------

    private TrainingAssignment requireAssignment(LearnerContext learner, long assignmentId) {
        TrainingAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务分配不存在");
        }
        if (!learner.owns(assignment.getEmployeeId())) {
            // 不用 404 掩盖：403 明确表示越权，且不泄露他人 assignment 是否存在
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问他人的培训任务");
        }
        return assignment;
    }

    private TrainingTask requireLearnableTask(TrainingAssignment assignment) {
        TrainingTask task = taskMapper.selectById(assignment.getTaskId());
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训任务不存在");
        }
        if (!"PUBLISHED".equals(task.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "任务已关闭，无法继续学习");
        }
        LocalDateTime now = clock.now();
        // 左闭右开：now == startAt 可学；now == learnDeadlineAt 不可学
        if (now.isBefore(task.getStartAt())) {
            throw new BizException(ErrorCode.CONFLICT, "任务尚未开始");
        }
        if (!now.isBefore(task.getLearnDeadlineAt())) {
            throw new BizException(ErrorCode.CONFLICT, "任务已过学习截止时间");
        }
        return task;
    }

    /**
     * 校验章节与资源都属于该 assignment 绑定的包版本，并返回所需上下文。
     *
     * @param requireVideo true 时要求资源必须是 VIDEO（心跳路径）；false 时要求非 VIDEO（open 路径）
     */
    private SectionContext requireSectionContext(TrainingAssignment assignment, Long sectionId,
                                                 long resourceId, boolean requireVideo) {
        List<Long> versionIds = progressService.courseVersionIds(assignment.getPackageVersionId());
        if (versionIds.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训包版本没有课程");
        }
        List<CourseVersion> versions = courseVersionMapperQuery(versionIds);
        Map<Long, CourseVersion> versionById = versions.stream()
                .collect(Collectors.toMap(CourseVersion::getId, v -> v));

        List<CourseSection> sections = sectionMapperQuery(versionIds);
        CourseSection section;
        if (sectionId != null) {
            section = sections.stream().filter(s -> s.getId().equals(sectionId)).findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章节不属于当前任务"));
        }
        else {
            // open 路径只给 resourceId：由绑定行反查章节
            section = null;
        }

        List<CourseVersionResource> bindings = bindingMapperQuery(versionIds);
        if (section != null) {
            bindings = bindings.stream().filter(b -> b.getSectionId().equals(section.getId())).toList();
        }

        CourseVersionResource binding = bindings.stream()
                .filter(b -> b.getResourceId().equals(resourceId))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.FORBIDDEN, "资源不属于当前任务的课程章节"));
        CourseSection resolvedSection = section != null ? section
                : sections.stream().filter(s -> s.getId().equals(binding.getSectionId())).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "资源绑定的章节不存在"));

        CourseResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || !"ACTIVE".equals(resource.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        boolean video = "VIDEO".equals(resource.getKind());
        if (requireVideo && !video) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非视频资源请使用 resources/open 接口");
        }
        if (!requireVideo && video) {
            throw new BizException(ErrorCode.BAD_REQUEST, "视频资源请使用心跳接口");
        }
        CourseVersion ownerVersion = versionById.get(binding.getCourseVersionId());
        if (ownerVersion == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源所属课程版本不属于当前任务");
        }
        // 不校验 duration_seconds：学习可以重复进行，缺时长只是「无法判定完成」，
        // 不应阻止观看。时长缺失的章节在进度里按 0 处理，见 LearningProgressService。
        return new SectionContext(ownerVersion, resolvedSection, resource);
    }

    /** 该视频在服务端的累计有效观看时长（秒）。 */
    private int watchedSeconds(long assignmentId, long sectionId, long resourceId) {
        LearningRecord record = recordMapper.selectOne(Wrappers.<LearningRecord>lambdaQuery()
                .eq(LearningRecord::getAssignmentId, assignmentId)
                .eq(LearningRecord::getSectionId, sectionId)
                .eq(LearningRecord::getResourceId, resourceId));
        if (record == null || record.getEffectiveSeconds() == null) {
            return 0;
        }
        return record.getEffectiveSeconds();
    }

    private List<CourseVersion> courseVersionMapperQuery(List<Long> ids) {
        return courseVersionMapper.selectList(Wrappers.<CourseVersion>lambdaQuery().in(CourseVersion::getId, ids));
    }

    private List<CourseSection> sectionMapperQuery(List<Long> versionIds) {
        return sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                .in(CourseSection::getCourseVersionId, versionIds));
    }

    private List<CourseVersionResource> bindingMapperQuery(List<Long> versionIds) {
        return bindingMapper.selectList(Wrappers.<CourseVersionResource>lambdaQuery()
                .in(CourseVersionResource::getCourseVersionId, versionIds));
    }

    private record SectionContext(CourseVersion courseVersion, CourseSection section, CourseResource resource) {
    }

    /**
     * 落库播放器上报的媒体时长（首次写入者胜）。
     *
     * <p>为什么接受客户端上报：媒体时长是**文件内在属性**，播放器通过
     * {@code loadedmetadata} 事件拿到的是解码后的真实值，服务端无需再解析文件。
     * 风险控制：
     * <ul>
     *   <li>只在 {@code duration_seconds IS NULL} 时写入，之后锁定；
     *       学员改数字只能影响「第一次」，且会被后续不一致上报记录到日志；</li>
     *   <li>不参与「已看时长」计算——已看时长仍只来自服务端墙钟，
     *       所以把时长改小不能伪造完成（累计时长按真实墙钟增长，
     *       改小只会让自己更早达到阈值，但墙钟仍需真实等待）。</li>
     * </ul>
     */
    private void recordMediaDuration(CourseResource resource, Integer mediaDurationSeconds) {
        if (mediaDurationSeconds == null || mediaDurationSeconds <= 0) {
            return;
        }
        if (resource.getDurationSeconds() == null) {
            int written = resourceMapper.fillVideoDurationIfAbsent(resource.getId(), mediaDurationSeconds);
            if (written > 0) {
                resource.setDurationSeconds(mediaDurationSeconds);
                log.info("已记录视频时长 resourceId={} duration={}s",
                        resource.getId(), mediaDurationSeconds);
            }
            return;
        }
        if (!resource.getDurationSeconds().equals(mediaDurationSeconds)) {
            // 不改库，只留痕：用于排查伪造或编码差异
            log.warn("上报时长与既有值不一致，保留原值 resourceId={} stored={} reported={}",
                    resource.getId(), resource.getDurationSeconds(), mediaDurationSeconds);
        }
    }
}