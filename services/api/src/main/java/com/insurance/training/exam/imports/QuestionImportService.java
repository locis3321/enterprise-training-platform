package com.insurance.training.exam.imports;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.exam.dto.QuestionRequest;
import com.insurance.training.exam.service.QuestionService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 题库 Excel 导入。
 *
 * <p>模板列：题目 / 类型 / 选项A–E / 正确答案。
 * 正确答案写 {@code A} 或 {@code A,C}（逗号分隔）；类型写 {@code SINGLE} 或 {@code MULTIPLE}。
 *
 * <p>逐行独立校验：任一行失败只记该行错误，其他行照常导入（与员工导入一致）。
 */
@Service
public class QuestionImportService {

    private static final Logger log = LoggerFactory.getLogger(QuestionImportService.class);
    private static final int MAX_ROWS = 2000;
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_OPTIONS = 5;

    private final QuestionService questionService;
    private final AuditService auditService;

    public QuestionImportService(QuestionService questionService, AuditService auditService) {
        this.questionService = questionService;
        this.auditService = auditService;
    }

    /** 生成导入模板（含一行示例）。 */
    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库导入");
            Row header = sheet.createRow(0);
            String[] cols = {"题目", "类型", "选项A", "选项B", "选项C", "选项D", "选项E", "正确答案"};
            for (int i = 0; i < cols.length; i++) {
                header.createCell(i).setCellValue(cols[i]);
                sheet.setColumnWidth(i, 20 * 256);
            }
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("发现火情应首先做什么？");
            sample.createCell(1).setCellValue("SINGLE");
            sample.createCell(2).setCellValue("立即报警并组织疏散");
            sample.createCell(3).setCellValue("先拍照记录");
            sample.createCell(4).setCellValue("等待领导指示");
            sample.createCell(5).setCellValue("");
            sample.createCell(6).setCellValue("");
            sample.createCell(7).setCellValue("A");
            wb.write(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模板生成失败");
        }
    }

    public QuestionImportResult importFile(CurrentUser user, long courseId, MultipartFile file, String ip) {
        validateFile(file);
        List<QuestionImportResult.Row> rows = new ArrayList<>();
        int success = 0;
        int failed = 0;

        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文件缺少工作表");
            }
            int lastRow = sheet.getLastRowNum();
            if (lastRow < 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文件没有数据行");
            }
            if (lastRow > MAX_ROWS) {
                throw new BizException(ErrorCode.BAD_REQUEST, "单次导入不能超过 " + MAX_ROWS + " 行");
            }

            for (int i = 1; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) {
                    continue;
                }
                QuestionImportResult.Row result = processRow(user, courseId, i + 1, row, ip);
                rows.add(result);
                if (result.ok()) {
                    success++;
                }
                else {
                    failed++;
                }
            }
        }
        catch (BizException ex) {
            throw ex;
        }
        catch (Exception ex) {
            log.warn("题库导入解析失败", ex);
            throw new BizException(ErrorCode.BAD_REQUEST, "文件解析失败，请使用下载的模板重新填写");
        }

        QuestionImportResult result = new QuestionImportResult(rows.size(), success, failed, rows);
        auditService.record(user, "QUESTIONS_IMPORTED", "course", courseId,
                "total=" + result.total() + ",success=" + success + ",failed=" + failed, ip);
        return result;
    }

    private QuestionImportResult.Row processRow(CurrentUser user, long courseId, int rowNo, Row row, String ip) {
        String stem = text(row, 0);
        String type = text(row, 1).toUpperCase();
        String correct = text(row, 7);
        String summary = stem.length() > 40 ? stem.substring(0, 40) : stem;

        if (stem.isEmpty()) {
            return new QuestionImportResult.Row(rowNo, summary, false, "题目不能为空");
        }
        if (!"SINGLE".equals(type) && !"MULTIPLE".equals(type)) {
            return new QuestionImportResult.Row(rowNo, summary, false, "类型只能是 SINGLE 或 MULTIPLE");
        }
        if (correct.isEmpty()) {
            return new QuestionImportResult.Row(rowNo, summary, false, "必须填写正确答案");
        }

        List<String> contents = new ArrayList<>();
        for (int c = 2; c <= 6; c++) {
            String value = text(row, c);
            if (!value.isEmpty()) {
                contents.add(value);
            }
        }
        if (contents.size() < 2 || contents.size() > MAX_OPTIONS) {
            return new QuestionImportResult.Row(rowNo, summary, false,
                    "选项数量必须在 2-" + MAX_OPTIONS + " 之间，当前 " + contents.size());
        }

        List<String> correctLabels = Arrays.stream(correct.toUpperCase().split("[,，\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        // 正确答案必须落在实际存在的选项范围内
        for (String label : correctLabels) {
            int idx = label.charAt(0) - 'A';
            if (label.length() != 1 || idx < 0 || idx >= contents.size()) {
                return new QuestionImportResult.Row(rowNo, summary, false,
                        "正确答案 " + label + " 超出选项范围（当前 " + contents.size() + " 个选项）");
            }
        }
        long correctCount = correctLabels.stream().distinct().count();
        if ("SINGLE".equals(type) && correctCount != 1) {
            return new QuestionImportResult.Row(rowNo, summary, false,
                    "单选题必须且只能有 1 个正确答案，当前 " + correctCount + " 个");
        }
        if ("MULTIPLE".equals(type) && correctCount < 2) {
            return new QuestionImportResult.Row(rowNo, summary, false,
                    "多选题至少需要 2 个正确答案；若只有 1 个正确项请改用单选");
        }

        try {
            QuestionRequest request = QuestionImportResult.toRequest(stem, type, contents, correctLabels);
            questionService.create(user, courseId, request, ip);
            return new QuestionImportResult.Row(rowNo, summary, true, null);
        }
        catch (BizException ex) {
            return new QuestionImportResult.Row(rowNo, summary, false, ex.getMessage());
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要导入的文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能超过 5MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 .xlsx 文件");
        }
    }

    private boolean isBlankRow(Row row) {
        if (row == null) {
            return true;
        }
        for (int i = 0; i < 8; i++) {
            if (!text(row, i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String text(Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            // 公式单元格视为非法，避免公式注入
            return "__FORMULA__";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }
        return cell.getStringCellValue() == null ? "" : cell.getStringCellValue().trim();
    }
}
