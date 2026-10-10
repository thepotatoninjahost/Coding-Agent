# Tool-call sequencing (current behavior)

**Current behavior: one model tool call per response, followed by inspection of its result. There is no general multi-tool queue in the current implementation.**

The agent's prompt directs the model to return one tool call per turn. The execution loop handles the returned `ModelResponse.ToolCall`, dispatches that tool, records/inspects the outcome, and then continues the agent loop. This sequencing is intended to let the next model decision use the result of the previous action.

## Current code locations

- `app/src/main/java/com/codingagent/model/ModelTypes.kt` — defines the model response types, including a single `ToolCall` result.
- `app/src/main/java/com/codingagent/model/AgentModelProtocol.kt` — defines the tool catalog and schemas sent to the model.
- `app/src/main/java/com/codingagent/agent/AutonomousAgent.kt` — runs the agent loop and routes returned tool calls through the outcome handler.
- `app/src/main/java/com/codingagent/agent/ToolCallOutcomeHandler.kt` — handles tool results and decides whether the loop continues or stops.
- `app/src/main/java/com/codingagent/agent/AgentToolDispatch.kt` — executes one named tool and returns its result.
- `app/src/main/java/com/codingagent/agent/AgentRuntime.kt` — defines shared task outcome types; it is not a tool queue or a second execution loop.

## What this does not guarantee

- The model may still return malformed or unsupported output; parsing and policy checks can reject it.
- A tool can fail, time out, be cancelled, or return incomplete evidence.
- Sequential tool calls do not mean that every planned action will succeed or that the overall task is correct.
- Do not rely on old queue status strings or the former `Coding-Agent-queue-tools.zip` package as evidence of the current implementation.

When changing tool sequencing, update the model protocol, agent loop, outcome handler, tests, and this document together. Verify behavior against the exact source revision and CI run.
