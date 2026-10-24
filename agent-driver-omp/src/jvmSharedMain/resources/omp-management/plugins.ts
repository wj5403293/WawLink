import { dispatch as dispatchNative, safeError } from "./plugin-native.mjs";
import { ManagementDiagnostic } from "./diagnostics.mjs";

const operations: Record<string, true> = {snapshot:true, detail:true, raw:true, markets:true, checkUpdates:true, validate:true, install:true, uninstall:true, upgrade:true, upgradeMarket:true, enable:true, features:true, setting:true, autoUpdate:true, marketAdd:true, marketUpdate:true, marketRemove:true, doctor:true, cancel:true};
const identified: Record<string, true> = {detail:true, raw:true, validate:true, uninstall:true, upgrade:true, enable:true, features:true, setting:true};

/** Validates the JSONL boundary before calling installed-container native OMP managers. */
export async function dispatch(operation: string, argumentsValue: unknown, context: unknown): Promise<Record<string, unknown>> {
  try {
    if (!argumentsValue || typeof argumentsValue !== "object" || Array.isArray(argumentsValue)) throw Error("Plugin arguments must be an object");
    if (!context || typeof context !== "object" || !("cwd" in context) || !("agentDir" in context) || typeof context.agentDir !== "string" || (context.cwd !== null && typeof context.cwd !== "string")) throw Error("Plugin context is unavailable");
    const args: Record<string, unknown> = Object.fromEntries(Object.entries(argumentsValue));
    const op = operation.replace(/^plugins\./, "");
    if (!Object.hasOwn(operations, op)) throw Error("Unknown plugin operation");
    if (Object.hasOwn(identified, op) && (typeof args.id !== "string" || !args.id)) throw Error("Plugin identity is required");
    for (const key of ["id", "scope", "kind", "spec", "source", "name", "key", "operationId"]) if (args[key] !== undefined && typeof args[key] !== "string") throw Error(`Invalid plugin argument: ${key}`);
    for (const key of ["enabled", "confirmed", "force", "fix", "clear"]) if (args[key] !== undefined && typeof args[key] !== "boolean") throw Error(`Invalid plugin argument: ${key}`);
    if (args.scope !== undefined && args.scope !== "user" && args.scope !== "project") throw Error("Invalid plugin scope");
    if (op === "install" && (typeof args.spec !== "string" || !args.spec || !["npm", "git", "link", "market"].includes(String(args.kind)))) throw Error("Plugin install source is required");
    if (op === "features" && args.value !== null && (!Array.isArray(args.value) || !args.value.every(value => typeof value === "string"))) throw Error("Features must be null or a string array");
    if (op === "setting" && (typeof args.key !== "string" || !args.key || (args.clear !== true && !["string", "number", "boolean"].includes(typeof args.value)))) throw Error("Setting key and scalar value are required");
    if (op === "marketAdd" && (typeof args.source !== "string" || !args.source)) throw Error("Marketplace source is required");
    if (["marketRemove", "marketUpdate"].includes(op) && (typeof args.name !== "string" || !args.name)) throw Error("Marketplace name is required");
    const result: unknown = await dispatchNative(operation, args, context);
    if (!result || typeof result !== "object" || Array.isArray(result)) throw Error("Native plugin response must be an object");
    return Object.fromEntries(Object.entries(result));
  } catch (error) { throw new ManagementDiagnostic(`UNAVAILABLE: ${safeError(error)}`); }
}
