package com.insurance.training.exam.imports;

import com.insurance.training.exam.dto.QuestionRequest;

import java.util.List;

/** 题库 Excel 导入结果：逐行返回，任一行错误不影响其他行。 */
public record QuestionImportResult(
        int total,
        int success,
        int failed,
        List<Row> rows
) {

    /**
     * 单行结果。
     *
     * @param rowNo  Excel 行号（从 1 开始，含表头行偏移），便于用户定位
     * @param stem   题干摘要（过长截断），用于回显
     * @param error  失败原因；成功为 null
     */
    public record Row(int rowNo, String stem, boolean ok, String error) {
    }

    /** 供导入服务内部构造请求用。 */
    public static QuestionRequest toRequest(String stem, String type, List<String> contents,
                                            List<String> correctLabels) {
        List<QuestionRequest.OptionRequest> options = new java.util.ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            String label = String.valueOf((char) ('A' + i));
            options.add(new QuestionRequest.OptionRequest(contents.get(i), correctLabels.contains(label)));
        }
        return new QuestionRequest(stem, type, options);
    }
}
