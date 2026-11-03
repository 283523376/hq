import { useMessage } from '/@/hooks/web/useMessage';

/**
 * 导出响应落盘。
 *
 * **成功与否只能看 `Content-Disposition`**：后端成功导出 JSON 包时 Content-Type 也是
 * `application/json`，与失败时的 `Result.error` 错误体一样；区别只在成功必带
 * `Content-Disposition`（失败体没有）。所以导出请求必须带
 * `isTransformResponse:false, isReturnNativeResponse:true`，把 headers 一并取回。
 */
export function useExportDownload() {
  const { createMessage } = useMessage();

  async function handleExportResponse(response: any, fileName: string) {
    if (!response) {
      createMessage.error('导出失败：无响应');
      return;
    }
    const headers = response.headers || {};
    const disposition = headers['content-disposition'] || headers['Content-Disposition'];
    const data = response.data;

    // 成功：有 Content-Disposition，直接落盘
    if (disposition) {
      if (!(data instanceof Blob)) {
        createMessage.error('导出失败：响应格式异常');
        return;
      }
      const url = window.URL.createObjectURL(data);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      createMessage.success(`已导出：${fileName}`);
      return;
    }

    // 失败：HTTP 200 + Result.error JSON（无 Content-Disposition）
    let message = '导出失败';
    if (data instanceof Blob) {
      const text = await data.text();
      try {
        const body = JSON.parse(text);
        message = body.message || body.msg || message;
      } catch {
        if (text) {
          message = text;
        }
      }
    } else if (data && typeof data === 'object') {
      message = data.message || data.msg || message;
    }
    createMessage.warning(message);
  }

  return { handleExportResponse };
}
