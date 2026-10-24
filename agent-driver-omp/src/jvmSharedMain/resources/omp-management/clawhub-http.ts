import { ManagementDiagnostic } from "./diagnostics.mjs";

export const API = "https://clawhub.ai/api/v1";
export const PREVIEW_BYTES = 200 * 1024;
export const FILE_BYTES = 10 * 1024 * 1024;
export const BUNDLE_BYTES = 50 * 1024 * 1024;
const JSON_BYTES = 4 * 1024 * 1024;
const CACHE_BYTES = 8 * 1024 * 1024;
const cache = new Map<string, { expires: number; size: number; value: Record<string, unknown> }>();
let cacheSize = 0;
const cooldown = new Map<string, number>();
export const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === "object" && !Array.isArray(value);
export function fail(message: string): never { throw new ManagementDiagnostic(message); }
export class PublicHttpError extends ManagementDiagnostic {
  constructor(public readonly status: number, message: string) { super(message); }
}
export function text(value: unknown, maximum = 20000): string | null {
  return typeof value === "string" ? value.slice(0, maximum) : null;
}
export function identity(value: unknown): string {
  if (typeof value !== "string" || !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/.test(value) || value === "." || value === "..") fail("ClawHub 发布者或技能身份无效，请重新选择");
  return value;
}
export function exactVersion(value: unknown): string {
  if (typeof value !== "string" || !value || value.length > 200 || /[\u0000-\u0020\u007f/\\]/.test(value) || value === "latest") fail("请选择明确的 ClawHub 版本后重试");
  return value;
}
export function cursor(value: unknown): string | null {
  if (value === undefined || value === null || value === "") return null;
  if (typeof value !== "string" || value.length > 16384 || /[\u0000-\u001f\u007f]/.test(value)) fail("ClawHub 分页游标无效，请重新加载列表");
  return value;
}
export function apiUrl(route: string, parameters: Record<string, string | null | undefined> = {}): string {
  const url = new URL(`${API}${route}`);
  for (const [key, value] of Object.entries(parameters)) if (value !== null && value !== undefined) url.searchParams.set(key, value);
  return url.href;
}
export function skillUrl(owner: string, slug: string, route = "", parameters: Record<string, string | null | undefined> = {}): string {
  return apiUrl(`/skills/${encodeURIComponent(slug)}${route}`, { ...parameters, ownerHandle: owner });
}
export function canonical(owner: string, slug: string): string { return `https://clawhub.ai/${encodeURIComponent(owner)}/skills/${encodeURIComponent(slug)}`; }
function allowed(url: URL): boolean {
  return url.protocol === "https:" && !url.username && !url.password && !url.port &&
    ["clawhub.ai", "www.clawhub.ai", "api.github.com", "raw.githubusercontent.com"].includes(url.hostname);
}
function retryDelay(headers: Headers): number {
  const retry = headers.get("retry-after");
  if (retry && /^\d+(?:\.\d+)?$/.test(retry)) return Math.max(1000, Math.ceil(Number(retry) * 1000));
  if (retry && Number.isFinite(Date.parse(retry))) return Math.max(1000, Date.parse(retry) - Date.now());
  const reset = Number(headers.get("ratelimit-reset"));
  if (reset > 0 && Number.isFinite(reset)) return Math.ceil(reset * 1000);
  const absolute = Number(headers.get("x-ratelimit-reset"));
  return absolute > 0 && Number.isFinite(absolute) ? Math.max(1000, absolute * 1000 - Date.now()) : 60000;
}
function httpFailure(status: number, delay: number): PublicHttpError {
  const reason = status === 429 ? `ClawHub 或 GitHub 请求限流（429），请至少等待 ${Math.ceil(delay / 1000)} 秒后重试；未自动重试`
    : status === 403 ? "公开来源拒绝访问（403）；内容可能受审核限制或 GitHub 匿名配额不足，不会绕过限制"
    : status === 401 ? "该资源需要认证（401）；这里只读取公开技能，不会提交账号凭据"
    : status === 404 ? "公开技能、精确版本或资源不存在（404），请重新选择"
    : status === 410 ? "该公开技能或版本已移除（410），不能安装"
    : status === 423 ? "该来源正在审核或等待上游检查（423），请稍后重新加载"
    : status === 409 ? "ClawHub 来源身份或版本存在冲突（409），请重新选择发布者与精确版本"
    : "公开技能服务请求失败，请稍后重试；未信任远端错误内容";
  return new PublicHttpError(status, reason);
}

/** Each RPC has one deadline, including every body read; no automatic retries. */
export class ClawHubHttp {
  readonly deadline = Date.now() + 145000;
  private requests = 0;
  check(): void { if (Date.now() >= this.deadline) fail("ClawHub 操作超过截止时间，请稍后重试；未完成安装不会保留"); }
  async bytes(urlString: string, maximum: number): Promise<{ bytes: Buffer; contentType: string }> {
    this.check();
    if (++this.requests > 8500) fail("ClawHub 操作资源请求数超过安全上限");
    let url = new URL(urlString);
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), Math.min(25000, this.deadline - Date.now()));
    try {
      for (let redirects = 0; redirects <= 3; redirects++) {
        if (!allowed(url)) fail("公开来源重定向到未允许的地址，已停止读取");
        const wait = (cooldown.get(url.hostname) ?? 0) - Date.now();
        if (wait > 0) throw httpFailure(429, wait);
        const response = await fetch(url, { method: "GET", redirect: "manual", signal: abort.signal,
          headers: { Accept: "*/*", "User-Agent": "Wawlink-ClawHub/1" }, credentials: "omit" });
        if ([301, 302, 303, 307, 308].includes(response.status)) {
          const location = response.headers.get("location");
          await response.body?.cancel();
          if (!location || redirects === 3) fail("公开来源重定向次数超过上限，已停止读取");
          url = new URL(location, url); continue;
        }
        if (!response.ok) {
          const delay = retryDelay(response.headers);
          if (response.status === 429) cooldown.set(url.hostname, Date.now() + delay);
          await response.body?.cancel();
          throw httpFailure(response.status, delay);
        }
        const length = Number(response.headers.get("content-length"));
        if (length > maximum) { await response.body?.cancel(); fail("公开资源响应超过安全大小上限，已停止读取"); }
        const reader = response.body?.getReader();
        if (!reader) fail("公开资源响应为空，请稍后重试");
        const chunks: Uint8Array[] = [];
        let size = 0;
        try {
          for (;;) {
            this.check();
            const { value, done } = await reader.read();
            if (done) break;
            size += value.byteLength;
            if (size > maximum) { await reader.cancel(); fail("公开资源响应超过安全大小上限，已停止读取"); }
            chunks.push(value);
          }
        } finally { reader.releaseLock(); }
        return { bytes: Buffer.concat(chunks, size), contentType: response.headers.get("content-type") ?? "" };
      }
      fail("公开来源重定向无效，已停止读取");
    } catch (error) {
      if (error instanceof ManagementDiagnostic) throw error;
      if (abort.signal.aborted) fail("ClawHub 网络读取超时，请检查网络后重试；未完成安装不会保留");
      fail("无法连接公开技能来源，请检查容器网络后重试；不会使用未经验证的回退内容");
    } finally { clearTimeout(timer); }
  }
  async json(url: string, fresh = false): Promise<Record<string, unknown>> {
    this.check();
    const cached = cache.get(url);
    if (!fresh && cached && cached.expires > Date.now()) {
      cache.delete(url); cache.set(url, cached); return cached.value;
    }
    if (cached) { cache.delete(url); cacheSize -= cached.size; }
    const { bytes } = await this.bytes(url, JSON_BYTES);
    let value: unknown;
    try { value = JSON.parse(bytes.toString("utf8")); } catch { fail("公开技能服务返回了无效的数据格式，请稍后重试"); }
    if (!record(value)) fail("公开技能服务返回了无效的数据结构，请稍后重试");
    if (!fresh && bytes.length <= CACHE_BYTES) {
      while (cache.size >= 32 || cacheSize + bytes.length > CACHE_BYTES) {
        const oldest = cache.keys().next().value;
        if (oldest === undefined) break;
        cacheSize -= cache.get(oldest)!.size; cache.delete(oldest);
      }
      cache.set(url, { expires: Date.now() + 45000, size: bytes.length, value }); cacheSize += bytes.length;
    }
    return value;
  }
}
