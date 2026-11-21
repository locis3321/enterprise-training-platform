package com.insurance.training.report.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.exam.mapper.ExamAttemptMapper;
import com.insurance.training.learning.service.BusinessClock;
import com.insurance.training.report.vo.EnterpriseSummaryVo;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.service.TaskService;
import com.insurance.training.training.vo.TaskAssignmentVo;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 导出文件内容与边界。 */
class ReportExportServiceTest {

    private final ReportService reportService = mock(ReportService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final TrainingAssignmentMapper assignmentMapper = mock(TrainingAssignmentMapper.class);
    private final ExamAttemptMapper attemptMapper = mock(ExamAttemptMapper.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BusinessClock clock = mock(BusinessClock.class);

    private final ReportExportService service = new ReportExportService(reportService, taskService,
            assignmentMapper, attemptMapper, employeeMapper, auditService, clock);

    private final CurrentUser admin = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
            DataScopeType.ALL, null, null, "平台");

    private List<EnterpriseSummaryVo> summaryRows(int n) {
        List<EnterpriseSummaryVo> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new EnterpriseSummaryVo(100L + i, "子公司" + i, "集团A", 5, 3, 60.0, 2.0,
                    3, 2, 66.7, 2));
        }
        return rows;
    }

    @Test
    @DisplayName("企业汇总导出：表头与行数可被 POI 回读")
    void enterpriseSummaryFileIsReadable() throws Exception {
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 11, 12, 1, 0));
        when(reportService.enterpriseSummaryAll(any(), any())).thenReturn(
                PageResult.of(summaryRows(3), 3, 1, 3));

        var file = service.enterpriseSummary(admin, null, "1.1.1.1");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.body()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("子公司/部门");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("应培人数");
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("子公司0");
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(5);
        }
        assertThat(file.filename()).startsWith("企业汇总-").endsWith(".xlsx");
    }

    @Test
    @DisplayName("任务逐人导出：手机号脱敏，文件内无完整 11 位手机号")
    void taskLearnersMaskPhone() throws Exception {
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(taskService.assignmentRows(admin, 9L)).thenReturn(List.of(
                new TaskAssignmentVo(11L, 1L, "张三", "COMPLETED", 3600,
                        LocalDateTime.now(), LocalDateTime.now())));
        Employee e = new Employee();
        e.setId(1L);
        e.setName("张三");
        e.setPhone("13800001234");
        when(employeeMapper.selectList(any())).thenReturn(List.of(e));
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        var file = service.taskLearners(admin, 9L, "1.1.1.1");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.body()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("138****1234");
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                for (int c = 0; c < 9; c++) {
                    var cell = sheet.getRow(r).getCell(c);
                    if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                        assertThat(cell.getStringCellValue()).doesNotContain("13800001234");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("超过 5 万行显式拒绝，不产出文件")
    void rejectsTooManyRows() {
        List<EnterpriseSummaryVo> huge = new ArrayList<>();
        for (int i = 0; i < 50_001; i++) {
            huge.add(new EnterpriseSummaryVo(i, "e", "c", 0, 0, 0, 0, 0, 0, 0, 0));
        }
        when(reportService.enterpriseSummaryAll(any(), any())).thenReturn(
                PageResult.of(huge, 50_001, 1, 50_001));

        assertThatThrownBy(() -> service.enterpriseSummary(admin, null, "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过 50000");
    }

    @Test
    @DisplayName("空结果只导出表头")
    void emptyResultHasHeaderOnly() throws Exception {
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(reportService.taskSummaryAll(any(), any(), any())).thenReturn(
                PageResult.of(List.of(), 0, 1, 1));

        var file = service.taskSummary(admin, null, null, "1.1.1.1");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.body()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isZero();
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 12);
        }
    }

    @Test
    @DisplayName("导出写审计，detail 不含手机号")
    void auditHasNoPhone() {
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(reportService.enterpriseSummaryAll(any(), any())).thenReturn(
                PageResult.of(summaryRows(1), 1, 1, 1));

        service.enterpriseSummary(admin, null, "1.1.1.1");

        org.mockito.Mockito.verify(auditService).record(any(), org.mockito.ArgumentMatchers.eq("REPORT_EXPORTED"),
                org.mockito.ArgumentMatchers.eq("report"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(d -> d != null && !d.matches(".*1[3-9]\\d{9}.*")),
                org.mockito.ArgumentMatchers.eq("1.1.1.1"));
    }
}
