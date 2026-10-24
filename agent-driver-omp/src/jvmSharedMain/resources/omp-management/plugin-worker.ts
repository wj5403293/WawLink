import { executeNative, nativeError } from "./plugin-native.mjs";

const write = (value: unknown) => process.stdout.write(JSON.stringify(value));
console.log = console.info = console.warn = console.error = () => {};
let context: unknown;
try {
  const value: unknown = JSON.parse(await Bun.stdin.text());
  if (!value || typeof value !== "object" || !("op" in value) || typeof value.op !== "string" || !("args" in value) || !value.args || typeof value.args !== "object" || !("context" in value) || !value.context || typeof value.context !== "object") throw Error("Invalid plugin worker request");
  context = value.context;
  // Worker operations use only native managers, never a model session or authentication SDK.
  write({result:await executeNative(value.op,value.args,value.context)});
} catch (error) { write({error:await nativeError(error,context)}); }
