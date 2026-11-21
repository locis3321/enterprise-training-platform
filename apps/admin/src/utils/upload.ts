/**
 * 文件上传（独立于 axios 请求层）。
 *
 * <p>为什么不用 `utils/request.ts`：
 * - 该实例固定 `timeout: 15000`，1GB 视频必然超时；
 * - `post()` 拿不到上传进度。
 *
 * <p>因此用 XHR 单独实现：独立超时、进度回调、统一的响应解析。
 * 注意：这里不复用 axios 拦截器，避免出现两条错误提示链路。
 */
export interface UploadOptions {
  /** 上传超时（毫秒），默认 15 分钟，覆盖 1GB 视频 */
  timeout?: number
  onProgress?: (percent: number) => void
}

const DEFAULT_TIMEOUT = 15 * 60 * 1000

export function uploadFile<T>(
  url: string,
  formData: FormData,
  options: UploadOptions = {},
): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url, true)
    xhr.timeout = options.timeout ?? DEFAULT_TIMEOUT

    const token = sessionStorage.getItem('it_admin_token')
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    }

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && options.onProgress) {
        options.onProgress(Math.round((event.loaded / event.total) * 100))
      }
    }

    xhr.onload = () => {
      let body: { code?: number, message?: string, data?: T } | null = null
      try {
        body = JSON.parse(xhr.responseText)
      }
      catch {
        body = null
      }
      if (xhr.status >= 200 && xhr.status < 300 && body?.code === 200) {
        resolve(body.data as T)
        return
      }
      reject(new Error(body?.message || `上传失败（HTTP ${xhr.status}）`))
    }

    xhr.onerror = () => reject(new Error('网络异常，上传失败'))
    xhr.ontimeout = () => reject(new Error('上传超时，请检查网络或文件大小'))
    xhr.onabort = () => reject(new Error('上传已取消'))

    xhr.send(formData)
  })
}
