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
        busy = label; error = null
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
            catch (failure: Exception) { busy = null; error = failure.message ?: "原生操作失败" }
        }
    }
    fun refresh() = request("snapshot", label = "正在读取容器技能") { snapshot = it }
    fun openSkill(id: String) = request("detail", mapOf("id" to id)) { detail = it; navigate("detail") }
    fun openPackage(id: String, version: String? = null) = request("package", mapOf("id" to id, "version" to version)) { packageData = it; navigate("package") }
    fun approveScripts() {
        val approval = scriptRequest ?: return
        approvals = (approvals + "${approval.skillText("id")}@${approval.skillText("version")}").distinct()
        scriptRequest = null
        val pending = pendingOperation ?: return
        pendingOperation = null
        request(pending.first, pending.second + ("approvals" to approvals), "正在安装并校验", pending.third)
    }
}
