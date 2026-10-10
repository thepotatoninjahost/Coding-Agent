# Model instructions and data sent to the provider

Coding Agent runs locally on the Android device, but when a remote model is configured, the app sends model requests to the endpoint configured in Model settings. Do not interpret “runs locally” as “all model processing stays on the phone.”

## System prompt

In **Model settings**, the **Instruction sheet (system prompt)** field lets the owner customize the system-level instruction text used by the app. If the field is blank, the app falls back to the built-in default system prompt. The saved settings are stored on the device through the app's local settings/secret-storage code.

The app also builds task-specific instructions and context. Depending on the current request and turn, model requests can include:

- The configured or built-in system prompt
- The owner's current request and task-intake details
- Conversation/transcript messages used by the agent loop
- Tool definitions available for that turn
- Relevant project-map/evidence text, research evidence, and results from previous tool calls
- Follow-up instructions used by the agent to control sequencing, verification, or recovery

The exact context varies by task and turn; do not assume the remote provider receives only the literal message typed in the chat.

## Data and control boundaries

- The remote provider processes the content actually included in each request. Avoid sending secrets or private project files unless you intend to disclose them to that provider.
- The app's storage of a prompt or API key on the device does not mean the provider never receives prompt text or tool results.
- The app controls the request construction and tool execution path; the provider does not directly execute local tools. The app decides which tools are offered for a turn and dispatches returned tool calls through its own code and policy checks.
- Do not assume an external assistant, a model provider, or a tool can edit this setting unless that capability is explicitly implemented in the current source.
- UI labels and implementation can change. Verify the current settings screen and `ModelSettings.effectiveSystemPrompt()` before relying on this document.

This document describes the current source-level design, not a guarantee of provider privacy, app security, or correct model behavior.
