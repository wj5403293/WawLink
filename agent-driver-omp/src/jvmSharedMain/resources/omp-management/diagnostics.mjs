// Diagnostics cross the JSONL trust boundary only after they have been built here.
// Never forward parser source excerpts, offending schema values or provider response bodies.
export class ManagementDiagnostic extends Error {}

const secretKey = /api.?key|authorization|cookie|secret|password|credential|headers|bearer|(?:^|[_-])(?:access|refresh|session|id)?[_-]?token$/i;
function secretsIn(value, sensitive = false, result = new Set()) {
  if (typeof value === "string") {
    if (sensitive && value.length > 0) result.add(value);
  } else if (Array.isArray(value)) {
    for (const child of value) secretsIn(child, sensitive, result);
  } else if (value && typeof value === "object") {
    for (const [key, child] of Object.entries(value)) secretsIn(child, sensitive || secretKey.test(key), result);
  }
  return result;
}
export function safeReason(value, document) {
  let reason = String(value ?? "Native validation failed");
  for (const secret of [...secretsIn(document)].sort((a, b) => b.length - a.length)) reason = reason.split(secret).join("[redacted]");
  return reason
    .replace(/[a-z][a-z0-9+.-]*:\/\/[^\s"'<>]+/gi, raw => {
      try { const url = new URL(raw); url.username = ""; url.password = ""; url.search = ""; url.hash = ""; return url.toString(); }
      catch { return "[redacted URL]"; }
    })
    .replace(/\bBearer\s+[^\s,;]+/gi, "Bearer [redacted]")
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?\b/g, "[redacted]")
    .replace(/[\u0000-\u001f\u007f]/g, " ").slice(0, 1800);
}
function syntaxDiagnostic(error, file) {
  // Bun may append the entire line of YAML to its exception. Only retain the
  // parser's reason, before its source location / excerpt.
  const message = String(error?.message ?? "Invalid YAML syntax");
  const location = message.match(/(?:line\s+(\d+)(?:[, :]\s*(?:column|col)\s+(\d+))?|at\s+(\d+):(\d+))/i);
  const position = location ? `, line ${location[1] ?? location[3]}${location[2] ?? location[4] ? `, column ${location[2] ?? location[4]}` : ""}` : "";
  // Classify parser reasons rather than forwarding arbitrary text: even an
  // unquoted "unexpected token" suffix can contain the operator's API key.
  const reason = /duplicate/i.test(message) ? "duplicate mapping key"
    : /unterminated|unexpected end/i.test(message) ? "unterminated value or unexpected end of document"
    : /indent/i.test(message) ? "invalid indentation"
    : /escape/i.test(message) ? "invalid scalar escape sequence"
    : /mapping|colon/i.test(message) ? "invalid mapping entry"
    : /sequence|flow/i.test(message) ? "invalid sequence or flow collection"
    : /unexpected|token/i.test(message) ? "unexpected YAML token"
    : "native parser rejected document";
  return new ManagementDiagnostic(`UNAVAILABLE: ${file}${position}: YAML syntax: ${safeReason(reason)}`);
}
export function parseDocument(content, file) {
  try { return content.trim() ? Bun.YAML.parse(content) : {}; }
  catch (error) { throw syntaxDiagnostic(error, file); }
}
export async function validateNativeModels(content, file = "models.yml") {
  // Native platform modules exist only in the installed container, not the JVM build host.
  const { getModelsConfigSchema } = await import("/opt/omp/src/config/models-config-schema-bundle.ts");
  const { validateProviderConfiguration } = await import("/opt/omp/src/config/models-config.ts");
  const { OmpErrors } = await import("/opt/omp/node_modules/@oh-my-pi/omptype/src/index.ts");
  const document = parseDocument(content, file);
  const checked = getModelsConfigSchema()(document);
  if (checked instanceof OmpErrors) {
    const issues = [];
    for (const issue of checked) {
      if (issues.length === 8) break;
      const field = issue.path.length ? issue.path.map(String).join(".") : "root";
      // expected is schema-authored; problem/message can echo the secret data.
      issues.push(`${safeReason(field, document)}: expected ${safeReason(issue.expected, document)}`);
    }
    throw new ManagementDiagnostic(`UNAVAILABLE: ${file}: ${issues.join("; ")}`);
  }
  for (const [name, provider] of Object.entries(checked.providers ?? {})) {
    try { validateProviderConfiguration(name, { ...provider, models: provider.models ?? [] }, "models-config"); }
    catch (error) { throw new ManagementDiagnostic(`UNAVAILABLE: ${file}: providers.${safeReason(name, document)}: ${safeReason(error?.message, document)}`); }
  }
  return true;
}
