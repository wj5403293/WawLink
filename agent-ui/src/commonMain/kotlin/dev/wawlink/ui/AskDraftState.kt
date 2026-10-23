package dev.wawlink.ui

import androidx.compose.runtime.saveable.listSaver
import dev.wawlink.protocol.AskAnswer
import dev.wawlink.protocol.AskQuestion

internal data class AskQuestionDraft(
    val selectedOptions: List<String> = emptyList(),
    val customInput: String = "",
    val customActive: Boolean = false,
    val openPreviews: List<String> = emptyList(),
    val closedPreviews: List<String> = emptyList(),
    val confirmedEmpty: Boolean = false,
) {
    fun select(question: AskQuestion, label: String): AskQuestionDraft {
        val selected = if (question.multi) {
            if (label in selectedOptions) selectedOptions - label else selectedOptions + label
        } else listOf(label)
        val previews = if (label in selected && question.options.any { it.label == label && !it.preview.isNullOrBlank() }) {
            (openPreviews + label).distinct()
        } else openPreviews
        return copy(selectedOptions = selected, customActive = false, openPreviews = previews, closedPreviews = closedPreviews - label, confirmedEmpty = false)
    }

    fun editCustom(text: String) = copy(customInput = text, customActive = true, confirmedEmpty = false)

    fun previewOpen(label: String, selected: Boolean) = label in openPreviews || selected && label !in closedPreviews

    fun togglePreview(label: String, selected: Boolean): AskQuestionDraft = if (previewOpen(label, selected)) {
        copy(openPreviews = openPreviews - label, closedPreviews = (closedPreviews + label).distinct())
    } else {
        copy(openPreviews = (openPreviews + label).distinct(), closedPreviews = closedPreviews - label)
    }

    fun confirmEmpty() = copy(selectedOptions = emptyList(), customInput = "", customActive = false, confirmedEmpty = true)

    fun answer(question: AskQuestion): AskAnswer? {
        val custom = customInput.trim().takeIf { it.isNotEmpty() && (question.multi || customActive || question.options.isEmpty()) }
        val selected = if (!question.multi && customActive) emptyList() else {
            question.options.map { it.label }.filter { it in selectedOptions }.distinct().let { if (question.multi) it else it.take(1) }
        }
        if (selected.isEmpty() && custom == null && !(question.multi && confirmedEmpty)) return null
        return AskAnswer(question.id, selected, custom)
    }
}

internal data class AskRequestDraft(
    val questionId: String? = null,
    val questions: Map<String, AskQuestionDraft> = emptyMap(),
    val reviewing: Boolean = false,
    val unansweredQuestionId: String? = null,
) {
    fun question(id: String) = questions[id] ?: AskQuestionDraft()
    fun edit(id: String, draft: AskQuestionDraft) = copy(questions = questions + (id to draft))
    fun selectOption(questions: List<AskQuestion>, id: String, label: String): AskRequestDraft {
        if (reviewing) return this
        val index = page(questions)
        val active = questions.getOrNull(index)?.takeIf { it.id == id } ?: return this
        if (active.options.none { it.label == label }) return this
        val edited = edit(id, question(id).select(active, label))
        if (active.multi) return edited
        return questions.getOrNull(index + 1)?.let { edited.show(it.id) }
            ?: edited.copy(questionId = id, reviewing = true)
    }
    fun page(questions: List<AskQuestion>): Int = questions.indexOfFirst { it.id == questionId }.coerceAtLeast(0)
    fun show(id: String) = copy(questionId = id, reviewing = false)
    fun firstUnanswered(questions: List<AskQuestion>): String? = questions.firstOrNull { question(it.id).answer(it) == null }?.id
    fun answers(questions: List<AskQuestion>): List<AskAnswer>? {
        if (questions.isEmpty()) return null
        val answers = questions.map { question(it.id).answer(it) }
        return if (answers.any { it == null }) null else answers.filterNotNull()
    }
}

// Only primitive lists cross the saveable boundary; drafts remain keyed by native question IDs.
internal val AskRequestDraftSaver = listSaver<AskRequestDraft, Any>(
    save = { state ->
        listOf(listOfNotNull(state.questionId), state.reviewing, listOfNotNull(state.unansweredQuestionId)) + state.questions.map { (id, draft) ->
            listOf(id, draft.selectedOptions, draft.customInput, draft.customActive, draft.openPreviews, draft.closedPreviews, draft.confirmedEmpty)
        }
    },
    restore = { saved ->
        AskRequestDraft(
            questionId = (saved[0] as List<*>).singleOrNull() as String?,
            reviewing = saved[1] as Boolean,
            unansweredQuestionId = (saved[2] as List<*>).singleOrNull() as String?,
            questions = saved.drop(3).associate { entry ->
                val values = entry as List<*>
                (values[0] as String) to AskQuestionDraft(
                    selectedOptions = (values[1] as List<*>).map { it as String },
                    customInput = values[2] as String,
                    customActive = values[3] as Boolean,
                    openPreviews = (values[4] as List<*>).map { it as String },
                    closedPreviews = (values[5] as List<*>).map { it as String },
                    confirmedEmpty = values[6] as Boolean,
                )
            },
        )
    },
)
