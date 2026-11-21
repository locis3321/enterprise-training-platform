package com.insurance.training.audit.service;

import com.insurance.training.audit.entity.OperationLog;
import com.insurance.training.audit.mapper.OperationLogMapper;
import com.insurance.training.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志写入。
 *
 * <p>约束：{@code detail} 只允许写业务标识与结果，禁止写入手机号、身份证、口令等敏感值
 * （调用方负责脱敏；这里再截断长度防止异常长内容撑爆表）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_DETAIL = 1800;

    private final OperationLogMapper mapper;

    public AuditService(OperationLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(CurrentUser actor, String action, String targetType, Long targetId,
                       String detail, String ip) {
        try {
            OperationLog entity = new OperationLog();
            entity.setActorType(actor == null ? "SYSTEM" : actor.type().name());
            entity.setActorId(actor == null ? null : actor.id());
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setDetail(truncate(detail));
            entity.setIp(ip);
            entity.setCreatedAt(LocalDateTime.now());
            mapper.insert(entity);
        }
        catch (RuntimeException ex) {
            // 审计失败不能阻断主流程，但必须留下痕迹，便于补偿与告警
            log.error("审计日志写入失败 action={}, targetType={}, targetId={}", action, targetType, targetId, ex);
        }
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }
}
