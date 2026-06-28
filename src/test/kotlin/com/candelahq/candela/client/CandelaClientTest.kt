package com.candelahq.candela.client

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CandelaClient] — suspend functions, caching, health checks, parsing.
 * Uses MockWebServer for realistic HTTP behavior.
 */
class CandelaClientTest {
    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    // ── Health Check ─────────────────────────────────────────────────────

    @Test
    fun `isAlive returns true on 200`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
            val client = CandelaClient(baseUrl)
            assertTrue(client.isAlive())
        }

    @Test
    fun `isAlive returns true on 204`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(204))
            val client = CandelaClient(baseUrl)
            assertTrue(client.isAlive())
        }

    @Test
    fun `isAlive returns false on 500`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            val client = CandelaClient(baseUrl)
            assertFalse(client.isAlive())
        }

    @Test
    fun `isAlive returns false on connection error`() =
        runTest {
            server.shutdown()
            val client = CandelaClient("http://127.0.0.1:${server.port}")
            assertFalse(client.isAlive())
        }

    @Test
    fun `isAlive caches positive result`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
            val client = CandelaClient(baseUrl)

            assertTrue(client.isAlive()) // First call hits server
            assertTrue(client.isAlive()) // Second call should use cache

            assertEquals(1, server.requestCount, "Should only make one HTTP request")
        }

    @Test
    fun `resetHealth clears cached health`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
            val client = CandelaClient(baseUrl)

            assertTrue(client.isAlive())
            client.resetHealth()
            assertTrue(client.isAlive())

            assertEquals(2, server.requestCount, "Should make two requests after reset")
        }

    @Test
    fun `isAlive sends GET to healthz endpoint`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))
            val client = CandelaClient(baseUrl)
            client.isAlive()

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/healthz", request.path)
        }

    // ── getDashboardData ─────────────────────────────────────────────────

    @Test
    fun `getDashboardData returns null when server offline`() =
        runTest {
            server.shutdown()
            val client = CandelaClient("http://127.0.0.1:${server.port}")
            assertNull(client.getDashboardData())
        }

    @Test
    fun `getDashboardData parses consolidated response`() =
        runTest {
            // Health check
            server.enqueue(MockResponse().setResponseCode(200))
            // Dashboard data
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "summary": {
                                "total_input_tokens": 1000,
                                "total_output_tokens": 500,
                                "total_cost_usd": 0.42,
                                "total_llm_calls": 10
                            },
                            "models": [
                                {
                                    "model": "gpt-4",
                                    "provider": "openai",
                                    "input_tokens": 800,
                                    "output_tokens": 400,
                                    "cost_usd": 0.35,
                                    "call_count": 8,
                                    "cache_read_tokens": 100,
                                    "cache_creation_tokens": 50
                                }
                            ],
                            "budget_context": {
                                "budget": {
                                    "limit_usd": 10.0,
                                    "spent_usd": 4.2
                                },
                                "total_remaining_usd": 5.8,
                                "active_grants": []
                            }
                        }
                        """.trimIndent(),
                    ),
            )

            val client = CandelaClient(baseUrl)
            val data = client.getDashboardData()

            assertNotNull(data)
            assertEquals(1500, data.usage.totalTokens)
            assertEquals(1000, data.usage.inputTokens)
            assertEquals(500, data.usage.outputTokens)
            assertEquals(0.42, data.usage.totalCostUsd, 0.001)
            assertEquals(10, data.usage.requestCount)

            assertEquals(1, data.models.size)
            assertEquals("gpt-4", data.models[0].model)
            assertEquals("openai", data.models[0].provider)

            assertNotNull(data.budget)
            assertEquals(10.0, data.budget!!.limitUsd, 0.001)
            assertEquals(4.2, data.budget!!.spentUsd, 0.001)
            assertEquals(5.8, data.budget!!.remainingUsd, 0.001)
            assertFalse(data.budget!!.isNearLimit)
            assertFalse(data.budget!!.isExhausted)
        }

    @Test
    fun `getDashboardData returns cached data within TTL`() =
        runTest {
            // Health check
            server.enqueue(MockResponse().setResponseCode(200))
            // Dashboard data
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "summary": {"total_input_tokens": 100, "total_output_tokens": 50, "total_cost_usd": 0.1, "total_llm_calls": 1}
                        }
                        """.trimIndent(),
                    ),
            )

            val client = CandelaClient(baseUrl, cacheTtlMs = 60_000)
            val data1 = client.getDashboardData()
            assertNotNull(data1)

            // Second call should use cache (no more enqueued responses needed)
            val data2 = client.getDashboardData()
            assertNotNull(data2)
            assertEquals(data1.usage.totalTokens, data2.usage.totalTokens)

            // 1 health check + 1 dashboard = 2 requests total
            assertEquals(2, server.requestCount, "Second call should use cache")
        }

    @Test
    fun `invalidateCache forces fresh fetch`() =
        runTest {
            // First: health + dashboard
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"summary": {"total_input_tokens": 100, "total_output_tokens": 50}}"""),
            )
            // Second: dashboard (health is cached positive)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"summary": {"total_input_tokens": 200, "total_output_tokens": 100}}"""),
            )

            val client = CandelaClient(baseUrl, cacheTtlMs = 60_000)
            val data1 = client.getDashboardData()
            assertNotNull(data1)
            assertEquals(150, data1.usage.totalTokens)

            client.invalidateCache()
            val data2 = client.getDashboardData()
            assertNotNull(data2)
            assertEquals(300, data2.usage.totalTokens)
        }

    @Test
    fun `getDashboardData falls back to legacy fanout on 404`() =
        runTest {
            // Health check
            server.enqueue(MockResponse().setResponseCode(200))
            // Consolidated endpoint returns 404 (not supported)
            server.enqueue(MockResponse().setResponseCode(404))
            // Legacy summary endpoint
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"total_input_tokens": 50, "total_output_tokens": 25, "total_cost_usd": 0.05, "total_llm_calls": 2}"""),
            )
            // Legacy budget endpoint
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"budget": {"limit_usd": 5.0, "spent_usd": 1.0}, "total_remaining_usd": 4.0, "active_grants": []}"""),
            )

            val client = CandelaClient(baseUrl)
            val data = client.getDashboardData()

            assertNotNull(data)
            assertEquals(75, data.usage.totalTokens)
            assertNotNull(data.budget)
            assertEquals(5.0, data.budget!!.limitUsd, 0.001)
        }

    @Test
    fun `getDashboardData returns empty data when all endpoints return errors`() =
        runTest {
            // Health check
            server.enqueue(MockResponse().setResponseCode(200))
            // Consolidated fails
            server.enqueue(MockResponse().setResponseCode(500))
            // Legacy summary fails with 500 (still parsed as empty)
            server.enqueue(MockResponse().setResponseCode(500))
            // Legacy budget fails with 500
            server.enqueue(MockResponse().setResponseCode(500))

            val client = CandelaClient(baseUrl)
            val data = client.getDashboardData()

            // Legacy fanout returns an empty DashboardData for 500 responses
            // (the HTTP calls succeed at the transport level, just no useful data)
            assertNotNull(data)
            assertEquals(0, data.usage.totalTokens)
            assertNull(data.budget)
        }

    @Test
    fun `getDashboardData handles response without budget`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "summary": {"total_input_tokens": 100, "total_output_tokens": 50, "total_cost_usd": 0.1}
                        }
                        """.trimIndent(),
                    ),
            )

            val client = CandelaClient(baseUrl)
            val data = client.getDashboardData()

            assertNotNull(data)
            assertNull(data.budget, "Budget should be null when not in response")
            assertTrue(data.activeGrants.isEmpty())
        }

    @Test
    fun `getDashboardData detects near-limit budget`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "summary": {"total_input_tokens": 0, "total_output_tokens": 0},
                            "budget_context": {
                                "budget": {"limit_usd": 10.0, "spent_usd": 9.0},
                                "total_remaining_usd": 1.0
                            }
                        }
                        """.trimIndent(),
                    ),
            )

            val client = CandelaClient(baseUrl)
            val data = client.getDashboardData()

            assertNotNull(data?.budget)
            assertTrue(data!!.budget!!.isNearLimit, "90% spent should be near limit")
            assertFalse(data.budget!!.isExhausted, "90% spent should not be exhausted")
        }

    @Test
    fun `getDashboardData detects exhausted budget`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "summary": {"total_input_tokens": 0, "total_output_tokens": 0},
                            "budget_context": {
                                "budget": {"limit_usd": 10.0, "spent_usd": 10.0},
                                "total_remaining_usd": 0.0
                            }
                        }
                        """.trimIndent(),
                    ),
            )

            val client = CandelaClient(baseUrl)
            val data = client.getDashboardData()

            assertNotNull(data?.budget)
            assertTrue(data!!.budget!!.isNearLimit)
            assertTrue(data.budget!!.isExhausted, "100% spent should be exhausted")
            assertEquals(0.0, data.budget!!.remainingUsd, 0.001)
        }
}
