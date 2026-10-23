package dev.wawlink.ui

import androidx.compose.runtime.saveable.SaverScope
import dev.wawlink.protocol.AskAnswer
import dev.wawlink.protocol.AskOption
import dev.wawlink.protocol.AskQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskDraftStateTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    private fun restore(state: AskRequestDraft): AskRequestDraft {
        val saved = with(AskRequestDraftSaver) { scope.save(state) }
        return requireNotNull(AskRequestDraftSaver.restore(requireNotNull(saved)))
    }

    @Test fun leavingAndReturningPreservesEachQuestionAndTheActiveNativeId() {
        val choice = AskQuestion("native-choice", "选择方式", options = listOf(AskOption("方案", preview = "预览")))
        val input = AskQuestion("native-input", "补充说明")
        val choiceDraft = AskQuestionDraft().select(choice, "方案").togglePreview("方案", selected = true)
        val state = AskRequestDraft()
            .edit(choice.id, choiceDraft)
            .edit(input.id, AskQuestionDraft().editCustom("保留的输入"))
            .show(input.id)
        val returned = restore(state)

        assertEquals(1, returned.page(listOf(choice, input)))
        assertEquals(0, returned.page(listOf(input, choice)))
        assertEquals("保留的输入", returned.question(input.id).customInput)
        assertEquals(listOf("方案"), returned.question(choice.id).answer(choice)?.selectedOptions)
        assertFalse(returned.question(choice.id).previewOpen("方案", selected = true))
        assertEquals(state, restore(returned.show(choice.id)).show(input.id))
    }

    @Test fun reviewCannotProducePartialBatchAndFindsFirstMissingInNativeOrder() {
        val first = AskQuestion("first", "第一题")
        val second = AskQuestion("second", "第二题")
        val state = AskRequestDraft(reviewing = true)
            .edit(second.id, AskQuestionDraft().editCustom("第二个答案"))
        assertNull(state.answers(listOf(first, second)))
        assertEquals(first.id, state.firstUnanswered(listOf(first, second)))
        val complete = state.edit(first.id, AskQuestionDraft().editCustom("第一个答案"))
        assertEquals(listOf(
            AskAnswer(first.id, customInput = "第一个答案"),
            AskAnswer(second.id, customInput = "第二个答案"),
        ), complete.answers(listOf(first, second)))
        assertTrue(restore(complete).reviewing)
    }

    @Test fun singleChoiceAndCustomSwitchWithoutSubmittingTheInactiveDraft() {
        val question = AskQuestion("single", "选择", options = listOf(AskOption("A"), AskOption("B")))
        val custom = AskQuestionDraft().select(question, "A").editCustom("  自定义  ")
        assertEquals(AskAnswer(question.id, customInput = "自定义"), custom.answer(question))
        val selected = custom.select(question, "B")
        assertEquals(AskAnswer(question.id, listOf("B")), selected.answer(question))
        assertEquals("  自定义  ", selected.customInput)
        assertNull(selected.editCustom(" \n ").answer(question))
    }

    @Test fun multiselectKeepsCustomAndNormalizesChoicesToNativeOrder() {
        val question = AskQuestion("multi", "选择", options = listOf(AskOption("A"), AskOption("B")), multi = true)
        val draft = AskQuestionDraft().select(question, "B").editCustom("补充").select(question, "A")
        assertEquals(AskAnswer(question.id, listOf("A", "B"), "补充"), draft.answer(question))
        assertEquals(AskAnswer(question.id, listOf("A"), "补充"), draft.select(question, "B").answer(question))
    }

    @Test fun noChoiceMultiMustBeExplicitAndRemainsConfirmedAfterRestoration() {
        val question = AskQuestion("optional", "选择", options = listOf(AskOption("A")), multi = true)
        assertNull(AskQuestionDraft().answer(question))
        val confirmed = AskQuestionDraft().confirmEmpty()
        assertEquals(AskAnswer(question.id), confirmed.answer(question))
        val restored = restore(AskRequestDraft().edit(question.id, confirmed))
        assertEquals(listOf(AskAnswer(question.id)), restored.answers(listOf(question)))
        val changedThenCleared = confirmed.select(question, "A").select(question, "A")
        assertNull(changedThenCleared.answer(question))
    }

    @Test fun previewInspectionDoesNotAnswerAnUntouchedQuestion() {
        val question = AskQuestion("preview", "选择", options = listOf(AskOption("A", preview = "预览")))
        val inspected = AskQuestionDraft().togglePreview("A", selected = false)
        assertTrue(inspected.previewOpen("A", selected = false))
        assertNull(inspected.answer(question))
        assertFalse(inspected.togglePreview("A", selected = false).previewOpen("A", selected = false))
    }
}
