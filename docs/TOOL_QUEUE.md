# Tool queue (how multiple tools are handled)

When the AI asks for several tools in one reply (for example: list files, read a file, search):

1. **Run the first tool**
2. **Put the rest in a line (queue)**
3. **Run the next one after the first finishes**
4. **Repeat until the line is empty**
5. **Then ask the AI again** with all the results

Nothing is skipped. Nothing runs at the same time. The app does **not** fail just because the AI asked for more than one tool.

You will see phase messages like:
- `QUEUE: Model asked for 3 tools. Running list_files first; 2 waiting in line: read_file, search_project`
- `QUEUE: Running queued tool read_file (1 still waiting in line)`

## Code

This behavior is implemented in:
- `ModelGateway.kt` — keeps every tool call; first now, rest in `queued`
- `AutonomousAgent.kt` — `toolQueue` runs them one by one
- `AgentRuntime.kt` — same queue

Rebuild the app from the `Coding-Agent-queue-tools.zip` package (or the matching source) so this runs on your phone.
