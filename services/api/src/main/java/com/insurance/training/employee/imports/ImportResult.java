package com.insurance.training.employee.imports;

import java.util.List;

/** 员工导入的逐行结果。行级失败不影响其他行。 */
public record ImportResult(
        int total,
        int success,
        int failed,
        List<Row> rows
) {

    /**
     * @param rowNo  Excel 行号（从 2 开始，1 为表头），便于管理员对照文件修改
     * @param name   文件中的姓名
     * @param phone  脱敏后的手机号，便于定位但不在响应里泄露完整号码
     * @param ok     是否成功
     * @param action CREATED / UPDATED / SKIPPED
     * @param error  失败原因；成功时为 null
     */
    public record Row(int rowNo, String name, String phone, boolean ok, String action, String error) {
    }
}
