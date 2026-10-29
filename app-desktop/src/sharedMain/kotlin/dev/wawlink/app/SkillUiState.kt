package dev.wawlink.app

import androidx.compose.runtime.*
import dev.wawlink.protocol.AgentResourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class SkillUiState(val repository: AgentResourceRepository, private val coroutine: CoroutineScope) {
    var snapshot by mutableStateOf<SkillObject>(emptyMap())
    var page by mutableStateOf("home")
    var history by mutableStateOf(emptyList<String>())
    var detail by mutableStateOf<SkillObject>(emptyMap())
    var packageData by mutableStateOf<SkillObject>(emptyMap())
    var market by mutableStateOf<SkillObject>(emptyMap())
    var marketSource by mutableStateOf("clawhub")
    var clawMarket by mutableStateOf<SkillObject>(emptyMap())
    var clawQuery by mutableStateOf("")
    var clawSearchQuery by mutableStateOf("")
    var clawSort by mutableStateOf("downloads")
    var clawDetail by mutableStateOf<SkillObject>(emptyMap())
    var clawIdentity by mutableStateOf<SkillObject>(emptyMap())
    var clawFile by mutableStateOf<SkillObject?>(null)
    var clawFilePath by mutableStateOf<String?>(null)
    private var retryAction: (() -> Unit)? = null
    var author by mutableStateOf<SkillObject>(emptyMap())
    var tokens by mutableStateOf(emptyList<SkillObject>())
    var mine by mutableStateOf(emptyList<SkillObject>())
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var changed by mutableStateOf(false)
    var scriptRequest by mutableStateOf<SkillObject?>(null)
    var pendingOperation: Triple<String, SkillObject, (SkillObject) -> Unit>? = null
    var approvals = emptyList<String>()
    val project get() = repository.context.projectPath != null
    val scopeLabel get() = if (project) "项目" else "用户"
    fun navigate(next: String) { history += page; page = next; error = null }
    fun back(): Boolean { if (history.isEmpty()) return false; page = history.last(); history = history.dropLast(1); error = null; return true }
    fun request(op: String, args: SkillObject = emptyMap(), label: String = "正在读取", done: (SkillObject) -> Unit = {}) {
        if (busy != null) return
        busy = label; error = null; retryAction = null
        coroutine.launch {
            try {
                val result = repository.request("skills.$op", args)
                if (result.skillBool("changed")) { changed = true; snapshot = repository.request("skills.snapshot") }
                busy = null
                if (result["scriptApproval"] != null) {
                    scriptRequest = result.skillObject("scriptApproval")
                    pendingOperation = Triple(op, args, done)
                } else done(result)
            } catch (cancelled: CancellationException) { busy = null; throw cancelled }
            catch (failure: Exception) {
                error = failure.message ?: "原生操作失败"
                retryAction = if (op == "clawhub.install") null else ({ request(op, args, label, done) })
                try {
                    when (op) {
                        "install", "update", "restore", "uninstall", "bumpVersion", "repairPackageFile" ->
                            snapshot = repository.request("skills.snapshot")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Preserve the original operation error. */ }
                finally { busy = null }
            }
        }
    }
    fun refresh() = request("snapshot", label = "正在读取容器技能") { snapshot = it }
    fun openSkill(id: String) = request("detail", mapOf("id" to id)) { detail = it; navigate("detail") }
    fun openPackage(id: String, version: String? = null) = request("package", mapOf("id" to id, "version" to version)) { packageData = it; navigate("package") }
    fun retry() { retryAction?.invoke() }
    val canRetry get() = retryAction != null && busy == null
    fun openMarket(source: String = marketSource) {
        if (busy != null) return
        marketSource = source
        navigate("market")
        ensureMarket()
    }
    fun selectMarket(source: String) {
        if (busy != null || marketSource == source) return
        marketSource = source; error = null
        ensureMarket()
    }
    private fun ensureMarket() {
        if (marketSource == "clawhub") {
            if (clawMarket.isEmpty()) browseClawHub()
        } else if (market.isEmpty()) request("home", label = "正在读取官方 Skillshare") { market = it }
    }
    fun browseClawHub(sort: String = clawSort) {
        if (busy != null) return
        clawSort = sort; clawSearchQuery = ""; clawMarket = emptyMap()
        request("clawhub.list", mapOf("sort" to sort), "正在浏览 ClawHub") { clawMarket = it }
    }
    fun searchClawHub() {
        if (busy != null) return
        val query = clawQuery.trim()
        if (query.isEmpty()) { browseClawHub(); return }
        clawSearchQuery = query; clawMarket = emptyMap()
        request("clawhub.search", mapOf("query" to query), "正在搜索 ClawHub") { clawMarket = it }
    }
    fun moreClawHub() {
        val cursor = clawMarket.skillText("nextCursor")
        if (cursor.isEmpty() || clawSearchQuery.isNotEmpty()) return
        request("clawhub.list", mapOf("sort" to clawSort, "cursor" to cursor), "正在读取更多技能") { result ->
            clawMarket = result + ("items" to (clawMarket.skillObjects("items") + result.skillObjects("items")).distinctBy { it.skillText("owner") to it.skillText("slug") })
        }
    }
    fun openClawHub(item: SkillObject) {
        if (busy != null) return
        clawIdentity = mapOf("owner" to item.skillText("owner"), "slug" to item.skillText("slug"))
        clawDetail = emptyMap(); clawFile = null; clawFilePath = null
        navigate("clawhubDetail")
        loadClawHubVersion()
    }
    fun loadClawHubVersion(version: String? = null) {
        if (busy != null) return
        clawDetail = emptyMap(); clawFile = null; clawFilePath = null
        request("clawhub.detail", clawIdentity + if (version == null) emptyMap() else mapOf("version" to version), "正在读取技能与完整资源清单") { clawDetail = it }
    }
    fun moreClawHubVersions() {
        val cursor = clawDetail.skillText("nextCursor")
        if (cursor.isEmpty()) return
        request("clawhub.versions", clawIdentity + mapOf("cursor" to cursor), "正在读取更多版本") { result ->
            clawDetail = clawDetail + mapOf("versions" to (clawDetail.skillObjects("versions") + result.skillObjects("versions")).distinctBy { it.skillText("version") }, "nextCursor" to result["nextCursor"])
        }
    }
    fun previewClawHub(path: String) {
        if (busy != null) return
        clawFile = null; clawFilePath = path
        request("clawhub.file", clawIdentity + mapOf("version" to clawDetail.skillObject("version").skillText("version"), "path" to path), "正在读取有界资源预览") { clawFile = it }
    }
    fun approveScripts() {
        val approval = scriptRequest ?: return
        approvals = (approvals + "${approval.skillText("id")}@${approval.skillText("version")}").distinct()
        scriptRequest = null
        val pending = pendingOperation ?: return
        pendingOperation = null
        request(pending.first, pending.second + ("approvals" to approvals), "正在安装并校验", pending.third)
    }
}
