package com.candelahq.candela.client

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Gson deserialization of chat models — verifies null safety
 * for the Gson-bypasses-Kotlin-nullability issue.
 */
class ModelsTest {
    private val gson = Gson()

    @Test
    fun `ChatCompletionChunk deserializes normally`() {
        val json =
            """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion.chunk",
                "created": 1700000000,
                "model": "gpt-4",
                "choices": [
                    {
                        "index": 0,
                        "delta": {"role": "assistant", "content": "Hello"},
                        "finish_reason": null
                    }
                ]
            }
            """.trimIndent()

        val chunk = gson.fromJson(json, ChatCompletionChunk::class.java)
        assertNotNull(chunk)
        assertEquals("chatcmpl-123", chunk.id)
        assertEquals("gpt-4", chunk.model)
        assertNotNull(chunk.choices)
        assertEquals(1, chunk.choices!!.size)
        assertEquals(
            "Hello",
            chunk.choices!!
                .first()
                .delta
                ?.content,
        )
    }

    @Test
    fun `ChatCompletionChunk handles null choices from Gson`() {
        // Gson will set choices to null despite Kotlin non-null declaration
        val json = """{"id": "test", "choices": null}"""
        val chunk = gson.fromJson(json, ChatCompletionChunk::class.java)
        assertNotNull(chunk)
        assertNull(chunk.choices, "choices should be null when JSON has null")
    }

    @Test
    fun `ChatCompletionChunk handles missing choices field`() {
        val json = """{"id": "test", "model": "gpt-4"}"""
        val chunk = gson.fromJson(json, ChatCompletionChunk::class.java)
        assertNotNull(chunk)
        // Gson will either use the default (emptyList) or set to null
        // Either way, safe-call should work
        val choice = chunk.choices?.firstOrNull()
        assertNull(choice, "Should safely return null for missing choices")
    }

    @Test
    fun `ChatCompletionChunk handles empty choices array`() {
        val json = """{"id": "test", "choices": []}"""
        val chunk = gson.fromJson(json, ChatCompletionChunk::class.java)
        assertNotNull(chunk)
        assertNotNull(chunk.choices)
        assertTrue(chunk.choices!!.isEmpty())
    }

    @Test
    fun `ChunkDelta handles null content`() {
        val json = """{"role": "assistant", "content": null}"""
        val delta = gson.fromJson(json, ChunkDelta::class.java)
        assertNotNull(delta)
        assertEquals("assistant", delta.role)
        assertNull(delta.content)
    }

    @Test
    fun `ChunkDelta handles missing content`() {
        val json = """{"role": "assistant"}"""
        val delta = gson.fromJson(json, ChunkDelta::class.java)
        assertNotNull(delta)
        assertEquals("assistant", delta.role)
        assertNull(delta.content)
    }

    @Test
    fun `ChunkUsage deserializes token counts`() {
        val json = """{ "prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}"""
        val usage = gson.fromJson(json, ChunkUsage::class.java)
        assertNotNull(usage)
        assertEquals(10, usage.promptTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(30, usage.totalTokens)
    }

    @Test
    fun `ChatCompletionChunk with usage in final chunk`() {
        val json =
            """
            {
                "id": "test",
                "choices": [],
                "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
            }
            """.trimIndent()
        val chunk = gson.fromJson(json, ChatCompletionChunk::class.java)
        assertNotNull(chunk.usage)
        assertEquals(150, chunk.usage!!.totalTokens)
    }

    @Test
    fun `ModelsResponse deserializes model list`() {
        val json =
            """
            {
                "object": "list",
                "data": [
                    {"id": "gpt-4", "object": "model", "owned_by": "openai"},
                    {"id": "claude-3", "object": "model", "owned_by": "anthropic"}
                ]
            }
            """.trimIndent()
        val response = gson.fromJson(json, ModelsResponse::class.java)
        assertEquals(2, response.data.size)
        assertEquals("gpt-4", response.data[0].id)
        assertEquals("claude-3", response.data[1].id)
    }

    @Test
    fun `ChatMessage data class`() {
        val msg = ChatMessage(role = "user", content = "hello")
        assertEquals("user", msg.role)
        assertEquals("hello", msg.content)
    }
}
