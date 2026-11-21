package com.insurance.training.training.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 追加分配：重复分配幂等，任务关闭后拒绝。 */
class AssignmentServiceTest {

    private final TrainingAssignmentMapper assignmentMapper = Mockito.mock(TrainingAssignmentMapper.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final AssignmentService service = new AssignmentService(assignmentMapper, auditService);

    @Test
    @DisplayName("正常分配：逐员工插入，返回新增条数")
    void assignsEachEmployee() {
        TrainingTask task = task("PUBLISHED");
        Mockito.when(assignmentMapper.insertIfTaskOpen(Mockito.any(TrainingAssignment.class))).thenReturn(1);

        int inserted = service.assign(task, List.of(emp(101L), emp(102L)), new com.insurance.training.common.security.CurrentUser(
                3L, com.insurance.training.common.security.PrincipalType.ADMIN, "INSURANCE_ADMIN",
                com.insurance.training.common.security.DataScopeType.INSURANCE_COMPANY, 10L, null, "管理员"),
                "127.0.0.1");

        assertThat(inserted).isEqualTo(2);
        ArgumentCaptor<TrainingAssignment> captor = ArgumentCaptor.forClass(TrainingAssignment.class);
        Mockito.verify(assignmentMapper, Mockito.times(2)).insertIfTaskOpen(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(a -> {
            assertThat(a.getTaskId()).isEqualTo(50L);
            assertThat(a.getPackageVersionId()).isEqualTo(11L);
            assertThat(a.getStatus()).isEqualTo("NOT_STARTED");
            assertThat(a.getLastHeartbeatAt()).isNull();
        });
    }

    @Test
    @DisplayName("重复分配同一员工幂等：唯一键冲突被吞掉，不计入新增")
    void duplicateAssignmentIsIdempotent() {
        TrainingTask task = task("PUBLISHED");
        Mockito.when(assignmentMapper.insertIfTaskOpen(Mockito.any(TrainingAssignment.class)))
                .thenThrow(new DuplicateKeyException("uk_assignment_task_employee"));

        int inserted = service.assign(task, List.of(emp(101L)),
                new com.insurance.training.common.security.CurrentUser(3L,
                        com.insurance.training.common.security.PrincipalType.ADMIN, "INSURANCE_ADMIN",
                        com.insurance.training.common.security.DataScopeType.INSURANCE_COMPANY, 10L, null, "管理员"),
                "127.0.0.1");

        assertThat(inserted).isZero();
    }

    @Test
    @DisplayName("任务已关闭 → 409，且一条都不插")
    void rejectsClosedTask() {
        TrainingTask task = task("CLOSED");

        assertThatThrownBy(() -> service.assign(task, List.of(emp(101L)),
                new com.insurance.training.common.security.CurrentUser(3L,
                        com.insurance.training.common.security.PrincipalType.ADMIN, "INSURANCE_ADMIN",
                        com.insurance.training.common.security.DataScopeType.INSURANCE_COMPANY, 10L, null, "管理员"),
                "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verifyNoInteractions(assignmentMapper);
    }

    @Test
    @DisplayName("条件插入 0 行（任务被并发关闭）→ 409 而不是当成幂等成功")
    void conflictsWhenTaskClosedConcurrently() {
        TrainingTask task = task("PUBLISHED");
        Mockito.when(assignmentMapper.insertIfTaskOpen(Mockito.any(TrainingAssignment.class))).thenReturn(0);

        assertThatThrownBy(() -> service.assign(task, List.of(emp(101L)),
                new com.insurance.training.common.security.CurrentUser(3L,
                        com.insurance.training.common.security.PrincipalType.ADMIN, "INSURANCE_ADMIN",
                        com.insurance.training.common.security.DataScopeType.INSURANCE_COMPANY, 10L, null, "管理员"),
                "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------- fixtures ----------

    private static TrainingTask task(String status) {
        TrainingTask t = new TrainingTask();
        t.setId(50L);
        t.setInsuranceCompanyId(10L);
        t.setEnterpriseId(1L);
        t.setPackageVersionId(11L);
        t.setTitle("任务");
        t.setStatus(status);
        return t;
    }

    private static Employee emp(long id) {
        Employee e = new Employee();
        e.setId(id);
        e.setEnterpriseId(1L);
        e.setInsuranceCompanyId(10L);
        e.setStatus(1);
        return e;
    }
}