// API checks are restricted to the isolated local QA server.
// The destination cannot be overridden through an environment variable.
export const qaBase = 'http://127.0.0.1:17891/api';
export const qaPassword = process.env.ZIJI_QA_PASSWORD;

if (!qaPassword) {
  throw new Error('请先设置 ZIJI_QA_PASSWORD 为独立测试库的解锁口令。检查已停止，尚未发送任何请求；请勿使用正式空间的口令。');
}
