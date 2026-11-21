package com.insurance.training.employee.imports;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.employee.vo.EmployeeVo;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 员工 Excel 批量导入。
 *
 * <p>关键约定：
 * <ul>
 *   <li>企业按「编码」匹配，必须在当前登录者的数据范围内；</li>
 *   <li>手机号校验格式 + 同企业内唯一（文件内重复与库内已存在都算冲突）；</li>
 *   <li>逐行返回结果，任何单行错误都不会阻断其他行；整批在一个事务内提交，</li>
 *   <li>不写手机号明文到审计日志。</li>
 * </ul>
 */
@Service
public class EmployeeImportService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeImportService.class);
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final int MAX_ROWS = 2000;
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final EmployeeMapper employeeMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final AuditService auditService;
    private final com.insurance.training.identity.service.LearnerAccountService accountService;

    public EmployeeImportService(EmployeeMapper employeeMapper, EnterpriseMapper enterpriseMapper,
                                 AuditService auditService,
                                 com.insurance.training.identity.service.LearnerAccountService accountService) {
        this.employeeMapper = employeeMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.auditService = auditService;
        this.accountService = accountService;
    }

    /** 生成模板：表头 + 示例行（示例为注释性数据，导入时会被校验）。 */
    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("员工导入");
            Row header = sheet.createRow(0);
            String[] columns = {"姓名", "手机号", "企业编码", "身份证号（可选）"};
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
                sheet.setColumnWidth(i, 22 * 256);
            }
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("张三");
            sample.createCell(1).setCellValue("13800138000");
            sample.createCell(2).setCellValue("ENT-DEMO");
            sample.createCell(3).setCellValue("");
            workbook.write(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模板生成失败");
        }
    }

    @Transactional
    public ImportResult importFile(CurrentUser user, MultipartFile file, String ip) {
        DataScopeGuard.requireAdmin();
        validateFile(file);

        List<ImportResult.Row> rows = new ArrayList<>();
        Map<String, Long> enterpriseIdByCode = loadEnterprises(user);
        int success = 0;
        int failed = 0;

        try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
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

            Set<String> seenInFile = new HashSet<>();
            for (int i = 1; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) {
                    continue;
                }
                String name = text(row, 0);
                String phone = text(row, 1);
                String code = text(row, 2);
                String idCard = text(row, 3);

                ImportResult.Row result = processRow(user, i + 1, name, phone, code, idCard,
                        enterpriseIdByCode, seenInFile);
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
            log.warn("员工导入解析失败", ex);
            throw new BizException(ErrorCode.BAD_REQUEST, "文件解析失败，请使用下载的模板重新填写");
        }

        ImportResult result = new ImportResult(rows.size(), success, failed, rows);
        auditService.record(user, "EMPLOYEE_IMPORTED", "employee", null,
                "total=" + result.total() + ",success=" + success + ",failed=" + failed, ip);
        return result;
    }

    private ImportResult.Row processRow(CurrentUser user, int rowNo, String name, String phone, String code,
                                        String idCard, Map<String, Long> enterpriseIdByCode,
                                        Set<String> seenInFile) {
        String masked = EmployeeVo.maskPhone(phone);
        if (name.isEmpty()) {
            return fail(rowNo, name, masked, "姓名不能为空");
        }
        if (name.length() > 64) {
            return fail(rowNo, name, masked, "姓名长度不能超过 64");
        }
        if (!PHONE.matcher(phone).matches()) {
            return fail(rowNo, name, masked, "手机号格式不正确");
        }
        if (!idCard.isEmpty() && !idCard.matches("^\\d{17}[\\dXx]$")) {
            return fail(rowNo, name, masked, "身份证号格式不正确");
        }
        Long enterpriseId = enterpriseIdByCode.get(code);
        if (code.isEmpty()) {
            return fail(rowNo, name, masked, "企业编码不能为空");
        }
        if (enterpriseId == null) {
            return fail(rowNo, name, masked, "企业编码不存在或不在您的数据范围内");
        }
        String dedupeKey = enterpriseId + "|" + phone;
        if (!seenInFile.add(dedupeKey)) {
            return fail(rowNo, name, masked, "文件内该企业手机号重复");
        }

        Enterprise enterprise = enterpriseMapper.selectById(enterpriseId);
        if (enterprise == null || !Integer.valueOf(1).equals(enterprise.getStatus())) {
            return fail(rowNo, name, masked, "所属企业已停用");
        }

        Employee existing = employeeMapper.selectOne(Wrappers.<Employee>lambdaQuery()
                .eq(Employee::getEnterpriseId, enterpriseId)
                .eq(Employee::getPhone, phone));

        if (existing == null) {
            Employee entity = new Employee();
            entity.setInsuranceCompanyId(enterprise.getInsuranceCompanyId());
            entity.setEnterpriseId(enterpriseId);
            entity.setName(name);
            entity.setPhone(phone);
            entity.setIdCardNo(idCard.isEmpty() ? null : idCard);
            entity.setStatus(1);
            employeeMapper.insert(entity);
            // 首次导入即开通学员账号（初始密码 anzhi+后6位）；已存在的账号不覆盖密码
            accountService.ensureAccount(phone);
            return new ImportResult.Row(rowNo, name, masked, true, "CREATED", null);
        }

        if (!Integer.valueOf(1).equals(existing.getStatus())) {
            return fail(rowNo, name, masked, "该员工已停用，请先在员工列表启用");
        }
        existing.setName(name);
        if (!idCard.isEmpty()) {
            existing.setIdCardNo(idCard);
        }
        employeeMapper.updateById(existing);
        // 更新路径同样补建账号：账号化上线前导入的老员工在下次导入时自动补齐，
        // 但已有密码绝不被覆盖（ensureAccount 只在账号不存在时写入）
        accountService.ensureAccount(phone);
        return new ImportResult.Row(rowNo, name, masked, true, "UPDATED", null);
    }

    /** 只加载当前登录者可见且在用的企业编码，避免跨租户通过编码探测数据。 */
    private Map<String, Long> loadEnterprises(CurrentUser user) {
        var wrapper = Wrappers.<Enterprise>lambdaQuery();
        if (!user.isSuperAdmin()) {
            if (user.companyId() == null) {
                return Map.of();
            }
            if (user.scope() == com.insurance.training.common.security.DataScopeType.ENTERPRISE) {
                if (user.enterpriseId() == null) {
                    return Map.of();
                }
                wrapper.eq(Enterprise::getId, user.enterpriseId());
            }
            else {
                wrapper.eq(Enterprise::getInsuranceCompanyId, user.companyId());
            }
        }
        Map<String, Long> result = new HashMap<>();
        for (Enterprise enterprise : enterpriseMapper.selectList(wrapper)) {
            result.put(enterprise.getCode(), enterprise.getId());
        }
        return result;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要导入的文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能超过 5MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 .xlsx 文件");
        }
    }

    private boolean isBlankRow(Row row) {
        if (row == null) {
            return true;
        }
        for (int i = 0; i < 4; i++) {
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
            // 公式单元格一律视为非法内容，避免公式注入与不可预期计算
            return "__FORMULA__";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }
        return cell.getStringCellValue() == null ? "" : cell.getStringCellValue().trim();
    }

    private ImportResult.Row fail(int rowNo, String name, String phone, String error) {
        return new ImportResult.Row(rowNo, name, phone, false, "SKIPPED", error);
    }
}
