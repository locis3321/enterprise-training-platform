package com.insurance.training.content.storage;

import com.insurance.training.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件真实类型检测。
 *
 * <p>核心是「不信任扩展名与客户端 Content-Type」：MP4 改名为 .pdf 也必须被识别为视频，
 * 普通 zip 改名为 .pptx 必须被拒绝。
 */
class FileTypeDetectorTest {

    private final FileTypeDetector detector = new FileTypeDetector();

    private byte[] bytes(int length, byte[] prefix) {
        byte[] data = new byte[Math.max(length, 16)];
        System.arraycopy(prefix, 0, data, 0, prefix.length);
        return data;
    }

    @Test
    @DisplayName("PDF 按 %PDF- 识别")
    void detectsPdf() {
        byte[] data = bytes(16, "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        var r = detector.detect(new ByteArrayInputStream(data), "pdf");
        assertThat(r.kind()).isEqualTo(ResourceKind.PDF);
        assertThat(r.contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("PNG / JPEG 识别")
    void detectsImages() {
        var png = detector.detect(new ByteArrayInputStream(
                bytes(16, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})), "png");
        assertThat(png.kind()).isEqualTo(ResourceKind.IMAGE);
        assertThat(png.ext()).isEqualTo("png");

        var jpg = detector.detect(new ByteArrayInputStream(
                bytes(16, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})), "jpg");
        assertThat(jpg.kind()).isEqualTo(ResourceKind.IMAGE);
        assertThat(jpg.ext()).isEqualTo("jpg");
    }

    @Test
    @DisplayName("MP4 在偏移 4 处识别 ftyp")
    void detectsMp4() {
        byte[] data = new byte[32];
        data[0] = 0; data[1] = 0; data[2] = 0; data[3] = 0x18;
        data[4] = 'f'; data[5] = 't'; data[6] = 'y'; data[7] = 'p';
        var r = detector.detect(new ByteArrayInputStream(data), "mp4");
        assertThat(r.kind()).isEqualTo(ResourceKind.VIDEO);
        assertThat(r.contentType()).isEqualTo("video/mp4");
    }

    @Test
    @DisplayName("WebM 按 1A45DFA3 识别")
    void detectsWebm() {
        var r = detector.detect(new ByteArrayInputStream(
                bytes(16, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})), "webm");
        assertThat(r.kind()).isEqualTo(ResourceKind.VIDEO);
        assertThat(r.ext()).isEqualTo("webm");
    }

    @Test
    @DisplayName("普通 zip 改名 pptx 必须被拒绝（缺 Office 结构）")
    void plainZipIsNotPptx() throws Exception {
        byte[] zip = zipOf("readme.txt", "hello");

        assertThatThrownBy(() -> detector.detect(new ByteArrayInputStream(zip), "pptx"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PPTX");
    }

    @Test
    @DisplayName("含 [Content_Types].xml 与 ppt/ 的 zip 识别为 PPTX")
    void realPptxAccepted() throws Exception {
        byte[] zip = zipOf("[Content_Types].xml", "<Types/>", "ppt/presentation.xml", "<p/>");

        var r = detector.detect(new ByteArrayInputStream(zip), "pptx");
        assertThat(r.kind()).isEqualTo(ResourceKind.PPT);
        assertThat(r.ext()).isEqualTo("pptx");
    }

    @Test
    @DisplayName("未知类型直接拒绝")
    void unknownRejected() {
        byte[] data = new byte[]{(byte) 0x4D, (byte) 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00,
                0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> detector.detect(new ByteArrayInputStream(data), "exe"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    @DisplayName("内容过短拒绝，不猜测类型")
    void tooShortRejected() {
        assertThatThrownBy(() -> detector.detect(new ByteArrayInputStream(new byte[]{1, 2, 3}), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过短");
    }

    private byte[] zipOf(String... namesAndContents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
