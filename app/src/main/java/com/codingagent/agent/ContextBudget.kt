package com.codingagent.agent

import com.codingagent.model.AgentModelProtocol
import com.codingagent.model.ModelCaps
import com.codingagent.model.ModelMessage
import com.codingagent.model.OutputBudget

/**
 * ONE JOB: Keep one model request small enough for a free helper's per-minute limit.
 * The full system instructions stay in AgentModelProtocol. This only changes what is sent.
 */
object ContextBudget {
    const val TARGET_TOKENS = 2600

    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    private val COMPACT_SYSTEM = """
        You are a Coding-Agent on the owner's phone. The owner does not know computer jargon.
        Use plain, everyday language. Never use the words agentic, agentic loop, checksum, or checksum-backed rollback.
        If you must use a technical word, explain it in the same sentence.

        Make exactly one tool call per turn and include a purpose field. Read real files. Never invent paths, file contents, or test results.
        Code changes (create_file, replace_text) only STAGE a proposal. The owner must approve twice. Never claim a change was applied until then.
        When the phone must run the program, write JavaScript. Run it after you write it. If the run fails, fix it. If you did not run it, say you have not run it. Do not say the result is correct unless a run showed that.
        Verification honesty: a static unfinished-work marker scan is not a substitute for a real run. Never claim it passed when it was not run.
        Do not invoke git, find, gradle, or project-local executables. Use the project tools for files.
        There is no arbitrary two-or-three-result stopping limit. Do not stop at a plan. Continue until the goal is met, you are blocked, or the owner must decide.
    """.trimIndent()

    fun systemForRequest(configured: String): String {
        val extra = when {
            configured == AgentModelProtocol.DEFAULT_SYSTEM -> ""
            configured.startsWith(AgentModelProtocol.DEFAULT_SYSTEM) ->
                configured.removePrefix(AgentModelProtocol.DEFAULT_SYSTEM)
            else -> null
        }
        if (extra != null) return COMPACT_SYSTEM + extra
        if (estimateTokens(configured) > 700) return COMPACT_SYSTEM
        return configured
    }

    data class Fitted(
        val system: String,
        val user: String,
        val transcript: List<ModelMessage>
    )

    fun fit(
        system: String,
        user: String,
        transcript: List<ModelMessage>,
        targetTokens: Int = TARGET_TOKENS
    ): Fitted {
        var messages = transcript.mapIndexed { index, message ->
            val age = transcript.size - index
            when {
                message.role == "tool" && age > 6 ->
                    message.copy(content = "[old output cleared; re-run if needed]")
                message.content.length > 10_000 -> message.copy(content = headTail(message.content))
                else -> message
            }
        }
        var prompt = shortenRules(user)
        var guard = 0
        while (overBudget(system, prompt, messages, targetTokens) && guard < 16) {
            guard++
            val groups = TranscriptCompactor.groupsOf(messages)
            when {
                groups.size > 2 -> messages = TranscriptCompactor.compact(messages, groups.size - 1)
                prompt.length > 900 -> prompt = shrinkEvidence(prompt, (prompt.length * 2 / 3).coerceAtLeast(800))
                groups.size > 1 -> {
                    val next = TranscriptCompactor.compact(messages, 1)
                    if (next == messages) break
                    messages = next
                }
                else -> break
            }
        }
        return Fitted(system, prompt, messages)
    }

    fun shrinkEvidence(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val marker = "Evidence so far:"
        val at = text.lastIndexOf(marker)
        if (at < 0) return "Earlier text was shortened so this request fits the helper.\n" + text.takeLast(maxChars)
        val head = text.substring(0, at + marker.length)
        val evidence = text.substring(at + marker.length)
        val room = (maxChars - head.length - 80).coerceAtLeast(200)
        if (evidence.length <= room) return text
        return head + "\nEarlier notes were shortened. Call the tool again if you need them.\n" + evidence.takeLast(room)
    }

    private fun shortenRules(user: String): String {
        val start = user.indexOf("Operating rules for this turn:")
        if (start < 0) return user
        val end = listOf(user.indexOf("Lessons from recent runs"), user.indexOf("Evidence so far:"))
            .filter { it >= 0 }
            .minOrNull() ?: return user
        if (end <= start) return user
        val short = """
            Operating rules for this turn:
            Exactly one tool call this turn. Include a purpose field.
            Verify meaningful milestones. static marker scanning alone is not proof.
            This work can include coordinated multi-file changes, new tools, and self-modification.
            Do not stop merely because a file was read.
            Keep personal logs. Use plain everyday English. Do not say the code works unless you ran it.
            Before you finish a new file, check that it is not cut off, then review at least two examples. If it reads text, include more than one line. Fix it if the check fails.
        """.trimIndent() + "\n\n"
        return user.substring(0, start) + short + user.substring(end)
    }

    fun inputTarget(): Int = ModelCaps.inputBudget("", OutputBudget.forModel(""))

    fun condense(messages: List<ModelMessage>): List<ModelMessage> {
        if (messages.size <= 4) return messages
        val note = ModelMessage(
            "user",
            "Earlier steps were shortened so the helper can keep going. The recent steps are still here. Re-run a tool if you need an old result."
        )
        return messages.take(2) + note + messages.takeLast(messages.size / 2)
    }

    fun headTail(text: String, limit: Int = 10_000): String {
        if (text.length <= limit) return text
        val keep = limit / 2
        val elided = text.length - (keep * 2)
        return text.take(keep) + "\n[$elided characters elided. Ask for a narrower part if you need the middle.]\n" + text.takeLast(keep)
    }

    private fun shrinkBlock(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n[Earlier tool text was shortened. Call the tool again if you need the rest.]"
    }

    private fun overBudget(system: String, user: String, messages: List<ModelMessage>, targetTokens: Int): Boolean {
        val tokens = estimateTokens(system) + estimateTokens(user) + messages.sumOf { estimateTokens(it.content) }
        return tokens > targetTokens
    }
}
