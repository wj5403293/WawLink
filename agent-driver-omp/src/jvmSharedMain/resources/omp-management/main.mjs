import * as fs from "node:fs/promises";
import * as path from "node:path";
import { createHash } from "node:crypto";
import * as readline from "node:readline";
import * as agents from "./agents.ts";
import { ManagementDiagnostic, parseDocument, validateNativeModels } from "./diagnostics.mjs";

// Installed-container modules do not exist on the JVM build host; Bun resolves them only in the container.
const { Settings } = await import("/opt/omp/src/config/settings.ts");
const registry = await import("/opt/omp/src/config/registry.ts");
const { orderedSettings } = await import("/opt/omp/src/config/all-settings.ts");
const { withFileLock } = await import("/opt/omp/node_modules/@oh-my-pi/pi-utils/src/file-lock.ts");
const send = value => process.stdout.write(JSON.stringify(value) + "\n");
// Native diagnostics may contain provider bodies. Never forward them to a UI or log.
console.log = console.info = console.warn = console.error = () => {};
const pending = new Map();
let authentication;
// Installed-container auth/SDK modules must not gate the configuration snapshot.
const auth = () => authentication ??= import("./auth-models.ts");
let hostSequence = 0;
async function host(op, args) {
  const id = `host-${++hostSequence}`;
  const deferred = Promise.withResolvers();
  pending.set(id, deferred);
  send({event:"host",id,op,args});
  return deferred.promise;
}
const ctx = {
  cwd: null, agentDir: "/root/.omp/agent", sessionId: null, send,
  settings: async () => {
    const settings = await Settings.loadReadOnly({cwd:ctx.cwd ?? "/",agentDir:ctx.agentDir});
    // No-project is a real global-only view, not whichever project happens to be under /root.
    return ctx.cwd ? settings : Settings.isolated().restoreOverlay({global:settings.getGlobalSettings(),overrides:{}});
  },
  editSettings: async (scope, changes) => {
    await validate(changes);
    const file=scope==="PROJECT"?path.join(ctx.cwd??"/",".omp/config.yml"):(await fs.stat(path.join(ctx.agentDir,"config.yml")).catch(()=>null))||!(await fs.stat(path.join(ctx.agentDir,"config.yaml")).catch(()=>null))?path.join(ctx.agentDir,"config.yml"):path.join(ctx.agentDir,"config.yaml");
    await fs.mkdir(path.dirname(file),{recursive:true});
    return withFileLock(await fs.realpath(file).catch(()=>file),()=>host("editSettings",{scope,changes}));
  },
  patchYaml: async (file,changes,expectedVersion,body) => {await fs.mkdir(path.dirname(file),{recursive:true});return withFileLock(await fs.realpath(file).catch(()=>file),()=>host("patchYaml",{path:file,changes,expectedVersion,body}));},
  atomicWrite: async (file,content,expectedVersion) => {await fs.mkdir(path.dirname(file),{recursive:true});return withFileLock(await fs.realpath(file).catch(()=>file),()=>host("atomicWrite",{path:file,content,expectedVersion}));},
  atomicDelete: async (file,expectedVersion) => withFileLock(await fs.realpath(file).catch(()=>file),()=>host("atomicDelete",{path:file,expectedVersion})),
  version: async file => {try{return createHash("sha256").update(await fs.readFile(file)).digest("hex");}catch(error){if(error?.code==="ENOENT")return "";throw error;}},
  pinAccount: (provider,accountId) => host("pinAccount",{provider,accountId}),
  accountPins: () => ctx.sessionId ? host("accountPins",{}) : {},
};
function redact(value,key="") {
  if(/(?:api.?key|access.?token|refresh.?token|password|secret|authorization|cookie|credential)/i.test(key)||/^token$/i.test(key))return "[redacted]";
  if(Array.isArray(value))return value.map(v=>redact(v));
  if(value&&typeof value==="object")return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,redact(v,k)]));
  if(typeof value==="string"&&/^https?:\/\//i.test(value)){try{const url=new URL(value);url.username="";url.password="";url.search="";url.hash="";return url.toString();}catch{return "[redacted invalid URL]";}}
  return value;
}
function set(target,dotted,value) {
  const parts=dotted.split(".");let obj=target;
  for(const part of parts.slice(0,-1)){if(["__proto__","prototype","constructor"].includes(part))throw Error("Invalid setting path");obj=obj[part]??={};}
  if(["__proto__","prototype","constructor"].includes(parts.at(-1)))throw Error("Invalid setting path");
  obj[parts.at(-1)]=value;
}
async function configurationSnapshot() {
  const settings=await ctx.settings();
  const defaults={},effective={},provenance={},categories=new Map();
  for(const setting of orderedSettings()) {
    if(setting.isCredential)continue;
    set(defaults,setting.id,setting.default??null);set(effective,setting.id,setting.get(settings)??null);provenance[setting.id]=setting.provenance(settings);
    const ui=setting.ui,tab=ui?.tab??"advanced",group=ui?.group??"Configuration";
    if(!categories.has(tab))categories.set(tab,{id:tab,name:tab,icon:"tune",groups:[]});
    const category=categories.get(tab);let section=category.groups.find(g=>g.name===group);
    if(!section){section={id:`${tab}:${group}`,name:group,icon:"tune",items:[]};category.groups.push(section);}
    const opts=Array.isArray(ui?.options)?ui.options:[];
    section.items.push({id:setting.id,title:ui?.label??setting.id,description:ui?.description??"",icon:"tune",type:setting.type.toUpperCase(),value:redact(setting.get(settings)??null),defaultValue:redact(setting.default??null),options:(setting.enumValues??opts.map(o=>o.value)).map(v=>({value:String(v),label:opts.find(o=>o.value===v)?.label??String(v)})),min:ui?.min,max:ui?.max,step:ui?.step??1});
  }
  const globalValues=redact(settings.getGlobalSettings()),projectValues=ctx.cwd?redact(settings.getProjectSettings()):{};
  for(const setting of registry.all())if(setting.isCredential)for(const object of [globalValues,projectValues]){let o=object;const parts=setting.id.split(".");for(const key of parts.slice(0,-1))o=o?.[key];if(o)delete o[parts.at(-1)];}
  const yml=path.join(ctx.agentDir,"config.yml"),yaml=path.join(ctx.agentDir,"config.yaml");
  const globalConfig=(await fs.stat(yml).catch(()=>null))||!(await fs.stat(yaml).catch(()=>null))?yml:yaml;
  const modelsYml=path.join(ctx.agentDir,"models.yml"),modelsYaml=path.join(ctx.agentDir,"models.yaml");
  const modelsConfig=(await fs.stat(modelsYml).catch(()=>null))||!(await fs.stat(modelsYaml).catch(()=>null))?modelsYml:modelsYaml;
  const files=[{id:"global",title:"Global configuration",path:globalConfig,writable:true},{id:"models",title:"Model providers",path:modelsConfig,writable:true}];
  if(ctx.cwd)files.push({id:"project",title:"Project configuration",path:path.join(ctx.cwd,".omp/config.yml"),writable:true});
  return {globalValues,projectValues,effectiveValues:redact(effective),defaultValues:redact(defaults),provenance,categories:[...categories.values()],files};
}
function assertSetting(setting,value,id) {
  try { setting.assertWritable(value); }
  catch { throw new ManagementDiagnostic(`UNAVAILABLE: ${id}: expected native ${setting.type}${setting.enumValues?.length?` (${setting.enumValues.join(", ")})`:""}`); }
}
async function validate(changes) {
  if(!changes||typeof changes!=="object"||Array.isArray(changes))throw Error("Invalid setting changes");
  const settings=Settings.isolated();
  for(const [id,value]of Object.entries(changes)) {
    let setting=registry.lookup(id);
    if(setting){if(value!==null){assertSetting(setting,value,id);setting.override(settings,value);}continue;}
    const parts=id.split(".");let found=false;
    for(let n=parts.length-1;n>0;n--){setting=registry.lookup(parts.slice(0,n).join("."));if(setting?.type!=="record")continue;if(value!==null){const candidate={};set(candidate,parts.slice(n).join("."),value);assertSetting(setting,candidate,id);}found=true;break;}
    if(!found)throw new ManagementDiagnostic(`UNAVAILABLE: ${id}: unknown native setting`);
  }
  return true;
}
async function preset(op,name) {
  const settings=await ctx.settings();
  const presets=await import("/opt/omp/src/config/model-presets.ts");
  if(!presets.isValidModelPresetName(name))throw Error("UNAVAILABLE: Invalid preset name");
  if(op==="savePreset"){const value=presets.saveModelPreset(settings.overlay(),name);await ctx.editSettings("GLOBAL",{[`modelPresets.${name}`]:value});return;}
  if(op==="deletePreset"){if(!Object.hasOwn(settings.getGlobalSettings().modelPresets??{},name))throw Error("UNAVAILABLE: Preset is not owned by global configuration");await ctx.editSettings("GLOBAL",{[`modelPresets.${name}`]:null});return;}
  const lookup=presets.getModelPreset(settings,name);
  if(lookup.kind!=="found")throw Error("UNAVAILABLE: Preset is missing or invalid");
  const value=lookup.preset,preview=await (await auth()).dispatch("previewPreset",{preset:value},ctx);
  if(preview?.error)throw Error("UNAVAILABLE: Preset default model is unavailable");
  const scope=registry.lookup("modelRoleStorage")?.get(settings)==="project"?"PROJECT":"GLOBAL";
  if(scope==="PROJECT"&&!ctx.cwd)throw Error("UNAVAILABLE: Project preset application requires a project");
  const changes={},layer=scope==="PROJECT"?settings.getProjectSettings():settings.getGlobalSettings();
  for(const role of new Set([...Object.keys(layer.modelRoles??{}),...Object.keys(value.modelRoles)]))changes[`modelRoles.${role}`]=value.modelRoles[role]??null;
  await ctx.editSettings(scope,changes);
  if(value.defaultThinkingLevel!==undefined)await ctx.editSettings("GLOBAL",{defaultThinkingLevel:value.defaultThinkingLevel});
}
async function validateModels(content, file = "models.yml") {
  return validateNativeModels(content, file);
}
async function dispatch(op,args) {
  if(op==="init"){ctx.cwd=args.workspace??null;ctx.sessionId=args.sessionId??null;return true;}
  if(op==="snapshot") {
    const globalYml=path.join(ctx.agentDir,"config.yml"),globalYaml=path.join(ctx.agentDir,"config.yaml");
    const files=[{id:"global",path:(await fs.stat(globalYml).catch(()=>null))?globalYml:globalYaml}];
    if(ctx.cwd)files.push({id:"project",path:path.join(ctx.cwd,".omp/config.yml")});
    for(const file of files){const content=await fs.readFile(file.path,"utf8").catch(error=>{if(error?.code==="ENOENT")return null;throw Error("UNAVAILABLE: Cannot read native configuration file");});if(content!==null)await dispatch("validateDocument",{id:file.id,content});}
    return {...await configurationSnapshot(),...await agents.snapshot(ctx)};
  }
  if(op==="modelsSnapshot")return (await auth()).modelsSnapshot(ctx,args.online===true);
  if(op==="modelDetails")return (await auth()).modelDetails(ctx,args.provider,args.modelId);
  if(op==="modelDefaults")return (await auth()).modelDefaults(args.provider,args.modelId,args.api);
  if(op==="fetchProviderModels")return (await auth()).dispatch(op,args,ctx);
  if(op.startsWith("plugins."))return (await import("./plugins.ts")).dispatch(op,args,ctx);
  if(op.startsWith("skills."))return (await import("./skills.ts")).dispatch(op,args,ctx);
  if(op==="validateSettings")return validate(args.changes);
  if(op==="updateSettings"){await ctx.editSettings(args.scope,args.changes);return true;}
  if(op==="writeDocument"){await ctx.atomicWrite(args.path,args.content,args.expectedVersion);return true;}
  if(op==="validateDocument") {
    if(args.id==="models")return validateModels(args.content,args.path??"models.yml");
    const parsed=parseDocument(args.content,args.path??`${args.id}.yml`);
    if(!parsed||Array.isArray(parsed)||typeof parsed!=="object")throw Error("UNAVAILABLE: Configuration must be a mapping");
    if(args.id==="global"||args.id==="project"){const changes={};for(const setting of registry.all()){let value=parsed;for(const part of setting.id.split("."))value=value?.[part];if(value!==undefined)changes[setting.id]=value;}await validate(changes);}
    return true;
  }
  if(op.endsWith("Preset"))return preset(op,args.name);
  if(["login","answerLogin","cancelLogin","logout","pinAccount","previewRole","probeModel"].includes(op))return (await auth()).dispatch(op,args,ctx);
  return agents.dispatch(op,args,ctx);
}
let chain=Promise.resolve();
const input=readline.createInterface({input:process.stdin,crlfDelay:Infinity});
input.on("line",line=>{
  let message;try{message=JSON.parse(line);}catch{return;}
  if(!message||typeof message!=="object"||typeof message.id!=="string")return;
  if(message.hostResponse){const deferred=pending.get(message.id);if(deferred){pending.delete(message.id);message.error?deferred.reject(Error(/^(UNAVAILABLE|CONFLICT): [\w .,:;/()@-]+$/.test(message.error)?message.error:"UNAVAILABLE: Host configuration operation failed")):deferred.resolve(message.result);}return;}
  if(typeof message.op!=="string"||!message.args||typeof message.args!=="object"||Array.isArray(message.args))return;
  const execute=async()=>{try{
    const result=await dispatch(message.op,message.args)??null;
    if(message.op==="modelsSnapshot") {
      const {models,...metadata}=result;
      for(let offset=0;offset<models.length;offset+=256)send({event:"models_page",id:message.id,models:models.slice(offset,offset+256)});
      send({id:message.id,result:{...metadata,modelsCount:models.length}});
    } else send({id:message.id,result});
  }catch(error){const safe=error instanceof ManagementDiagnostic?error.message:error instanceof Error&&/^(UNAVAILABLE|CONFLICT): [\w .,:;/()@-]+$/.test(error.message)?error.message:`Native ${message.op.replace(/[^a-zA-Z]/g,"")} operation failed; check the selected configuration and capability availability`;send({id:message.id,error:safe});}};
  if(["answerLogin","cancelLogin","plugins.cancel","skills.cancel"].includes(message.op))void execute();else chain=chain.then(execute);
});
input.on("close",async()=>{try{if(authentication)await (await authentication).dispatch("cancelLogin",{},ctx);}finally{process.exit(0);}});
