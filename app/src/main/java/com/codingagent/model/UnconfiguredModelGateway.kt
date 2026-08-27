package com.codingagent.model

class UnconfiguredModelGateway : ModelGateway {
    override fun complete(request: ModelRequest): ModelResponse =
        ModelResponse.Failure("No model gateway is configured")
}
