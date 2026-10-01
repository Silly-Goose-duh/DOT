package com.dot.agent.llm

import kotlinx.coroutines.delay

/**
 * Test double. Returns a canned reply, a typed failure, or throws — the three
 * shapes a real provider can produce. Never touches the network.
 */
class FakeModelProvider(
    override val id: String = "fake",
    override val displayName: String = "Fake",
    private val response: () -> ModelReply = { ModelReply.Failure(ModelErrorCodes.NOT_CONFIGURED) },
) : ModelProvider {
    var lastRequest: ModelRequest? = null
        private set

    /** When set, the provider sleeps past the caller's timeout. */
    var delayMs: Long = 0

    override suspend fun complete(request: ModelRequest): ModelReply {
        lastRequest = request
        if (delayMs > 0) delay(delayMs)
        return response()
    }
}
