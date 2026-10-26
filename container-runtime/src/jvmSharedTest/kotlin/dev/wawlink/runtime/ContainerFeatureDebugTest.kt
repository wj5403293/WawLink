package dev.wawlink.runtime

import java.lang.reflect.Proxy
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContainerFeatureDebugTest {
    private class Fixture {
        private val config = ContainerConfig("container", "Test", "/unused")
        val containers = MutableStateFlow(listOf(ContainerInfo(config, running = true, runGeneration = 4)))
        val catalogCalls = AtomicInteger()
        val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, _ ->
            when (method.name) {
                "getContainers" -> containers
                "toString" -> "DebuggerFixtureRuntime"
                else -> error("Debugger must not invoke runtime method ${method.name}")
            }
        } as ContainerRuntime
        val debugger = ContainerFeatureDebugger(runtime, {
            catalogCalls.incrementAndGet()
            ContainerFeatureCatalog("/home/test", listOf(ContainerFeature("demo", "Demo", "Description", "terminal", "/home/test/.wawlink/features/demo")), emptyList())
        }, { _, _ -> ContainerFeatureState(output = "real service output", error = "service failure") })
        val card = FeatureDebugTarget("container", "demo", "card")
        val page = card.copy(surface = "page")
        suspend fun request(operation: String, surface: String = "card", generation: Long = 4) =
            debugger.request("container", generation, operation, "demo", surface, if (operation == "eval") "value" else null)
    }

    @Test fun duplicateMountsFailAndClosingOwnHandlePreservesOtherMountsAndSurfaces() = runBlocking {
        val fixture = Fixture()
        fixture.debugger.use {
            val first = it.register(fixture.card) { _, _ -> JsonPrimitive("first") }
            val second = it.register(fixture.card) { _, _ -> JsonPrimitive("second") }
            val page = it.register(fixture.page) { _, _ -> JsonPrimitive("page") }
            assertTrue(runCatching { fixture.request("eval") }.exceptionOrNull() is IllegalStateException)
            assertEquals(JsonPrimitive("page"), fixture.request("eval", "page"))
            first.close()
            assertEquals(JsonPrimitive("second"), fixture.request("eval"))
            first.close()
            assertEquals(JsonPrimitive("second"), fixture.request("eval"))
            second.close()
            page.close()
            val inspection = fixture.request("inspect").jsonObject
            assertFalse(inspection.getValue("mounted").jsonPrimitive.boolean)
            assertEquals("real service output", fixture.request("logs").jsonObject.getValue("output").jsonPrimitive.content)
            assertEquals("service failure", inspection.getValue("error").jsonPrimitive.content)
            assertTrue(runCatching { fixture.request("screenshot") }.isFailure)
            assertTrue(runCatching { fixture.request("reload") }.isFailure)
        }
    }

    @Test fun stoppedDeletedAndConflatedRestartCannotUseStaleMountsOrStartRuntime() = runBlocking {
        val fixture = Fixture()
        fixture.debugger.use {
            val old = it.register(fixture.card) { _, _ -> JsonPrimitive("old") }
            fixture.containers.value = fixture.containers.value.map { container -> container.copy(running = false) }
            assertTrue(runCatching { fixture.request("eval") }.isFailure)
            assertEquals(0, fixture.catalogCalls.get(), "Stopped requests must fail before discovery")
            fixture.containers.value = fixture.containers.value.map { container -> container.copy(running = true, runGeneration = 5) }
            assertTrue(runCatching { fixture.request("eval") }.isFailure)
            assertFalse(fixture.request("inspect", generation = 5).jsonObject.getValue("mounted").jsonPrimitive.boolean)
            val replacement = it.register(fixture.card) { _, _ -> JsonPrimitive("new") }
            it.containersChanged()
            old.close()
            assertEquals(JsonPrimitive("new"), fixture.request("eval", generation = 5))
            fixture.containers.value = emptyList()
            assertTrue(runCatching { fixture.request("reload", generation = 5) }.isFailure)
            it.containersChanged()
            replacement.close()
        }
    }

    @Test fun disposingMountCancelsPendingRequestRatherThanReturningStaleValue() = runBlocking {
        val fixture = Fixture()
        fixture.debugger.use {
            val entered = CompletableDeferred<Unit>()
            val registration = it.register(fixture.card) { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }
            val pending = async { runCatching { fixture.request("eval") } }
            withTimeout(5000) { entered.await() }
            registration.close()
            assertTrue(withTimeout(5000) { pending.await() }.isFailure)
            assertFalse(fixture.request("inspect").jsonObject.getValue("mounted").jsonPrimitive.boolean)
        }
    }

    @Test fun endpointRejectsMissingWrongCrossContainerAndBrowserCredentials() {
        val calls = AtomicInteger()
        FeatureDebugEndpoint("first", 7) { id, generation, _, _, _, _ ->
            calls.incrementAndGet()
            buildJsonObject { put("containerId", id); put("generation", generation) }
        }.use { first ->
            FeatureDebugEndpoint("second", 11) { _, _, _, _, _, _ -> JsonNull }.use { second ->
                val config = first.configuration()
                val firstToken = config.getValue("token").jsonPrimitive.content
                val secondToken = second.configuration().getValue("token").jsonPrimitive.content
                assertNotEquals(firstToken, secondToken)
                assertEquals(403, post(config, null).first)
                assertEquals(403, post(config, secondToken).first)
                assertEquals(403, post(config, firstToken, "Origin: http://127.0.0.1\r\n").first)
                assertEquals(403, post(config, firstToken, "Referer: http://127.0.0.1/\r\n").first)
                assertEquals(0, calls.get())
                val (status, response) = post(config, firstToken)
                assertEquals(200, status)
                assertFalse(response.contains("Access-Control-Allow", ignoreCase = true))
                assertFalse(response.contains(firstToken))
                val body = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
                assertEquals("first", body.getValue("result").jsonObject.getValue("containerId").jsonPrimitive.content)
                assertEquals(1, calls.get())
            }
        }
    }

    private fun post(config: JsonObject, token: String?, extraHeaders: String = ""): Pair<Int, String> {
        val url = URI(config.getValue("url").jsonPrimitive.content)
        val body = """{"operation":"list","surface":"card"}""".toByteArray()
        return Socket("127.0.0.1", url.port).use { socket ->
            socket.soTimeout = 5000
            val headers = buildString {
                append("POST /v1 HTTP/1.1\r\nHost: 127.0.0.1:${url.port}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                append(extraHeaders); append("\r\n")
            }
            socket.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
            val response = featureReadBounded(socket.getInputStream(), 1_048_576).toString(Charsets.UTF_8)
            response.substringBefore("\r\n").split(' ')[1].toInt() to response
        }
    }
}
