package com.insurance.training.system.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.storage.MinioStorageService;
import com.insurance.training.content.storage.ResourceKind;
import com.insurance.training.system.config.SystemConfigKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 隐私协议：魔数校验、大小上限、未发布态、写库失败补偿。 */
class PrivacyPolicyServiceTest {

    private final MinioStorageService storage = mock(MinioStorageService.class);
    private final SystemConfigService config = mock(SystemConfigService.class);
    private final AuditService auditService = mock(AuditService.class);

    private final PrivacyPolicyService service = new PrivacyPolicyService(storage, config, auditService);

    private final CurrentUser admin = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
            DataScopeType.ALL, null, null, "平台");

    private static byte[] pdfBytes() {
        return "%PDF-1.7\nfake pdf body".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("未上传时 available=false，不生成签名地址")
    void notPublishedYet() {
        when(config.getString(SystemConfigKey.LEGAL_PRIVACY_POLICY)).thenReturn(null);

        var vo = service.current();

        assertThat(vo.available()).isFalse();
        assertThat(vo.url()).isNull();
        verify(storage, never()).presignedGetUrl(anyString(), any(), any());
    }

    @Test
    @DisplayName("已上传时每次请求重新签名（实时地址）")
    void signsOnEveryRequest() {
        when(config.getString(SystemConfigKey.LEGAL_PRIVACY_POLICY)).thenReturn("legal/privacy-policy/x.pdf");
        when(config.overriddenAt(SystemConfigKey.LEGAL_PRIVACY_POLICY))
                .thenReturn(LocalDateTime.of(2026, 11, 12, 10, 0));
        when(storage.presignedGetUrl(eq("legal/privacy-policy/x.pdf"), eq(ResourceKind.PDF), isNull()))
                .thenReturn("https://minio.example/x.pdf?sig=1");

        var first = service.current();
        var second = service.current();

        assertThat(first.available()).isTrue();
        assertThat(first.filename()).isEqualTo("隐私协议.pdf");
        assertThat(first.url()).contains("sig=1");
        assertThat(second.url()).isEqualTo(first.url());
        // 两次请求各签一次，不复用缓存地址
        verify(storage, org.mockito.Mockito.times(2))
                .presignedGetUrl(eq("legal/privacy-policy/x.pdf"), eq(ResourceKind.PDF), isNull());
    }

    @Test
    @DisplayName("非 PDF 文件被拒（只看魔数，不看扩展名）")
    void rejectsNonPdf() {
        var fake = new MockMultipartFile("file", "policy.pdf", "application/pdf",
                "not a pdf at all".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload(admin, fake, "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只支持 PDF");
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("空文件被拒")
    void rejectsEmpty() {
        var empty = new MockMultipartFile("file", "p.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.upload(admin, empty, "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请选择");
    }

    @Test
    @DisplayName("超过 10MB 被拒")
    void rejectsTooLarge() {
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(pdfBytes(), 0, big, 0, 5);
        var file = new MockMultipartFile("file", "p.pdf", "application/pdf", big);

        assertThatThrownBy(() -> service.upload(admin, file, "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("10MB");
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("上传成功后写配置并审计")
    void uploadWritesConfigAndAudits() {
        when(config.getString(SystemConfigKey.LEGAL_PRIVACY_POLICY)).thenReturn(null);
        var file = new MockMultipartFile("file", "p.pdf", "application/pdf", pdfBytes());

        service.upload(admin, file, "1.1.1.1");

        verify(storage).put(org.mockito.ArgumentMatchers.startsWith("legal/privacy-policy/"),
                any(InputStream.class), anyLong(), eq("application/pdf"));
        verify(config).setManagedValue(eq(admin), eq(SystemConfigKey.LEGAL_PRIVACY_POLICY),
                org.mockito.ArgumentMatchers.startsWith("legal/privacy-policy/"), eq("1.1.1.1"));
        verify(auditService).record(eq(admin), eq("PRIVACY_POLICY_UPLOADED"), eq("sys_config"),
                isNull(), anyString(), eq("1.1.1.1"));
    }

    @Test
    @DisplayName("写配置失败时补偿删除对象，不留孤儿")
    void compensatesOnConfigFailure() {
        var file = new MockMultipartFile("file", "p.pdf", "application/pdf", pdfBytes());
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(config).setManagedValue(any(), any(), anyString(), anyString());

        assertThatThrownBy(() -> service.upload(admin, file, "1.1.1.1"))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).delete(org.mockito.ArgumentMatchers.startsWith("legal/privacy-policy/"));
        verify(auditService, never()).record(any(), anyString(), anyString(), any(), anyString(), anyString());
    }
}
