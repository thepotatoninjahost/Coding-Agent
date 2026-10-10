# Model instructions and provider data

Coding Agent runs on an Android device, but when a remote model is configured, the app sends model requests to the configured endpoint. “Runs on the phone” does not mean all model processing stays on the phone.

## Current system-prompt behavior

The active agent request path uses the built-in `AgentModelProtocol.SYSTEM` prompt and builds task-specific instructions through `AgentPrompt`. The `ModelSettings` data class contains a `systemPrompt` field and an `effectiveSystemPrompt()` helper, but the current settings dialog does not expose a system-prompt editor, and code search found no call site for `effectiveSystemPrompt()`. **Do not assume custom system-prompt editing works in the current app.** Treat it as an unwired field until an implementation and tests demonstrate otherwise.

The settings dialog currently exposes the remote base URL, model name, API key, and optional extra HTTP headers. Although `ModelSettings` also contains a fallback-model field, the current dialog does not expose it.

## Context that can be sent to the provider

Depending on the request and agent turn, a remote model request can include:

- The built-in system prompt and task-specific agent instructions
- The owner's request and task-intake details
- Conversation/transcript messages used by the agent loop
- Tool definitions offered for that turn
- Project-map/evidence text, research evidence, and results from previous tool calls

The exact context varies by task and turn. Do not assume the provider receives only the literal message typed in the chat.

## Data and control boundaries

- The remote provider processes the content actually included in each request. Avoid sending secrets or private project files unless you intend to disclose them to that provider.
- Storing a setting or API key on the device does not mean the provider never receives prompt text or tool results.
- The provider does not directly execute local tools. The app constructs each request, chooses which tools to offer for a turn, and dispatches returned tool calls through its own code and policy checks.
- Do not assume an external assistant, a model provider, or a tool can edit settings unless that capability is explicitly implemented in the current source.
- Verify this document against the current request builder, settings UI, and tests after changing prompt configuration.

This document describes source-level behavior, not a guarantee of provider privacy, app security, or correct model behavior.
