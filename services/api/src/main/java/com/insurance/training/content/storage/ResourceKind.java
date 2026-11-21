package com.insurance.training.content.storage;

/**
 * 资源类型。
 *
 * <p>同时决定三件事：签名 URL 有效期、上传大小上限、允许的扩展名与真实文件类型。
 * 放在存储包而不是实体包，因为存储层与上传校验都依赖它，实体层再引用即可。
 */
public enum ResourceKind {
    VIDEO,
    PDF,
    PPT,
    IMAGE
}
