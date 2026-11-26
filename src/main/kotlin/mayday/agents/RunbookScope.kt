package mayday.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mayday.identity.Principal
import mayday.runbooks.Runbook

/**
 * The tools of an agent that follows an approved runbook: the read tools, the tools that the runbook allows, and [RAISE].
 * A write call after the runbook was changed or revoked, or a call of any other tool, is refused with `forbidden` and raises the alert to a person through [raise].
 */
class RunbookScope(
    private val inner: ToolCatalog,
    private val runbook: Runbook,
    private val stillApproved: suspend () -> Boolean,
    private val raise: suspend (String) -> Unit,
) : ToolCatalog {
    override suspend fun tools(principal: Principal, internal: Boolean): List<ToolSpec> =
        inner.tools(principal, internal).filter { runbook.allows(it.name) } + RAISE_SPEC

    override suspend fun call(principal: Principal, name: String, args: JsonObject, internal: Boolean): ToolOutcome {
        if (name == RAISE) {
            val reason = (args["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty().ifEmpty { "The runbook does not cover this case." }
            raise(reason)
            return ToolOutcome("A person is paged. Stop your work on the runbook and give your report.")
        }
        if (!runbook.allows(name)) {
            raise("The agent tried '$name', which the runbook '${runbook.title}' does not allow.")
            return ToolOutcome("forbidden: '$name' is not in the runbook. A person is paged. Stop and give your report.", true, "forbidden")
        }
        if (name !in Runbook.READ_TOOLS && !stillApproved()) {
            raise("The runbook '${runbook.title}' was changed or revoked during the run.")
            return ToolOutcome("forbidden: the runbook is no longer approved. A person is paged. Stop and give your report.", true, "forbidden")
        }
        return inner.call(principal, name, args, internal)
    }

    companion object {
        const val RAISE = "raise_to_human"
        private val RAISE_SPEC = ToolSpec(RAISE,
            "Raise the alert to a person: a step of the runbook failed, or the runbook does not cover the case. A person is paged.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("reason") { put("type", "string"); put("description", "What failed or what the runbook does not cover") } }
                putJsonArray("required") { add("reason") }
                put("additionalProperties", false)
            })
    }
}
