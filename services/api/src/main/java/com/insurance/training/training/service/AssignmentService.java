package com.insurance.training.training.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务分配写入。
 *
 * <p>分配用 {@code INSERT ... SELECT ... WHERE status='PUBLISHED'}：先查任务状态再插存在窗口，
 * 关闭后仍会被追加分配。影响 0 行为「任务已关闭」→ 409。
 * 重复分配同一员工由唯一键兜底，视为幂等成功。
 */
@Service
public class AssignmentService {

    private final TrainingAssignmentMapper assignmentMapper;
    private final AuditService auditService;

    public AssignmentService(TrainingAssignmentMapper assignmentMapper, AuditService auditService) {
        this.assignmentMapper = assignmentMapper;
        this.auditService = auditService;
    }

    /**
     * 逐员工生成 assignment。
     *
     * @return 实际新增条数（重复分配不计入）
     */
    public int assign(TrainingTask task, List<Employee> employees, CurrentUser actor, String ip) {
        if (!"PUBLISHED".equals(task.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "任务已关闭或未发布，不能追加分配");
        }
        int inserted = 0;
        for (Employee employee : employees) {
            TrainingAssignment assignment = new TrainingAssignment();
            assignment.setTaskId(task.getId());
            assignment.setEmployeeId(employee.getId());
            assignment.setPackageVersionId(task.getPackageVersionId());
            assignment.setStatus("NOT_STARTED");
            // lastHeartbeatAt 必须保持 NULL：预填当前时间会让首次心跳白得一段时长
            try {
                if (assignmentMapper.insertIfTaskOpen(assignment) > 0) {
                    inserted++;
                }
                else {
                    throw new BizException(ErrorCode.CONFLICT, "任务已关闭或未发布，不能追加分配");
                }
            }
            catch (DuplicateKeyException ex) {
                // 已有该员工的分配，幂等
            }
        }
        auditService.record(actor, "TASK_ASSIGNED", "training_task", task.getId(),
                "inserted=" + inserted + ",requested=" + employees.size(), ip);
        return inserted;
    }
}