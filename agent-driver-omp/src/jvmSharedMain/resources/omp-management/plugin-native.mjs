import { withFileLock } from "/opt/omp/node_modules/@oh-my-pi/pi-utils/src/file-lock.ts";
import { lookup } from "/opt/omp/src/config/registry.ts";
import { runBunCli } from "/opt/omp/src/extensibility/plugins/bun-cli.ts";
import { safeReason } from "./diagnostics.mjs";
import * as fs from "node:fs/promises";
import * as path from "node:path";
import { PluginManager, validateSetting } from "/opt/omp/src/extensibility/plugins/manager.ts";
import { MarketplaceManager } from "/opt/omp/src/extensibility/plugins/marketplace/manager.ts";
import * as registry from "/opt/omp/src/extensibility/plugins/marketplace/registry.ts";
import { resolveOrDefaultProjectRegistryPath } from "/opt/omp/src/discovery/helpers.ts";
import { parseGitUrl } from "/opt/omp/src/extensibility/plugins/git-url.ts";
import { parsePluginId } from "/opt/omp/src/extensibility/plugins/marketplace/types.ts";
import { writePluginRuntimeConfig } from "/opt/omp/src/extensibility/plugins/runtime-config.ts";
import { getPluginsDir, getPluginsLockfile, getPluginsPackageJson, getProjectPluginOverridesPath } from "/opt/omp/node_modules/@oh-my-pi/pi-utils/src/dirs.ts";

const active = new Map();
const stopping = new Map();
function stopChildren() {
 for (const child of active.values()) { try { process.kill(-child.pid, "SIGKILL"); } catch {} }
}
process.once("exit", stopChildren);
for (const [signal, code] of [["SIGTERM",143],["SIGINT",130],["SIGHUP",129]]) {
 process.once(signal, () => { stopChildren(); process.exit(code); });
}
const object = (v) => v && typeof v === "object" && !Array.isArray(v) ? v : {};
export function safe(value,key="") {
  if (/(secret|password|token|credential|authorization|cookie|api.?key)/i.test(key)) return "••••••••";
  if (Array.isArray(value)) return value.map(v=>safe(v));
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,safe(v,k)]));
  if (typeof value === "string") return value.replace(/(https?:\/\/)[^\s/@]+(?::[^\s/@]*)?@/gi,"$1[redacted]@").replace(/([?&](?:token|key|secret|password)=)[^&\s]+/gi,"$1[redacted]");
  return value;
}
export function safeError(error) { return safeReason(safe(String(error?.message ?? error))).slice(0,1800); }
export async function nativeError(error,ctx) {
 const values=[];
 const collect=value=>{if(typeof value==="string"&&value.length>=4)values.push(value);else if(value&&typeof value==="object")for(const child of Object.values(value))collect(child);};
 for(const file of [getPluginsLockfile(),ctx?.cwd?getProjectPluginOverridesPath(ctx.cwd):null].filter(Boolean)){
  try{collect(JSON.parse(await fs.readFile(file,"utf8")).settings);}catch{}
 }
 return safeReason(safeError(error),{secret:values});
}
function displaySchema(schema) {
 return Object.fromEntries(Object.entries(object(schema)).map(([key,value])=>{const field=object(value);return [key,{...field,description:safe(field.description),secret:field.secret===true,...(Object.hasOwn(field,"default")?{default:field.secret?"••••••••":safe(field.default,key)}:{})}];}));
}
function displayManifest(manifest) {const data=object(manifest);return {...safe(data),settings:displaySchema(data.settings)};}
async function json(file,fallback={}) {
  try {const value=JSON.parse(await fs.readFile(file,"utf8"));if(!value||typeof value!=="object"||Array.isArray(value))throw Error("Expected JSON object");return value;}
  catch(error){if(error.code==="ENOENT")return fallback;throw Error(`Cannot read plugin JSON file ${file}: invalid JSON or unavailable file`);}
}
async function managers(ctx) {
  return { native:new PluginManager(ctx.cwd??"/"), market:new MarketplaceManager({marketplacesRegistryPath:registry.getMarketplacesRegistryPath(),installedRegistryPath:registry.getInstalledPluginsRegistryPath(),projectInstalledRegistryPath:ctx.cwd?await resolveOrDefaultProjectRegistryPath(ctx.cwd):undefined,marketplacesCacheDir:registry.getMarketplacesCacheDir(),pluginsCacheDir:registry.getPluginsCacheDir()}) };
}
function scope(ctx,args) {const value=ctx.cwd?"project":"user";if(args.scope&&args.scope!==value)throw Error("Operation scope does not match the selected workspace");return value;}
function confirmed(args) {if(args.confirmed!==true)throw Error("Explicit risk confirmation is required");}
async function paths(ctx) {return {root:getPluginsDir(),package:getPluginsPackageJson(),dependencyLock:path.join(getPluginsDir(),"bun.lock"),runtime:getPluginsLockfile(),overrides:ctx.cwd?getProjectPluginOverridesPath(ctx.cwd):null,marketplaces:registry.getMarketplacesRegistryPath(),userRegistry:registry.getInstalledPluginsRegistryPath(),projectRegistry:ctx.cwd?await resolveOrDefaultProjectRegistryPath(ctx.cwd):null,marketplacesCache:registry.getMarketplacesCacheDir(),cache:registry.getPluginsCacheDir()};}
async function resources(root) {
 const names=async(dir)=>{try{return (await fs.readdir(path.join(root,dir),{withFileTypes:true})).filter(e=>!e.name.startsWith(".")).map(e=>e.name.replace(/\.md$/,""));}catch(error){if(error.code==="ENOENT")return [];throw error;}};
 return {skills:await names("skills"),agents:await names("agents"),mcp:(await fs.stat(path.join(root,".mcp.json")).catch(()=>null))?[".mcp.json"]:[]};
}
async function snapshot(ctx) {
 const {native,market}=await managers(ctx),p=await paths(ctx);
 // Fail closed on malformed registration/state instead of presenting an empty installation.
 const runtime=await json(p.runtime,{plugins:{},settings:{}}),packageData=await json(getPluginsPackageJson()),deps=object(packageData.dependencies),overrides=p.overrides?await json(p.overrides):{};
 for(const [label,value] of [["runtime plugins",runtime.plugins],["runtime settings",runtime.settings],["package dependencies",packageData.dependencies]])if(value!==undefined&&(!value||typeof value!=="object"||Array.isArray(value)))throw Error(`Invalid ${label}; repair the native JSON file before editing`);
 const marketRegistry=await json(p.marketplaces,{version:1,marketplaces:[]});if(marketRegistry.version!==1||!Array.isArray(marketRegistry.marketplaces))throw Error("Invalid marketplaces registry; existing state is not being replaced with an empty list");
 for(const file of [p.userRegistry,p.projectRegistry].filter(Boolean)){const installed=await json(file,{version:2,plugins:{}});if(typeof installed.version!=="number"||!installed.plugins||typeof installed.plugins!=="object"||Array.isArray(installed.plugins))throw Error("Invalid installed plugin registry; repair it before editing");}
 const plugins=[];
 for(const item of await native.list()) {
   const spec=typeof deps[item.name]==="string"?deps[item.name]:"";
   const linked=await fs.lstat(path.join(getPluginsDir(),"node_modules",item.name)).then(s=>s.isSymbolicLink()).catch(()=>false);
   const kind=spec.startsWith("link:")||spec.startsWith("file:")||linked&&!parseGitUrl(spec)?"link":parseGitUrl(spec)?"git":"npm";
   const state=runtime.plugins?.[item.name],actualPath=kind==="link"?await fs.realpath(item.path).catch(()=>item.path):item.path;
   plugins.push({...item,path:actualPath,manifest:displayManifest(item.manifest),id:item.name,kind,scope:"user",spec:safe(spec||(kind==="link"?actualPath:"")),description:item.manifest?.description??"",enabled:state?.enabled!==false&&!overrides.disabled?.includes(item.name),enabledFeatures:overrides.features?.[item.name]??state?.enabledFeatures??null,userEnabled:state?.enabled??true,projectDisabled:overrides.disabled?.includes(item.name)??false,userFeatures:state?.enabledFeatures??null,projectFeatures:overrides.features?.[item.name],resources:await resources(item.path),loaded:{available:false,reason:"管理进程未加载可执行扩展；清单不是加载证明"},session:{available:false,reason:ctx.sessionId?"当前 RPC 未提供按插件归属的运行能力快照":"未绑定会话"}});
 }
 // Native list omits broken links, retain their real runtime registration.
 const seen=new Set(plugins.map(p=>p.id));
 const summaries=await market.listInstalledPlugins();
 const marketNames=new Set();
 for(const summary of summaries)for(const entry of summary.entries??[]) {
   const pkg=await json(path.join(entry.installPath,"package.json")),runtimeName=pkg.name??parsePluginId(summary.id)?.name??summary.id;marketNames.add(runtimeName);
   if(summary.scope!==scope(ctx,{}))continue;
   const standard=await json(path.join(entry.installPath,"plugin.json")),compatible=await json(path.join(entry.installPath,".claude-plugin/plugin.json"));
   const manifest=pkg.omp??pkg.pi??(Object.keys(standard).length?standard:compatible);
   const runtimeFile=path.join(summary.scope==="project"?path.dirname(p.projectRegistry):p.root,"omp-plugins.lock.json");
   const state=(await json(runtimeFile)).plugins?.[runtimeName];
   plugins.push({id:summary.id,name:pkg.name??parsePluginId(summary.id)?.name??summary.id,kind:"market",scope:summary.scope,...entry,path:entry.installPath,manifest:displayManifest(manifest),description:manifest?.description??pkg.description??"",enabled:entry.enabled!==false,userEnabled:entry.enabled!==false,enabledFeatures:state?.enabledFeatures??null,userFeatures:state?.enabledFeatures??null,shadowedBy:summary.shadowedBy??null,resources:await resources(entry.installPath),loaded:{available:false,reason:"管理进程未执行插件；无法证明加载成功"},session:{available:false,reason:"当前 RPC 未提供按插件归属的能力快照"}});
 }
 for(const [name,state] of Object.entries(object(runtime.plugins)))if(!seen.has(name)&&!marketNames.has(name)){
  const link=path.join(p.root,"node_modules",name),target=await fs.readlink(link).catch(()=>null),actualPath=target?path.resolve(path.dirname(link),target):link;
  plugins.push({id:name,name,kind:typeof deps[name]==="string"&&parseGitUrl(deps[name])?"git":"link",scope:"user",...object(state),userEnabled:object(state).enabled!==false,path:actualPath,spec:safe(deps[name]??actualPath),broken:true,manifest:{},resources:{skills:[],agents:[],mcp:[]}});
 }
 return {plugins,paths:p,ompVersion:(await json("/opt/omp/package.json")).version??null,marketCount:marketRegistry.marketplaces.length,scope:ctx.cwd?"PROJECT":"GLOBAL",runtimeCapability:"unavailable",autoUpdate:ctx.settings?lookup("marketplace.autoUpdate")?.get(await ctx.settings())??"notify":null};
}
async function detail(args,ctx) {
 const data=await snapshot(ctx),item=data.plugins.find(p=>p.id===args.id&&(!args.scope||p.kind!=="market"||p.scope===args.scope));if(!item)throw Error("Plugin is not installed in the selected scope");
 const p=await paths(ctx),pkg=await json(path.join(item.path,"package.json")),name=pkg.name??item.name;
 const runtime=await json(item.kind==="market"?path.join(item.scope==="project"?path.dirname(p.projectRegistry):p.root,"omp-plugins.lock.json"):p.runtime);
 const override=p.overrides?await json(p.overrides):{},schema=object((pkg.omp??pkg.pi??item.manifest)?.settings),user=object((await json(p.runtime)).settings?.[name]),project=object(override.settings?.[name]);
 const defaults=Object.fromEntries(Object.entries(schema).filter(([,s])=>Object.hasOwn(s,"default")).map(([k,s])=>[k,s.default]));
 const effective={...defaults,...user,...project};
 const redactLayer=(layer)=>Object.fromEntries(Object.entries(layer).map(([k,v])=>[k,!Object.hasOwn(schema,k)?"[未声明值已隐藏]":schema[k]?.secret?"••••••••":safe(v,k)]));
 return {...item,settings:{schema:displaySchema(schema),defaults:redactLayer(defaults),user:redactLayer(user),project:redactLayer(project),effective:redactLayer(effective),unknown:Object.keys({...user,...project}).filter(k=>!Object.hasOwn(schema,k))},rawManifest:safe(pkg),standardManifest:safe(await json(path.join(item.path,"plugin.json"))),compatibleManifest:safe(await json(path.join(item.path,".claude-plugin/plugin.json"))),overridePath:p.overrides};
}
async function projectEdit(ctx,change) {if(!ctx.cwd)throw Error("A project workspace is required");const file=getProjectPluginOverridesPath(ctx.cwd),version=await ctx.version(file),data=await json(file);change(data);await ctx.atomicWrite(file,JSON.stringify(data,null,2)+"\n",version);}
async function runtimeEdit(file,change) {await fs.mkdir(path.dirname(file),{recursive:true});await withFileLock(await fs.realpath(file).catch(()=>file),async()=>{const data=await json(file,{plugins:{},settings:{}});change(data);await writePluginRuntimeConfig(file,data);});}
function sourceSafe(value) {if(/https?:\/\/[^\s/@]+(?::[^\s/@]*)?@/i.test(value)||/[?&](?:token|key|password|secret)=/i.test(value))throw Error("Use SSH or a credential helper, not credentials embedded in a URL");}
export async function executeNative(op,args,ctx) {
 const {native,market}=await managers(ctx),s=scope(ctx,args);
 if(ctx.cwd&&(["marketAdd","marketUpdate","marketRemove"].includes(op)||(op==="doctor"&&args.fix)))throw Error("This operation changes user-level state and is unavailable in a project-only context");
 if(op==="install") {
  confirmed(args);const kind=args.kind;sourceSafe(String(args.spec??""));
  if(kind==="market") {const id=parsePluginId(args.spec);if(!id)throw Error("Expected name@marketplace");return {entry:await market.installPlugin(id.name,id.marketplace,{scope:s,force:args.force===true})};}
  if(ctx.cwd)throw Error("Direct npm, Git and local links are user-only in OMP");
  if(kind==="link") {
   if(!path.isAbsolute(args.spec)||args.spec.startsWith("~"))throw Error("Select an absolute guest directory");
   const pkg=await json(path.join(args.spec,"package.json"));
   if(typeof pkg.name!=="string"||!/^(?:@[a-z0-9][a-z0-9._~-]*\/)?[a-z0-9][a-z0-9._~-]*$/i.test(pkg.name))throw Error("Local package.json must declare a valid npm package name");
   if(!args.force&&await fs.lstat(path.join(getPluginsDir(),"node_modules",pkg.name)).catch(()=>null))throw Error("A same-name installation already exists; explicitly confirm overwrite before linking");
   return {entry:await native.link(args.spec)};
  }
  if(kind!=="npm"&&kind!=="git")throw Error("Unknown install source");
  if(kind==="git"&&!parseGitUrl(args.spec))throw Error("Unrecognized Git source; owner/repo requires github: prefix");
  return {entry:await native.install(args.spec,{force:args.force===true})};
 }
 if(op==="doctor"){
  if(args.fix)confirmed(args);
  const checks=await native.doctor({fix:args.fix===true});
  return {checks:checks.map(check=>{const repair=check.name.startsWith("orphan:")?"删除孤立插件的运行登记和设置":check.name.includes(":feature:")?"从显式选择中移除未知功能":check.name.endsWith(":version")?"按记录版本重新安装并协调版本漂移":check.message==="Missing from node_modules"?"联网运行 Bun 安装缺失依赖":null;return {...check,fixable:repair!==null,repair};})};
 }
 if(op==="marketAdd"){confirmed(args);sourceSafe(args.source);return {market:await market.addMarketplace(args.source)};}
 if(op==="marketUpdate")return {market:await market.updateMarketplace(args.name)};
 if(op==="marketRemove"){confirmed(args);await market.removeMarketplace(args.name);return {};}
 if(op==="upgradeMarket") {
  confirmed(args);const updates=(await market.checkForUpdates()).filter(u=>u.scope===s),results=[];
  for(const u of updates){try{const entry=await market.upgradePlugin(u.pluginId,s);results.push({...u,ok:true,version:entry.version});}catch(error){results.push({...u,ok:false,error:safeError(error)});}}
  return {results};
 }
 const item=await detail(args,ctx);
 if(ctx.cwd&&item.kind!=="market")throw Error("Project context cannot uninstall or upgrade a user-installed direct plugin");
 if(op==="uninstall"){confirmed(args);if(item.kind==="market")await market.uninstallPlugin(item.id,s);else await native.uninstall(item.name);return {};}
 if(op==="upgrade"){confirmed(args);if(item.kind==="link")throw Error("Local links cannot be upgraded");return {result:item.kind==="market"?await market.upgradePlugin(item.id,s):await native.upgrade(item.name)};}
 throw Error("Unknown plugin native mutation");
}
async function child(op,args,ctx) {
 const key=String(args.operationId??"mutation");if(active.has(key))throw Error("An operation with this ID is already running");
 const proc=Bun.spawn([process.execPath,path.join(import.meta.dir,"plugin-worker.ts")],{cwd:ctx.cwd??"/",stdin:"pipe",stdout:"pipe",stderr:"pipe",env:{...process.env,BUN_BE_BUN:"1"},detached:true});active.set(key,proc);
 proc.stdin.write(JSON.stringify({op,args,context:{cwd:ctx.cwd,agentDir:ctx.agentDir}}));proc.stdin.end();
 const out=new Response(proc.stdout).text(),err=new Response(proc.stderr).text();
 try {const [stdout,stderr,exit]=await Promise.all([out,err,proc.exited]);await stopping.get(key);if(exit!==0)throw Error(`Plugin operation interrupted (${exit}); persisted state must be reread`);const result=JSON.parse(stdout);if(result.error)throw Error(result.error);return result.result;}finally{active.delete(key);stopping.delete(key);}
}
async function checkUpdates(args,ctx) {
 const {market}=await managers(ctx),data=await snapshot(ctx),updates=(await market.checkForUpdates()).filter(u=>u.scope===scope(ctx,args)),results=[];
 for(const p of data.plugins.filter(p=>(!args.id||p.id===args.id)&&p.kind!=="market")) {
  if(p.kind==="link"){results.push({id:p.id,status:"unsupported",message:"本地链接不能升级"});continue;}
  try {
   if(p.kind==="git") {
    const src=parseGitUrl(p.spec);if(!src)throw Error("Recorded Git source cannot be parsed");
    const ref=src.ref??"HEAD",proc=Bun.spawn(["git","ls-remote",src.repo,ref,`refs/heads/${ref}`,`refs/tags/${ref}`,`refs/tags/${ref}^{}`],{stdout:"pipe",stderr:"pipe"});
    const [text,error,exit]=await Promise.all([new Response(proc.stdout).text(),new Response(proc.stderr).text(),proc.exited]);if(exit!==0)throw Error(error||"Git remote cannot be queried");
    const refs=text.trim().split("\n").filter(Boolean).map(line=>{const [sha,name]=line.split(/\s+/);return {sha,name};});
    const lock=await fs.readFile(path.join(getPluginsDir(),"bun.lock"),"utf8").catch(()=>"");const resolution=Bun.JSONC.parse(lock||"{}").packages?.[p.name]?.[0]??"",recorded=String(resolution).split("#")[1],current=typeof recorded==="string"&&/^[a-f0-9]{7,40}$/i.test(recorded)?recorded:null;
    const branch=refs.find(value=>value.name===`refs/heads/${ref}`),peeled=refs.find(value=>value.name===`refs/tags/${ref}^{}`),exact=refs.find(value=>value.name===ref),commits=[...new Set(refs.map(value=>value.sha))];
    const remote=branch?.sha??peeled?.sha??exact?.sha??(commits.length===1?commits[0]:null),changed=Boolean(remote&&current&&remote!==current&&!remote.startsWith(current));
    const result={id:p.id,status:remote&&current?changed?"update":"unchanged":"unknown",from:current??p.version,to:remote,message:"只读取远端 ref（标签使用 peeled commit）；缺少可比较的锁定提交或远端结果时无法判断。升级重新解析记录来源/ref"};results.push(result);if(changed)updates.push({...result,pluginId:p.id,scope:"user"});
   } else {
    // Ask the same Bun package manager, in the same plugin root, so scoped/private
    // registries and bunfig/.npmrc settings match native installation resolution.
    const info=await runBunCli(["info",p.name,"version","--json"],getPluginsDir());
    if(info.exitCode!==0)throw Error(info.stderr||"Bun package metadata lookup failed");
    const metadata=JSON.parse(info.stdout),latest=typeof metadata==="string"?metadata:metadata?.version;
    if(typeof latest!=="string")throw Error("Bun did not return a resolved latest version");
    const changed=Bun.semver.order(latest,p.version)>0;const result={id:p.id,status:changed?"update":"unchanged",from:p.version,to:latest,message:"通过 Bun 在插件根查询最新版本，沿用 registry 配置；升级按包名安装最新，不保留原 range/pin"};results.push(result);if(changed)updates.push({...result,pluginId:p.id,scope:"user"});
   }
  } catch(error){results.push({id:p.id,status:"error",message:safeError(error)});}
 }
 return {updates:args.id?updates.filter(u=>u.pluginId===args.id):updates,results,checkedAt:new Date().toISOString(),note:"市场比较已缓存目录，缺少可比较版本会跳过；未报告更新不证明最新。没有执行 upgrade 或 dry-run"};
}
export async function dispatch(operation,args,ctx) {
 const op=operation.replace(/^plugins\./,"");
 if(op==="cancel"){
  const key=String(args.operationId??"mutation"),proc=active.get(key);
  if(proc){
   // The mutation worker starts detached, so all Bun/Git descendants inherit
   // its distinct POSIX process group rather than the management bridge's.
   if(!stopping.has(key))stopping.set(key,new Promise(resolve=>setTimeout(()=>{try{process.kill(-proc.pid,"SIGKILL");}catch{}resolve();},1500)));
   try{process.kill(-proc.pid,"SIGTERM");}catch(error){if(error.code!=="ESRCH")throw error;}
   await stopping.get(key);
  }
  return {cancelRequested:!!proc,note:proc?"已请求终止原生操作及其子进程；取消不撤回已发生的外部副作用，正在重新读取实际状态":"当前没有可取消的原生操作；只读请求继续执行"};
 }
 if(op==="snapshot")return snapshot(ctx);
 if(op==="detail"||op==="raw")return detail(args,ctx);
 if(op==="checkUpdates")return checkUpdates(args,ctx);
 if(op==="markets") {const {market}=await managers(ctx),items=[];await json(registry.getMarketplacesRegistryPath());for(const item of await market.listMarketplaces()){try {await json(item.catalogPath);const entries=await market.listAvailablePlugins(item.name);items.push({...safe(item),catalog:entries.map(e=>({...safe(e),supported:typeof e.source==="string"?item.sourceType!=="url":e.source?.source!=="npm",unsupportedReason:typeof e.source==="string"&&item.sourceType==="url"?"JSON 目录没有仓库根，无法安装相对来源":typeof e.source==="object"&&e.source?.source==="npm"?"当前 OMP 不支持市场 npm 来源，请改用直接安装":""}))});}catch(error){items.push({...safe(item),catalog:[],error:safeError(error)});}}return {markets:items};}
 if(op==="validate") {const item=await detail(args,ctx),errors=[];for(const [key,schema]of Object.entries(item.settings.schema)){const value=item.settings.effective[key];if(value===undefined||object(schema).secret)continue;const result=validateSetting(value,schema);if(!result.valid)errors.push({key,error:result.error});}return {valid:errors.length===0,errors,note:"仅校验已声明字段类型和范围，不校验 required/未知键；秘密值单独在写入时校验"};}
 if(op==="autoUpdate") {if(ctx.cwd)throw Error("Marketplace auto-update is a user-only setting");if(!["off","notify","auto"].includes(args.value))throw Error("Invalid marketplace auto-update mode");await ctx.editSettings("GLOBAL",{"marketplace.autoUpdate":args.value});return {changed:true,scope:"GLOBAL"};}
 if(op==="enable"||op==="features"||op==="setting") {
  const item=await detail(args,ctx),{native,market}=await managers(ctx),s=scope(ctx,args);
  if(op==="enable") {if(item.kind==="market")await market.setPluginEnabled(item.id,args.enabled===true,s);else if(ctx.cwd)await projectEdit(ctx,o=>{o.disabled=(o.disabled??[]).filter((n)=>n!==item.name);if(!args.enabled)o.disabled.push(item.name);});else await native.setEnabled(item.name,args.enabled===true);}
  if(op==="features") {
   const value=args.value;if(value!==null&&(!Array.isArray(value)||value.some(v=>typeof v!=="string")))throw Error("Features must be null or a string array");
   if(ctx.cwd&&item.kind!=="market")await projectEdit(ctx,o=>{o.features??={};if(value===null)delete o.features[item.name];else o.features[item.name]=value;});
   else if(item.kind==="market") {const p=await paths(ctx),file=path.join(s==="project"?path.dirname(p.projectRegistry):p.root,"omp-plugins.lock.json");await runtimeEdit(file,o=>{if(!o.plugins?.[item.name])throw Error("Runtime plugin state is missing");o.plugins[item.name].enabledFeatures=value;});}
   else await native.setEnabledFeatures(item.name,value);
  }
  if(op==="setting") {
   if(["__proto__","prototype","constructor"].includes(args.key))throw Error("Invalid setting key");const schema=item.manifest?.settings?.[args.key];if(!schema)throw Error("Only declared schema fields may be edited; unknown values are preserved");
   if(!args.clear){const result=validateSetting(args.value,schema);if(!result.valid)throw Error(`${args.key}: ${result.error}`);if(typeof args.value==="number"&&!Number.isFinite(args.value))throw Error("Setting must be a finite number");}
   const edit=(o)=>{o.settings??={};o.settings[item.name]??={};if(args.clear)delete o.settings[item.name][args.key];else o.settings[item.name][args.key]=args.value;};
   if(ctx.cwd)await projectEdit(ctx,edit);else if(args.clear)await native.deletePluginSetting(item.name,args.key);else await native.setPluginSetting(item.name,args.key,args.value);
  }
  return {changed:true,scope:ctx.cwd?"PROJECT":"GLOBAL"};
 }
 if(["install","uninstall","upgrade","upgradeMarket","marketAdd","marketUpdate","marketRemove","doctor"].includes(op)) {
  const result=await child(op,args,ctx);return {...safe(result),...((op!=="doctor"||args.fix)?{changed:true,scope:["marketAdd","marketUpdate","marketRemove","doctor"].includes(op)?"GLOBAL":ctx.cwd?"PROJECT":"GLOBAL"}:{})};
 }
 throw Error("Unknown plugin management operation");
}
