package com.insurance.training.content.storage;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文件真实类型检测。
 *
 * <p>只依据文件字节签名（magic number），**不使用** {@code Files.probeContentType}：
 * 后者依赖扩展名与操作系统探测器，可被改名绕过。
 *
 * <p>PPTX 额外要求内部结构：ZIP 容器里必须同时存在 {@code [Content_Types].xml} 与 {@code ppt/}
 * 目录，避免把普通压缩包改名成 .pptx 传进来。
 */
@Component
public class FileTypeDetector {

    /** 检测结果：类型 + 规范化扩展名 + MIME。 */
    public record Detected(ResourceKind kind, String ext, String contentType) {
    }

    public Detected detect(InputStream in, String declaredExtension) {
        try {
            byte[] head = in.readNBytes(32);
            if (head.length < 12) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文件内容过短，无法识别类型");
            }
            Detected byMagic = matchMagic(head);
            if (byMagic == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不支持的文件类型（仅支持 MP4/WebM/PDF/PPTX/PNG/JPEG/WebP）");
            }
            if (byMagic.kind() == ResourceKind.PPT && !isRealPptx(in, head)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文件不是有效的 PPTX（缺少 Office 文档结构）");
            }
            return byMagic;
        }
        catch (BizException ex) {
            throw ex;
        }
        catch (IOException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件读取失败");
        }
    }

    private Detected matchMagic(byte[] head) {
        // PDF: %PDF-
        if (startsWith(head, new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D})) {
            return new Detected(ResourceKind.PDF, "pdf", "application/pdf");
        }
        // PNG: 89 50 4E 47
        if (startsWith(head, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47})) {
            return new Detected(ResourceKind.IMAGE, "png", "image/png");
        }
        // JPEG: FF D8 FF
        if (startsWith(head, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return new Detected(ResourceKind.IMAGE, "jpg", "image/jpeg");
        }
        // WebP: RIFF....WEBP
        if (startsWith(head, "RIFF".getBytes()) && offsetEquals(head, 8, "WEBP".getBytes())) {
            return new Detected(ResourceKind.IMAGE, "webp", "image/webp");
        }
        // WebM: 1A 45 DF A3
        if (startsWith(head, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})) {
            return new Detected(ResourceKind.VIDEO, "webm", "video/webm");
        }
        // MP4: 偏移 4~8 为 "ftyp"
        if (head.length >= 12 && offsetEquals(head, 4, "ftyp".getBytes())) {
            return new Detected(ResourceKind.VIDEO, "mp4", "video/mp4");
        }
        // PPTX: ZIP 头 50 4B 03 04（需进一步校验内部结构）
        if (startsWith(head, new byte[]{0x50, 0x4B, 0x03, 0x04})) {
            return new Detected(ResourceKind.PPT, "pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        }
        return null;
    }

    /** 校验 ZIP 内是否含 [Content_Types].xml 与 ppt/ 条目。 */
    private boolean isRealPptx(InputStream in, byte[] head) {
        try (ZipInputStream zip = new ZipInputStream(new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(head), in))) {
            boolean hasContentTypes = false;
            boolean hasPpt = false;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("[Content_Types].xml".equals(name)) {
                    hasContentTypes = true;
                }
                if (name.startsWith("ppt/")) {
                    hasPpt = true;
                }
                if (hasContentTypes && hasPpt) {
                    return true;
                }
            }
            return false;
        }
        catch (IOException ex) {
            return false;
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean offsetEquals(byte[] data, int offset, byte[] expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
