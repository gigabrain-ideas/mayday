package mayday.agents

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import mayday.identity.OrgRole
import mayday.identity.Principal
import mayday.shared.Actor
import mayday.shared.Severity
import mayday.shared.Ts

@Serializable
enum class AgentKind { INTERNAL, EXTERNAL }

@Serializable
enum class Delivery { PUSH, PULL }

@Serializable
enum class AgentAction(val label: String, val writes: Boolean) {
    ANALYZE("Analyze", false),
    REMEDIATE("Remediate", true),
    PATCH("Create patch", true),
    TIMELINE("Update timeline", false),
    POSTMORTEM("Draft postmortem", false),
    RUNBOOK("Generate runbook", false),
}

@Serializable
enum class TriggerEvent { ALERT_CREATED }

@Serializable
data class AgentTrigger(
    val event: TriggerEvent = TriggerEvent.ALERT_CREATED,
    val minSeverity: Severity = Severity.SEV2,
    val teamIds: List<String> = emptyList(),
    val action: AgentAction = AgentAction.ANALYZE,
)

@Serializable
data class AgentConfig(
    val id: String,
    val name: String,
    val description: String = "",
    val kind: AgentKind,
    val delivery: Delivery = Delivery.PUSH,
    val endpointUrl: String? = null,
    val secretSealed: String = "",
    val actions: Set<AgentAction>,
    val triggers: List<AgentTrigger> = emptyList(),
    val role: OrgRole = OrgRole.VIEWER,
    val enabled: Boolean = true,
    val instructions: String = "",
    val createdAt: Ts,
    val updatedAt: Ts,
)

@Serializable
enum class RunStatus(val terminal: Boolean) {
    QUEUED(false), DISPATCHED(false), RUNNING(false), SUCCEEDED(true), FAILED(true), CANCELLED(true)
}

@Serializable
enum class RunTrigger { AUTO, MANUAL }

@Serializable
enum class SubjectKind { INCIDENT, ALERT }

@Serializable
data class AgentRun(
    val id: String,
    val agentId: String,
    val agentName: String,
    val action: AgentAction,
    val subjectKind: SubjectKind,
    val subjectId: String,
    val subjectLabel: String,
    val status: RunStatus = RunStatus.QUEUED,
    val trigger: RunTrigger,
    val requestedBy: Actor,
    val onBehalfOf: String? = null,
    val prompt: String = "",
    val output: String = "",
    val error: String = "",
    val createdAt: Ts,
    val startedAt: Ts? = null,
    val finishedAt: Ts? = null,
    val traceId: String = "",
    val version: Long = 1,
)

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val headerName: String = "Authorization",
    val credentialSealed: String = "",
    val toolAllowlist: List<String> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Ts,
    /** Send the identity headers of the LLM settings (token and custom headers) instead of the static credential. */
    val useAgentIdentity: Boolean = false,
)

@Serializable
data class LlmConfig(
    val provider: String = "anthropic",
    val model: String = "claude-opus-5-5",
    val apiKeySealed: String = "",
    val baseUrl: String = "",
    val effort: String = "high",
    val enabled: Boolean = false,
    /** Null: the provider default (anthropic = API_KEY, openai = BEARER). */
    val authStyle: AuthStyle? = null,
    val credential: CredentialMode = CredentialMode.STATIC,
    val tokenUrl: String = "",
    val issuer: String = "",
    val grant: OAuthGrant = OAuthGrant.CLIENT_CREDENTIALS,
    val clientAuth: ClientAuth = ClientAuth.BASIC,
    val clientId: String = "",
    val clientSecretSealed: String = "",
    val assertionFile: String = "",
    val tokenFile: String = "",
    val scope: String = "",
    val tokenParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
) {
    val api: LlmApi get() = LlmApi.of(provider)
    val effectiveAuthStyle: AuthStyle get() = authStyle ?: if (api == LlmApi.OPENAI) AuthStyle.BEARER else AuthStyle.API_KEY
}

interface AgentRepository {
    suspend fun get(id: String): AgentConfig?
    suspend fun list(): List<AgentConfig>
    suspend fun save(agent: AgentConfig)
    suspend fun delete(id: String)
}

interface RunRepository {
    suspend fun get(id: String): AgentRun?
    suspend fun save(run: AgentRun, expectedVersion: Long?)
    suspend fun bySubject(subjectId: String): List<AgentRun>
    suspend fun recent(limit: Int): List<AgentRun>

    /** Atomically move up to [limit] QUEUED runs of [agentId] to DISPATCHED and return them. */
    suspend fun claim(agentId: String, limit: Int, at: java.time.Instant): List<AgentRun>
    suspend fun active(): List<AgentRun>
}

interface McpServerRepository {
    suspend fun get(id: String): McpServerConfig?
    suspend fun list(): List<McpServerConfig>
    suspend fun save(server: McpServerConfig)
    suspend fun delete(id: String)
}

interface ConfigRepository {
    suspend fun llm(): LlmConfig?
    suspend fun saveLlm(config: LlmConfig)
}

// ---- LLM port (provider neutral) ----

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val write: Boolean = false,
    val admin: Boolean = false,
)

sealed interface LlmBlock {
    data class Text(val text: String) : LlmBlock
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : LlmBlock
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean) : LlmBlock
}

enum class LlmRole { USER, ASSISTANT }

/** [raw] lets a provider round-trip its native message (for example thinking blocks) without loss. */
data class LlmMessage(val role: LlmRole, val blocks: List<LlmBlock>, val raw: Any? = null)

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec>,
    val maxTokens: Long = 16000,
)

enum class StopReason { END, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

data class LlmUsage(val inputTokens: Long = 0, val outputTokens: Long = 0)

/** [model] is the model that answered, when the provider reports it. */
data class LlmResponse(val message: LlmMessage, val stopReason: StopReason, val usage: LlmUsage = LlmUsage(), val model: String = "")

/**
 * Provider contract: send [GatewayAuth.headers] on every call; on HTTP 401 call [GatewayAuth.invalidate] and retry once;
 * on HTTP 429 throw [BudgetExhausted] and never retry; other failures throw [GatewayError].
 */
interface LlmProvider {
    suspend fun complete(request: LlmRequest): LlmResponse
}

fun interface LlmProviderFactory {
    fun create(config: LlmConfig, auth: GatewayAuth): LlmProvider
}

// ---- Tools port ----

/** [code] is the domain error code of a failed call, for example forbidden or locked. */
data class ToolOutcome(val content: String, val isError: Boolean = false, val code: String? = null)

/** Tools that an agent can call. [internal] is true for the built-in agent, which bypasses the MCP write switch. */
interface ToolCatalog {
    suspend fun tools(principal: Principal, internal: Boolean): List<ToolSpec>
    suspend fun call(principal: Principal, name: String, args: JsonObject, internal: Boolean): ToolOutcome
}

// ---- Push delivery port ----

@Serializable
data class DispatchPayload(
    val event: String,
    val run: AgentRun,
    val maydayUrl: String,
    val mcpUrl: String,
    val delegatedToken: String? = null,
    val subject: JsonObject,
)

interface AgentDispatcher {
    /** Deliver a run to a push agent. Throws on a delivery failure. */
    suspend fun dispatch(agent: AgentConfig, secret: String, payload: DispatchPayload)
}
