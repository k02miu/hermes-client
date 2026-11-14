package io.github.k02miu.hermesmobile.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelAndPinTest {
    private fun user(key: String) = TranscriptRow.Single(ChatItem.User(key, "prompt $key"))
    private fun answer(key: String) = TranscriptRow.Single(ChatItem.Assistant(key, "answer"))

    @Test
    fun thePinnedPromptIsTheNearestOneAtOrBeforeTheTopRow() {
        val rows = listOf(user("u1"), answer("a1"), answer("a2"), user("u2"), answer("a3"))
        assertEquals(0, pinnedPromptIndex(rows, firstRow = 2)) // reading the first turn's answer
        assertEquals(3, pinnedPromptIndex(rows, firstRow = 4)) // scrolled into the second turn
        assertEquals(3, pinnedPromptIndex(rows, firstRow = 3)) // the prompt itself is the top row
    }

    @Test
    fun whatHermesInjectedIsNeverPinned() {
        val notice = TranscriptRow.Single(ChatItem.User("n", "[IMPORTANT: Background process ended]", injected = true))
        val rows = listOf(user("u1"), answer("a1"), notice, answer("a2"))
        assertEquals(0, pinnedPromptIndex(rows, firstRow = 3)) // the answer after the notice still belongs to u1
        assertEquals(0, pinnedPromptIndex(rows, firstRow = 2))
        assertNull(pinnedPromptIndex(listOf(notice, answer("a")), firstRow = 1))
    }

    @Test
    fun nothingIsPinnedBeforeTheFirstPromptOrOutsideTheList() {
        val rows = listOf(answer("greeting"), user("u1"))
        assertNull(pinnedPromptIndex(rows, firstRow = 0))
        assertNull(pinnedPromptIndex(rows, firstRow = 2))
        assertNull(pinnedPromptIndex(rows, firstRow = -1))
        assertNull(pinnedPromptIndex(emptyList(), firstRow = 0))
    }

    @Test
    fun thePickerListsOnlySignedInProvidersAndModelsThatCanBeChosen() {
        val options = parseModelOptions(Json.parseToJsonElement("""
            {"model":"m2","provider":"b","providers":[
              {"slug":"a","name":"A","authenticated":false,"models":["x"]},
              {"slug":"b","name":"B","authenticated":true,"models":["m1","m2","m3"],"unavailable_models":["m3"],"featured_models":["m2","m3"]},
              {"slug":"c","name":"C","authenticated":true,"models":["gone"],"unavailable_models":["gone"]}
            ]}""").jsonObject)
        assertEquals("m2" to "b", options.model to options.provider)
        assertEquals(listOf(ModelProvider("b", "B", listOf("m1", "m2"), featured = listOf("m2"))), options.providers)
    }

    @Test
    fun anEmptyOrOddAnswerGivesAnEmptyPicker() {
        assertEquals(ModelOptions(emptyList(), "", ""), parseModelOptions(Json.parseToJsonElement("{}").jsonObject))
        assertEquals(emptyList<ModelProvider>(), parseModelOptions(Json.parseToJsonElement("""{"providers":[1,{"authenticated":true,"models":["x"]}]}""").jsonObject).providers)
    }
}
