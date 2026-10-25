package dev.wawlink.app

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

internal const val FEATURE_CARD_ORIGIN = "https://wawlink-card.invalid"
internal const val FEATURE_CARD_URL = "$FEATURE_CARD_ORIGIN/card.html"
internal fun featureJsString(value: String): String = JsonPrimitive(value).toString()
    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

internal data class FeatureWebFont(val bytes: ByteArray, val css: String)
private val featureFontLock = Mutex()
private var cachedFeatureFont: FeatureWebFont? = null
private var cachedSymbolScript: String? = null

/** Cache the static Symbols face and its encoded CSS across all embedded documents. */
internal suspend fun featureWebFont(): FeatureWebFont = featureFontLock.withLock {
    cachedFeatureFont ?: dev.wawlink.ui.resources.Res.readBytes("font/material_symbols_filled.ttf").let { bytes ->
        FeatureWebFont(bytes, "@font-face{font-family:'Material Symbols Rounded';src:url(data:font/ttf;base64,${Base64.getEncoder().encodeToString(bytes)}) format('truetype');font-weight:normal}")
    }.also { cachedFeatureFont = it }
}

/** WebKit does not apply the Symbols ligature table; resolve names from the bundled native index. */
internal suspend fun featureSymbolScript(): String = featureFontLock.withLock {
    cachedSymbolScript ?: run {
        val points = dev.wawlink.ui.resources.Res.readBytes("files/material_symbols_rounded.codepoints").decodeToString()
        val mapping = buildString {
            append('{')
            points.lineSequence().forEach { line ->
                val gap = line.indexOf(' ')
                if (gap > 0) line.substring(gap + 1).trim().toIntOrNull(16)?.let { point ->
                    if (length > 1) append(',')
                    append(featureJsString(line.substring(0, gap))).append(':')
                    append(featureJsString(String(Character.toChars(point))))
                }
            }
            append('}')
        }
        """
        (function(){
          if(window.__wawlinkSymbols)return;window.__wawlinkSymbols=true;
          var points=$mapping,selector='.material-symbols-outlined,.material-symbols-rounded';
          function symbol(el){
            if(el.children.length)return;
            var name=el.textContent.trim(),glyph=points[name];
            if(typeof glyph==='string')el.textContent=glyph;
          }
          function scan(root){
            if(root.nodeType===3)root=root.parentNode;
            if(!root||!root.querySelectorAll)return;
            if(root.matches&&root.matches(selector))symbol(root);
            Array.prototype.forEach.call(root.querySelectorAll(selector),symbol);
          }
          new MutationObserver(function(changes){changes.forEach(function(change){
            if(change.type==='childList'){
              if(change.target.matches&&change.target.matches(selector))symbol(change.target);
              Array.prototype.forEach.call(change.addedNodes,scan);
            }else scan(change.target);
          });}).observe(document,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['class']});
          scan(document);
        })();
        """.trimIndent()
    }.also { cachedSymbolScript = it }
}


/** Insert before guest markup so APIs and theme exist for the first guest script. */
internal fun featureCardDocument(html: String, css: String, data: String, font: FeatureWebFont, bootstrap: String): String {
    val script = bootstrap + ";" + featureCardUpdateScript(css, data)
    val head = "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; media-src data:; frame-src 'none'; child-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">" +
        "<style>html,body{margin:0;height:100%;width:100%;overflow:hidden;box-sizing:border-box}*,*:before,*:after{box-sizing:inherit}</style>" +
        "<script>${script.replace("</script", "<\\/script", ignoreCase = true)}</script><style>${font.css}</style>"
    val existingHead = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
    return if (existingHead != null) html.substring(0, existingHead.range.last + 1) + head + html.substring(existingHead.range.last + 1)
    else "<!doctype html><html><head>$head</head><body>$html</body></html>"
}

internal fun featureCardUpdateScript(css: String, data: String): String =
    containerFeatureThemeScript(css) + ";window.wawlink.card=JSON.parse(${featureJsString(data)});window.dispatchEvent(new CustomEvent('wawlink-card-update',{detail:window.wawlink.card}));"

internal fun featureAndroidBootstrap(): String = """
(function(){
  if(window!==window.top||window.wawlink)return;
  // A same-origin child could otherwise call parent.wawlink. Disable child documents.
  function installPolicy(){if(!document.documentElement)return false;
    var policy=document.createElement('meta');policy.httpEquiv='Content-Security-Policy';
    policy.content="frame-src 'none'; child-src 'none'; object-src 'none'";
    (document.head||document.documentElement).appendChild(policy);return true;}
  if(!installPolicy()){var observer=new MutationObserver(function(){if(installPolicy())observer.disconnect();});observer.observe(document,{childList:true,subtree:true});}
  var bridge=window.__wawlinkNative, pending=new Map(), sequence=0;
  var documentId=Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);
  function send(op,args){return new Promise(function(resolve,reject){
    var id=String(++sequence);pending.set(id,{resolve:resolve,reject:reject});
    try{bridge.postMessage(JSON.stringify({document:documentId,id:id,op:op,args:args}));}
    catch(error){pending.delete(id);reject(error);}
  });}
  bridge.onmessage=function(event){var message;try{message=JSON.parse(event.data);}catch(e){return;}
    if(message.document!==documentId)return;var request=pending.get(message.id);if(!request)return;
    pending.delete(message.id);if(message.ok)request.resolve(message.value);else request.reject(new Error(message.error));
  };
  window.addEventListener('pagehide',function(){pending.forEach(function(p){p.reject(new Error('Document disposed'));});pending.clear();},{once:true});
  window.wawlink={lua:function(source){return send('lua',[String(source)]);},
    action:function(action){return send('action',[String(action)]);},
    openUrl:function(url){return send('openUrl',[String(url)]);},
    openBrowser:function(url){return send('openBrowser',[String(url)]);},
    share:function(text,title){return send('share',[String(text),title==null?null:String(title)]);},
    launchApp:function(name){return send('launchApp',[String(name)]);}};
  bridge.postMessage(JSON.stringify({document:documentId,op:'ready'}));
})();
""".trimIndent()

internal fun featureDesktopBootstrap(): String = """
(function(){if(window.wawlink)return;
 function unavailable(){return Promise.reject(new Error('Android API is unavailable on desktop'));}
 window.wawlink={lua:unavailable,openUrl:unavailable,openBrowser:unavailable,share:unavailable,launchApp:unavailable,
 action:function(action){return new Promise(function(resolve,reject){
   if(['open','start','stop'].indexOf(action)<0){reject(new Error('Unknown card action'));return;}
   var button=document.createElement('button');button.hidden=true;button.setAttribute('data-wawlink-native-action',action);
   (document.body||document.documentElement).appendChild(button);button.click();resolve(null);
 });}};
})();
""".trimIndent()

internal fun featureWhenDocumentExists(script: String): String = """
(function(){function apply(){if(!document.documentElement)return false;$script;return true;}
if(!apply()){var observer=new MutationObserver(function(){if(apply())observer.disconnect();});observer.observe(document,{childList:true,subtree:true});}})();
""".trimIndent()

internal fun featureDesktopUnavailableScript(): String = """
(function(){function unavailable(){return Promise.reject(new Error('Android API is unavailable on desktop'));}
window.wawlink={lua:unavailable,openUrl:unavailable,openBrowser:unavailable,share:unavailable,launchApp:unavailable,
action:function(){return Promise.reject(new Error('Card actions are unavailable on full pages'));}};})();
""".trimIndent()
