package com.insurance.training.exam.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.exam.dto.QuestionRequest;
import com.insurance.training.exam.entity.ExamPaper;
import com.insurance.training.exam.entity.Question;
import com.insurance.training.exam.entity.QuestionOption;
import com.insurance.training.exam.mapper.QuestionMapper;
import com.insurance.training.exam.mapper.QuestionOptionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 题目编辑边界：被已发布试卷引用时仍可修改。
 *
 * <p>判分读的是 attempt 内的题目快照，所以改题库不会污染历史成绩；
 * 这条边界一旦退回「409 拦截」，管理员就再也改不了写错的题。
 */
class QuestionEditBoundaryTest {

    private static final long COURSE_ID = 35L;
    private static final long QUESTION_ID = 3L;
    private static final CurrentUser ADMIN = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
            DataScopeType.ALL, null, null, "admin");

    private final QuestionMapper questionMapper = Mockito.mock(QuestionMapper.class);
    private final QuestionOptionMapper optionMapper = Mockito.mock(QuestionOptionMapper.class);
    private final QuestionReferenceChecker referenceChecker = Mockito.mock(QuestionReferenceChecker.class);
    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);

    private final QuestionService service = new QuestionService(
            questionMapper, optionMapper, referenceChecker, courseMapper, auditService);

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // 让 Lambda 列名解析可用（Question 的 lambdaUpdate 需要实体元数据）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Question.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void bindAdmin() {
        // DataScopeGuard 读的是请求上下文；单测里用 mock 请求绑定管理员主体
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        CurrentUserHolder.set(ADMIN);
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("被已发布试卷引用的题目允许编辑，不再返回 409")
    void allowsEditingReferencedQuestion() {
        stubExistingQuestion();
        Mockito.when(referenceChecker.isReferencedByPublished(QUESTION_ID)).thenReturn(true);

        QuestionRequest request = new QuestionRequest("修正后的题干", "SINGLE", List.of(
                new QuestionRequest.OptionRequest("对", true),
                new QuestionRequest.OptionRequest("错", false)));

        assertThatCode(() -> service.update(ADMIN, QUESTION_ID, request, "127.0.0.1"))
                .doesNotThrowAnyException();
        Mockito.verify(questionMapper).update(Mockito.isNull(), Mockito.any());
        Mockito.verify(auditService).record(Mockito.eq(ADMIN), Mockito.eq("QUESTION_UPDATED"),
                Mockito.eq("question"), Mockito.eq(QUESTION_ID), Mockito.isNull(), Mockito.eq("127.0.0.1"));
    }

    @Test
    @DisplayName("被引用题目仍然禁止删除，并提示取消发布或停用")
    void stillForbidsDeletingReferencedQuestion() {
        stubExistingQuestion();
        Mockito.when(referenceChecker.isReferencedByPublished(QUESTION_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(ADMIN, QUESTION_ID, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除")
                .hasMessageContaining("取消发布");
    }

    @Test
    @DisplayName("未被引用的题目照常可删除")
    void deletesUnreferencedQuestion() {
        stubExistingQuestion();
        Mockito.when(referenceChecker.isReferencedByPublished(QUESTION_ID)).thenReturn(false);

        assertThatCode(() -> service.delete(ADMIN, QUESTION_ID, "127.0.0.1"))
                .doesNotThrowAnyException();
        Mockito.verify(questionMapper).deleteById(QUESTION_ID);
    }

    @Test
    @DisplayName("编辑仍做答案数量校验，单选两面答案会被拒")
    void stillValidatesAnswers() {
        stubExistingQuestion();
        Mockito.when(referenceChecker.isReferencedByPublished(QUESTION_ID)).thenReturn(true);

        QuestionRequest bad = new QuestionRequest("题干", "SINGLE", List.of(
                new QuestionRequest.OptionRequest("一", true),
                new QuestionRequest.OptionRequest("二", true)));

        assertThatThrownBy(() -> service.update(ADMIN, QUESTION_ID, bad, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private void stubExistingQuestion() {
        Question q = new Question();
        q.setId(QUESTION_ID);
        q.setCourseId(COURSE_ID);
        q.setType("SINGLE");
        q.setStem("原题干");
        q.setStatus("ACTIVE");
        Mockito.when(questionMapper.selectById(QUESTION_ID)).thenReturn(q);

        Course course = new Course();
        course.setId(COURSE_ID);
        course.setInsuranceCompanyId(1L);
        Mockito.when(courseMapper.selectById(COURSE_ID)).thenReturn(course);

        QuestionOption opt = new QuestionOption();
        opt.setId(1L);
        opt.setQuestionId(QUESTION_ID);
        opt.setLabel("A");
        opt.setContent("对");
        opt.setIsCorrect(1);
        opt.setSortNo(1);
        Mockito.when(optionMapper.selectList(Mockito.any())).thenReturn(List.of(opt));
    }
}
