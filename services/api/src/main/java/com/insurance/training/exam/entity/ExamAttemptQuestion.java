package com.insurance.training.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 开考题目快照（表 exam_attempt_question）。
 *
 * <p>为什么快照：开考后管理员可能改题或停用，进行中的考试必须保持原题与 原判分口径，
 * 否则历史成绩无法解释。因此题干、选项、正确答案、分值全部复制一份。
 */
@TableName("exam_attempt_question")
public class ExamAttemptQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long attemptId;
    private Long questionId;
    private String stem;
    private String type;
    /** [{"label":"A","content":"..."}] */
    private String optionsJson;
    /** 正确答案标签，如 ["A","C"]；仅服务端判分使用，绝不下发学员。 */
    private String correctJson;
    private Integer score;
    private Integer sortNo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(Long attemptId) {
        this.attemptId = attemptId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getStem() {
        return stem;
    }

    public void setStem(String stem) {
        this.stem = stem;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public void setOptionsJson(String optionsJson) {
        this.optionsJson = optionsJson;
    }

    public String getCorrectJson() {
        return correctJson;
    }

    public void setCorrectJson(String correctJson) {
        this.correctJson = correctJson;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }
}
