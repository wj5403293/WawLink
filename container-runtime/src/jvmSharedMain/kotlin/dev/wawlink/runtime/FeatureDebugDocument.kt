package dev.wawlink.runtime

import kotlinx.serialization.json.JsonPrimitive

/** Runs in the existing trusted main document; no browser or host object is created. */
val FEATURE_DEBUG_BOOTSTRAP: String = """
(function(){
  if(window!==window.top||window.__wawlinkFeatureDebug)return;
  var entries=[],dropped=0,sequence=0,pending=Object.create(null);
  function text(value){try{if(value instanceof Error)return String(value)+(value.stack?'\n'+String(value.stack):'');
    if(typeof value==='string')return value;var encoded=JSON.stringify(value);return encoded===undefined?String(value):encoded;
    }catch(error){return String(value);}}
  function url(value){try{var parsed=new URL(value,location.href);return parsed.origin+parsed.pathname;}catch(error){return '';}}
  function record(level,message,source,line,column){
    if(entries.length===200){entries.shift();dropped++;}
    entries.push({sequence:++sequence,time:Date.now(),level:level,message:String(message).slice(0,4096),source:url(source||''),line:line||0,column:column||0});
  }
  var api={entries:entries,pending:pending,documentId:Date.now().toString(36)+'-'+Math.random().toString(36).slice(2),
    logs:function(){return {documentId:api.documentId,entries:entries.slice(),dropped:dropped};}};
  Object.defineProperty(window,'__wawlinkFeatureDebug',{value:api,configurable:false});
  ['debug','log','info','warn','error'].forEach(function(level){var original=console[level];
    console[level]=function(){try{record(level,Array.prototype.map.call(arguments,text).join(' '));}catch(error){}
      if(original)return original.apply(console,arguments);};});
  window.addEventListener('error',function(event){
    if(event.target&&event.target!==window){var node=event.target;record('resource-error','资源加载失败',node.src||node.href||'');}
    else record('error',event.error?text(event.error):event.message,event.filename,event.lineno,event.colno);
  },true);
  window.addEventListener('unhandledrejection',function(event){record('unhandledrejection',text(event.reason));});
})();
""".trimIndent()

val FEATURE_DEBUG_INSPECT_SCRIPT: String = """
(function(){
  function element(node){if(!node)return null;var rect=node.getBoundingClientRect(),style=getComputedStyle(node);
    return {tag:node.tagName,width:rect.width,height:rect.height,x:rect.x,y:rect.y,clientWidth:node.clientWidth,clientHeight:node.clientHeight,
      scrollWidth:node.scrollWidth,scrollHeight:node.scrollHeight,cssWidth:style.width,cssHeight:style.height,display:style.display,
      visibility:style.visibility,overflowX:style.overflowX,overflowY:style.overflowY,position:style.position};}
  return JSON.stringify({documentId:window.__wawlinkFeatureDebug?window.__wawlinkFeatureDebug.documentId:null,
    url:location.origin+location.pathname,title:document.title,readyState:document.readyState,visibilityState:document.visibilityState,
    viewport:{width:innerWidth,height:innerHeight,devicePixelRatio:devicePixelRatio},html:element(document.documentElement),body:element(document.body),
    bridgeReady:!!(window.wawlink&&window.wawlink.ready),card:window.wawlink&&window.wawlink.card||null});
})();
""".trimIndent()

val FEATURE_DEBUG_LOGS_SCRIPT: String = """
JSON.stringify(window.__wawlinkFeatureDebug?window.__wawlinkFeatureDebug.logs():{entries:[],dropped:0,unavailable:true})
""".trimIndent()

fun featureDebugEvalStart(id: String, expression: String): String = """
(function(){
  var api=window.__wawlinkFeatureDebug;if(!api)throw new Error('页面调试尚未初始化');
  var id=${debugJsString(id)},pending=api.pending;
  pending[id]={done:false};
  function fail(error){if(Object.prototype.hasOwnProperty.call(pending,id))pending[id]={done:true,ok:false,error:String(error)+(error&&error.stack?'\n'+String(error.stack):'')};}
  try{Promise.resolve((0,eval)(${debugJsString(expression)})).then(function(value){
    if(!Object.prototype.hasOwnProperty.call(pending,id))return;
    try{var encoded=JSON.stringify(value===undefined?null:value,function(key,item){
      if(typeof item==='bigint'||typeof item==='function'||typeof item==='symbol')throw new Error('执行结果不能转换为 JSON');
      if(typeof item==='number'&&!isFinite(item))throw new Error('执行结果包含非有限数字');return item;});
      if(encoded.length>1048576)throw new Error('执行结果超过 1 MiB');
      pending[id]={done:true,ok:true,value:JSON.parse(encoded)};
    }catch(error){fail(error);}
  },fail);}catch(error){fail(error);}
})();
""".trimIndent()

fun featureDebugEvalPoll(id: String): String = """
(function(){var api=window.__wawlinkFeatureDebug,id=${debugJsString(id)};
  if(!api||!Object.prototype.hasOwnProperty.call(api.pending,id))return JSON.stringify({done:true,ok:false,error:'页面已导航或执行已取消'});
  var result=api.pending[id];if(result.done)delete api.pending[id];return JSON.stringify(result);})();
""".trimIndent()

fun featureDebugEvalCancel(id: String): String =
    "if(window.__wawlinkFeatureDebug)delete window.__wawlinkFeatureDebug.pending[${debugJsString(id)}];"

private fun debugJsString(value: String): String = JsonPrimitive(value).toString()
    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
