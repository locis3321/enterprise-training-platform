package com.insurance.training.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.exam.entity.ExamAttempt;
import com.insurance.training.exam.mapper.ExamAttemptMapper;
import com.insurance.training.exam.service.ExamAttemptService;
import com.insurance.training.learning.service.BusinessClock;
import com.insurance.training.report.vo.EnterpriseSummaryVo;
import com.insurance.training.report.vo.LearnerReportVo;
import com.insurance.training.report.vo.TaskSummaryVo;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.service.TaskService;
import com.insurance.training.training.vo.TaskAssignmentVo;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表 Excel 导出。
 *
 * <p>统一 SXSSF 流式写（窗口 100），避免数万行驻留堆内存；写前先判行数上限，
 * 超限显式拒绝而不是截断（截断会让管理者以为拿到了全量名单）。
 *
 * <p>四个导出复用 {@link ReportService} 与 {@link TaskService#assignmentRows} 的同一份口径，
 * 不另写查询，避免「界面 60% 导出 55%」。
 */
@Service
public class ReportExportService {

    /** 单表行数上限：超限直接拒绝。 */
    private static final int MAX_ROWS = 50_000;
    private static final int WINDOW_SIZE = 100;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReportService reportService;
    private final TaskService taskService;
    private final TrainingAssignmentMapper assignmentMapper;
    private final ExamAttemptMapper attemptMapper;
    private final EmployeeMapper employeeMapper;
    private final AuditService auditService;
    private final BusinessClock clock;

    public ReportExportService(ReportService reportService, TaskService taskService,
                               TrainingAssignmentMapper assignmentMapper, ExamAttemptMapper attemptMapper,
                               EmployeeMapper employeeMapper, AuditService auditService,
                               BusinessClock clock) {
        this.reportService = reportService;
        this.taskService = taskService;
        this.assignmentMapper = assignmentMapper;
        this.attemptMapper = attemptMapper;
        this.employeeMapper = employeeMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    public record ExportFile(byte[] body, String filename) {
    }

    public ExportFile enterpriseSummary(CurrentUser user, String keyword, String ip) {
        List<EnterpriseSummaryVo> rows = collectAll(
                reportService.enterpriseSummaryAll(user, keyword));
        return write("企业汇总", ip, user, "ENTERPRISE_SUMMARY",
                new String[]{"子公司/部门", "集团/公司", "应培人数", "已完成", "完成率(%)",
                        "有效学时(小时)", "考试人数", "考试通过", "通过率(%)", "逾期未完"},
                rows.stream().map(v -> new Object[]{v.enterpriseName(), v.companyName(),
                        v.assignedCount(), v.completedCount(), v.completionRate(),
                        v.studyHours(), v.examTakers(), v.examPassed(), v.examPassRate(),
                        v.overdueCount()}).toList());
    }

    public ExportFile taskSummary(CurrentUser user, Long enterpriseId, String keyword, String ip) {
        List<TaskSummaryVo> rows = collectAll(
                reportService.taskSummaryAll(user, enterpriseId, keyword));
        return write("任务汇总", ip, user, "TASK_SUMMARY",
                new String[]{"任务标题", "子公司/部门", "开始时间", "学习截止", "任务状态",
                        "下发人数", "学习完成", "完成率(%)", "考试人数", "考试通过", "通过率(%)", "逾期人数"},
                rows.stream().map(v -> new Object[]{v.taskTitle(), v.enterpriseName(),
                        fmt(v.startAt()), fmt(v.learnDeadlineAt()), v.taskStatus(),
                        v.assignedCount(), v.learningCompleted(), v.completionRate(),
                        v.examTakers(), v.examPassed(), v.examPassRate(), v.overdueCount()}).toList());
    }

    /** 任务逐人名单：复用既有 assignmentRows（已含范围校验与服务端学时）。 */
    public ExportFile taskLearners(CurrentUser user, long taskId, String ip) {
        List<TaskAssignmentVo> rows = taskService.assignmentRows(user, taskId);
        if (rows.size() > MAX_ROWS) {
            throw tooManyRows(rows.size());
        }
        Map<Long, Employee> employees = employees(rows.stream().map(TaskAssignmentVo::employeeId).toList());
        Map<Long, List<ExamAttempt>> attempts = submittedAttempts(
                rows.stream().map(TaskAssignmentVo::assignmentId).toList());

        return write("任务名单-" + taskId, ip, user, "TASK_LEARNERS",
                new String[]{"姓名", "手机号", "学习状态", "有效学时(小时)", "开始时间", "完成时间",
                        "考试次数", "最好成绩", "是否通过"},
                rows.stream().map(v -> {
                    Employee e = employees.get(v.employeeId());
                    List<ExamAttempt> list = attempts.getOrDefault(v.assignmentId(), List.of());
                    ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(list);
                    return new Object[]{v.name(), maskPhone(e == null ? null : e.getPhone()),
                            statusText(v.status()), hours(v.effectiveSeconds()),
                            fmt(v.startedAt()), fmt(v.completedAt()),
                            list.size(),
                            best == null ? "—" : best.scoreSum() + "/" + best.totalScore(),
                            best == null ? "—" : (best.passed() ? "是" : "否")};
                }).toList());
    }

    /** 个人明细：学习 sheet + 考试 sheet。 */
    public ExportFile learner(CurrentUser user, long employeeId, String ip) {
        LearnerReportVo detail = reportService.learner(user, employeeId);
        int rows = detail.tasks().stream().mapToInt(t -> t.courses().stream()
                .mapToInt(c -> c.sections().size()).sum() + t.attempts().size()).sum();
        if (rows > MAX_ROWS) {
            throw tooManyRows(rows);
        }

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_SIZE);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            CellStyle header = headerStyle(workbook);

            Sheet study = workbook.createSheet("学习明细");
            writeHeader(study, header, new String[]{"任务", "课程", "章节", "是否完成", "有效学时(小时)", "应看学时(小时)"});
            int r = 1;
            for (LearnerReportVo.TaskDetail task : detail.tasks()) {
                for (LearnerReportVo.CourseDetail course : task.courses()) {
                    for (LearnerReportVo.SectionDetail section : course.sections()) {
                        Row row = study.createRow(r++);
                        setText(row, 0, task.taskTitle());
                        setText(row, 1, course.courseTitle());
                        setText(row, 2, section.title());
                        setText(row, 3, section.completed() ? "已完成" : "未完成");
                        setNumber(row, 4, hours(section.effectiveSeconds()));
                        setNumber(row, 5, hours(section.requiredSeconds()));
                    }
                }
            }

            Sheet exams = workbook.createSheet("考试记录");
            writeHeader(exams, header, new String[]{"任务", "试卷", "得分", "总分", "及格分", "是否通过",
                    "开始时间", "交卷时间", "自动交卷", "最佳成绩"});
            int er = 1;
            for (LearnerReportVo.TaskDetail task : detail.tasks()) {
                for (LearnerReportVo.AttemptRow a : task.attempts()) {
                    Row row = exams.createRow(er++);
                    setText(row, 0, task.taskTitle());
                    setText(row, 1, a.paperTitle());
                    setNumber(row, 2, a.scoreSum() == null ? 0 : a.scoreSum());
                    setNumber(row, 3, a.totalScore() == null ? 0 : a.totalScore());
                    setNumber(row, 4, a.passScore() == null ? 0 : a.passScore());
                    setText(row, 5, a.passed() ? "通过" : "未通过");
                    setText(row, 6, fmt(a.startedAt()));
                    setText(row, 7, fmt(a.submittedAt()));
                    setText(row, 8, a.autoSubmitted() ? "是" : "否");
                    setText(row, 9, a.best() ? "最佳" : "");
                }
            }

            workbook.write(out);
            workbook.dispose();
            auditService.record(user, "REPORT_EXPORTED", "report", null,
                    "type=LEARNER, employeeId=" + employeeId + ", rows=" + rows, ip);
            return new ExportFile(out.toByteArray(), "个人明细-" + employeeId + "-" + clock.now().format(TS) + ".xlsx");
        }
        catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "导出文件生成失败");
        }
    }

    // ---------- 内部 ----------

    /** 分页接口按 MAX_ROWS 作为 pageSize 取一页；超限由报表层直接 400，此处再兜一次。 */
    private <T> List<T> collectAll(com.insurance.training.common.response.PageResult<T> page) {
        if (page.total() > MAX_ROWS) {
            throw tooManyRows(page.total());
        }
        return page.items();
    }

    private ExportFile write(String type, String ip, CurrentUser user, String auditType,
                             String[] headers, List<Object[]> rows) {
        if (rows.size() > MAX_ROWS) {
            throw tooManyRows(rows.size());
        }
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_SIZE);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(type);
            writeHeader(sheet, headerStyle(workbook), headers);
            int r = 1;
            for (Object[] values : rows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    Object v = values[c];
                    if (v instanceof Number n) {
                        setNumber(row, c, n.doubleValue());
                    }
                    else {
                        setText(row, c, v == null ? "" : String.valueOf(v));
                    }
                }
            }
            workbook.write(out);
            workbook.dispose();
            auditService.record(user, "REPORT_EXPORTED", "report", null,
                    "type=" + auditType + ", rows=" + rows.size(), ip);
            return new ExportFile(out.toByteArray(), type + "-" + clock.now().format(TS) + ".xlsx");
        }
        catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "导出文件生成失败");
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private void writeHeader(Sheet sheet, CellStyle style, String[] headers) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
            sheet.setColumnWidth(i, Math.min(48, Math.max(14, headers[i].length() * 3)));
        }
    }

    private void setText(Row row, int col, String value) {
        row.createCell(col).setCellValue(value == null ? "" : value);
    }

    private void setNumber(Row row, int col, double value) {
        row.createCell(col).setCellValue(value);
    }

    private Map<Long, Employee> employees(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return employeeMapper.selectList(Wrappers.<Employee>lambdaQuery().in(Employee::getId, ids))
                .stream().collect(java.util.stream.Collectors.toMap(Employee::getId, e -> e, (a, b) -> a,
                        LinkedHashMap::new));
    }

    private Map<Long, List<ExamAttempt>> submittedAttempts(List<Long> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return Map.of();
        }
        return attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                        .in(ExamAttempt::getAssignmentId, assignmentIds)
                        .eq(ExamAttempt::getStatus, "SUBMITTED"))
                .stream().collect(java.util.stream.Collectors.groupingBy(ExamAttempt::getAssignmentId));
    }

    private BizException tooManyRows(long actual) {
        return new BizException(ErrorCode.BAD_REQUEST,
                "导出行数超过 " + MAX_ROWS + "（当前 " + actual + "），请缩小筛选范围");
    }

    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> "已完成";
            case "IN_PROGRESS" -> "进行中";
            case "NOT_STARTED" -> "未开始";
            default -> status;
        };
    }

    private static double hours(int seconds) {
        return BigDecimal.valueOf(seconds / 3600.0).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(DT);
    }

    /** 手机号脱敏，与列表一致；不提供明文导出开关。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? "" : phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
