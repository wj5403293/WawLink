package dev.wawlink.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatErrorDiagnosisTest {
    @Test fun identifiesOmpFailuresWithoutConfusingUnrelatedMissingFiles() {
        val errors = listOf(
            "容器中的 OMP 缺失", "OMP 安装损坏", "OMP 输出流已关闭", "OMP 通信响应损坏",
            "OMP 进程未启动", "Cannot find module 'native'", "ENOENT: /opt/omp/bin/omp",
        )
        for (error in errors) {
            assertEquals(listOf(ChatErrorAction.OPEN_OMP_REPAIR), diagnoseChatError(error, "selected"), error)
        }
        assertTrue(diagnoseChatError("ENOENT: /workspace/note.txt", "selected").isEmpty())
    }

    @Test fun identifiesProviderAuthenticationAndModelFailures() {
        val errors = listOf(
            "缺少服务标识", "缺少模型标识", "No available model", "API key is missing", "missing apiKey",
            "Unauthorized", "HTTP 401", "HTTP 403", "HTTP 404", "authentication failed", "缺少凭证", "认证失败",
            "配额不足", "余额不足", "Rate limit exceeded", "HTTP 429", "model_not_found", "未找到模型",
            "模型服务配置无效", "模型不可用", "没有可用模型匹配此角色的候选链",
        )
        for (error in errors) {
            assertEquals(listOf(ChatErrorAction.OPEN_PROVIDER_SETTINGS), diagnoseChatError(error, "selected"), error)
        }
        assertTrue(diagnoseChatError("错误编号 1401", "selected").isEmpty())
    }

    @Test fun defaultRoleAndBlankModelAlsoOfferAgentSettings() {
        val expected = listOf(ChatErrorAction.OPEN_PROVIDER_SETTINGS, ChatErrorAction.OPEN_AGENT_SETTINGS)
        for (error in listOf("原生默认模型响应缺失", "无法读取默认模型", "No available model for default role")) {
            assertEquals(expected, diagnoseChatError(error, "selected"), error)
        }
        assertEquals(expected, diagnoseChatError("其他故障", ""))
        assertEquals(expected, diagnoseChatError("Unauthorized", "  "))
    }

    @Test fun explicitFailuresTakePriorityAndRepairRequiresHostCallback() {
        assertEquals(listOf(ChatErrorAction.OPEN_OMP_REPAIR, ChatErrorAction.OPEN_PROVIDER_SETTINGS),
            diagnoseChatError("Cannot find module 'omp'", ""))
        assertEquals(listOf(ChatErrorAction.OPEN_PROVIDER_SETTINGS, ChatErrorAction.OPEN_AGENT_SETTINGS),
            visibleChatErrorActions("Cannot find module 'omp'", "", canRepairOmp = false))
        assertEquals(listOf(ChatErrorAction.OPEN_OMP_REPAIR, ChatErrorAction.OPEN_PROVIDER_SETTINGS),
            visibleChatErrorActions("Cannot find module 'omp'", "", canRepairOmp = true))
        assertTrue(visibleChatErrorActions("OMP 进程未启动", "selected", canRepairOmp = false).isEmpty())
    }

    @Test fun sessionManagementUsesTheExistingCloseControl() {
        for (error in listOf("OMP 会话管理扩展未就绪", "历史树请求超时", "Agent Hub 暂不可用")) {
            assertEquals(listOf(ChatErrorAction.DISMISS), diagnoseChatError(error, "selected"), error)
            assertTrue(visibleChatErrorActions(error, "selected", canRepairOmp = true).isEmpty(), error)
        }
        assertTrue(diagnoseChatError("普通错误", "selected").isEmpty())
    }
}
