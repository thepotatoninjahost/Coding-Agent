# Tool-call handling (current implementation)

The current model response type supports one tool call per model response. The agent executes that call, inspects the result, and then asks the model for the next step.

## Multiple tool calls in one response

When a reply contains several tool calls, the first call is run and the task continues. The reply notes that any later call must be sent on the next turn. The task does not fail just because more than one call arrived.

This behavior is covered by `ModelGatewayTest.multipleToolCallsAreRejectedRatherThanSilentlyDroppingCalls` and the streamed equivalent, which now expect the first call to be kept.

## Source of truth

- `app/src/main/java/com/codingagent/model/ModelTypes.kt` defines a single `ModelResponse.ToolCall`.
- `app/src/main/java/com/codingagent/model/JsonModelResponseParser.kt` parses one tool call and rejects multiple calls.
- `app/src/main/java/com/codingagent/model/RemoteHttpGateway.kt` parses streamed tool-call deltas.
- `app/src/test/java/com/codingagent/core/ModelGatewayTest.kt` covers rejection of multiple non-streaming calls.

This document describes the current source behavior; it does not promise functionality that has not been implemented and tested.
