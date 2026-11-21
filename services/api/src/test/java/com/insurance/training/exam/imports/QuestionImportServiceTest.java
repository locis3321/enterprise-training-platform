package com.insurance.training.exam.imports;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.exam.dto.QuestionRequest;
import com.insurance.training.exam.service.QuestionService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 题库导入的逐行校验。
 *
 * <p>关键语义：任一行失败只记该行错误，其他行照常导入；正确答案数量与题型必须一致。
 */
class QuestionImportServiceTest {

    private static final long COURSE_ID = 35L;
    private static final CurrentUser ADMIN = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
            DataScopeType.ALL, null, null, "admin");

    private final QuestionService questionService = Mockito.mock(QuestionService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final QuestionImportService importService = new QuestionImportService(questionService, auditService);

    @Test
    @DisplayName("合法行入库、非法行按行号报错，互不影响")
    void reportsRowErrorsWithoutAbortingOthers() {
        MockMultipartFile file = fileOf(
                row("合法单选", "SINGLE", "对", "错", "", "", "", "A"),
                row("合法多选", "MULTIPLE", "对一", "对二", "错", "", "", "A,B"),
                row("单选两答案", "SINGLE", "一", "二", "三", "", "", "A,B"),
                row("多选一答案", "MULTIPLE", "一", "二", "", "", "", "A"),
                row("缺答案", "SINGLE", "一", "二", "", "", "", ""),
                row("类型错误", "JUDGE", "一", "二", "", "", "", "A"));

        QuestionImportResult result = importService.importFile(ADMIN, COURSE_ID, file, "127.0.0.1");

        assertThat(result.total()).isEqualTo(6);
        assertThat(result.success()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(4);
        // Excel 行号从 2 开始（第 1 行是表头）
        assertThat(result.rows()).extracting(QuestionImportResult.Row::rowNo)
                .containsExactly(2, 3, 4, 5, 6, 7);
        assertThat(result.rows().get(2).error()).contains("单选题必须且只能有 1 个正确答案");
        assertThat(result.rows().get(3).error()).contains("多选题至少需要 2 个正确答案");
        assertThat(result.rows().get(4).error()).contains("必须填写正确答案");
        assertThat(result.rows().get(5).error()).contains("SINGLE 或 MULTIPLE");

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        Mockito.verify(questionService, Mockito.times(2))
                .create(Mockito.eq(ADMIN), Mockito.eq(COURSE_ID), captor.capture(), Mockito.anyString());
        List<QuestionRequest> created = captor.getAllValues();
        assertThat(created.get(0).type()).isEqualTo("SINGLE");
        assertThat(created.get(0).options()).hasSize(2)
                .filteredOn(QuestionRequest.OptionRequest::correct).hasSize(1);
        assertThat(created.get(1).type()).isEqualTo("MULTIPLE");
        assertThat(created.get(1).options()).hasSize(3)
                .filteredOn(QuestionRequest.OptionRequest::correct).hasSize(2);

        Mockito.verify(auditService).record(Mockito.eq(ADMIN), Mockito.eq("QUESTIONS_IMPORTED"),
                Mockito.eq("course"), Mockito.eq(COURSE_ID),
                Mockito.eq("total=6,success=2,failed=4"), Mockito.eq("127.0.0.1"));
    }

    @Test
    @DisplayName("正确答案超出选项范围时报错，而不是静默丢分")
    void rejectsCorrectLabelOutsideOptions() {
        MockMultipartFile file = fileOf(row("越界答案", "SINGLE", "一", "二", "", "", "", "D"));

        QuestionImportResult result = importService.importFile(ADMIN, COURSE_ID, file, "127.0.0.1");

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(0).error()).contains("超出选项范围");
        Mockito.verify(questionService, Mockito.never())
                .create(Mockito.any(), Mockito.anyLong(), Mockito.any(), Mockito.anyString());
    }

    @Test
    @DisplayName("非 .xlsx 文件直接拒绝")
    void rejectsNonXlsxFile() {
        MockMultipartFile file = new MockMultipartFile("file", "questions.csv",
                "text/csv", "a,b".getBytes());

        assertThatThrownBy(() -> importService.importFile(ADMIN, COURSE_ID, file, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(".xlsx");
    }

    @Test
    @DisplayName("模板含表头与一行示例")
    void templateHasHeaderAndSample() throws Exception {
        byte[] bytes = importService.template();

        try (Workbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("题目");
            assertThat(sheet.getRow(0).getCell(7).getStringCellValue()).isEqualTo("正确答案");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("SINGLE");
            assertThat(sheet.getRow(1).getCell(7).getStringCellValue()).isEqualTo("A");
        }
    }

    private static String[] row(String stem, String type, String a, String b, String c,
                                String d, String e, String correct) {
        return new String[]{stem, type, a, b, c, d, e, correct};
    }

    private static MockMultipartFile fileOf(String[]... rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库导入");
            Row header = sheet.createRow(0);
            String[] cols = {"题目", "类型", "选项A", "选项B", "选项C", "选项D", "选项E", "正确答案"};
            for (int i = 0; i < cols.length; i++) {
                header.createCell(i).setCellValue(cols[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "questions.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
