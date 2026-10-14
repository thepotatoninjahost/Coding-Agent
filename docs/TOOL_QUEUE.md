# Tool-call handling (current implementation)

The current model response type supports one tool call per model response. The agent executes that call, inspects the result, and then asks the model for the next step.

## Multiple tool calls in one response

Multiple tool calls are **not currently queued**. The non-streaming response parser rejects responses containing multiple tool calls. The streaming parser currently assembles only the first indexed tool call. Do not rely on a provider returning several calls in one response.

This behavior is covered by `ModelGatewayTest.multipleToolCallsAreRejectedRatherThanSilentlyDroppingCalls`. Support for multi-call queuing would require a deliberate implementation change in the model response types, streaming and non-streaming parsers, and agent execution loop, plus tests for ordering, partial failures, cancellation, and authorization.

## Source of truth

- `app/src/main/java/com/codingagent/model/ModelTypes.kt` defines a single `ModelResponse.ToolCall`.
- `app/src/main/java/com/codingagent/model/JsonModelResponseParser.kt` parses one tool call and rejects multiple calls.
- `app/src/main/java/com/codingagent/model/RemoteHttpGateway.kt` parses streamed tool-call deltas.
- `app/src/test/java/com/codingagent/core/ModelGatewayTest.kt` covers rejection of multiple non-streaming calls.

This document describes the current source behavior; it does not promise functionality that has not been implemented and tested.
