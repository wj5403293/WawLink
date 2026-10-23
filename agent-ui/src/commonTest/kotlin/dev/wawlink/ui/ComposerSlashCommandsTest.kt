package dev.wawlink.ui

import dev.wawlink.protocol.AgentSlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerSlashCommandsTest {
    @Test fun customShadowNeverInheritsBuiltinPresentationRegardlessOfCatalogOrder() {
        val builtin = AgentSlashCommand("compact", "builtin")
        val extension = AgentSlashCommand("compact", "extension", listOf("squeeze"))
        for (catalog in listOf(listOf(builtin, extension), listOf(extension, builtin))) {
            val commands = composerSlashCommands(catalog)
            val shadow = commands.single { it.name == "compact" }
            assertNull(shadow.description)
            assertEquals(ComposerSlashCommand("unknown").icon, shadow.icon)
            assertEquals(listOf("compact"), matchingSlashCommands(commands, "squee").map { it.name })
        }
    }

    @Test fun localCommandsRemainAvailableWithoutInventingNativeCommands() {
        val commands = composerSlashCommands(emptyList())
        assertEquals(LocalSlashAction.MODEL, localSlashAction("/${commands.single { it.name == "model" }.name}"))
        assertEquals(LocalSlashAction.COST, localSlashAction("/${commands.single { it.name == "cost" }.name}"))
        for (obsolete in listOf("plan", "review", "test", "clear")) {
            assertFalse(commands.any { it.name == obsolete })
        }
    }

    @Test fun localNativeNameCollisionDoesNotDuplicateCompletionOrLoseAliases() {
        val commands = composerSlashCommands(listOf(
            AgentSlashCommand("model", "builtin", listOf("models")),
            AgentSlashCommand("cost", "extension"),
        ))
        assertEquals(1, commands.count { it.name == "model" })
        assertEquals(1, commands.count { it.name == "cost" })
        assertEquals("model", matchingSlashCommands(commands, "MODELS").single().name)
    }

    @Test fun bareLocalCommandsDoNotInterceptNativeArgumentsOrAliases() {
        assertEquals(LocalSlashAction.MODEL, localSlashAction(" /model \n"))
        assertEquals(LocalSlashAction.COST, localSlashAction("/cost"))
        for (native in listOf("/model provider/model", "/model\tprovider/model", "/models", "/cost details", "/modelpreset")) {
            assertNull(localSlashAction(native))
        }
    }

    @Test fun customLocalNameCollisionRetainsNeutralPresentation() {
        val commands = composerSlashCommands(listOf(AgentSlashCommand("model", "extension")))
        val model = commands.single { it.name == "model" }
        assertNull(model.description)
        assertEquals(ComposerSlashCommand("unknown").icon, model.icon)
        assertEquals(LocalSlashAction.MODEL, localSlashAction("/${model.name}"))
    }

    @Test fun completionStopsAtArgumentBoundaryIncludingNonSpaceWhitespace() {
        assertEquals("", slashQuery("/"))
        assertEquals("my-tool", slashQuery("/my-tool"))
        for (text in listOf("", "hello /model", "/model ", "/model\targ", "/model\narg")) {
            assertNull(slashQuery(text))
        }
    }

    @Test fun nativeAliasesMatchButCompleteToCanonicalName() {
        val commands = composerSlashCommands(listOf(
            AgentSlashCommand("wt", "builtin", listOf("worktree")),
            AgentSlashCommand("custom-build", "file", listOf("cb")),
        ))
        assertEquals("wt", matchingSlashCommands(commands, "WORKT").single().name)
        val custom = matchingSlashCommands(commands, "cb").single()
        assertEquals("custom-build", custom.name)
        assertNull(custom.description)
        assertTrue(matchingSlashCommands(commands, "no-such-command").isEmpty())
    }
}
