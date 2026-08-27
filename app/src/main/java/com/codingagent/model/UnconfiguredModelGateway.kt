package com.codingagent.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import com.codingagent.intake.TaskIntent

class UnconfiguredModelGateway : ModelGateway {
    override fun complete(request: ModelRequest): ModelResponse = ModelResponse.Failure("No model gateway is configured")
}

