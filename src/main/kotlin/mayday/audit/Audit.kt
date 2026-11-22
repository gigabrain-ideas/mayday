package mayday.audit

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mayday.shared.Ts
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

const val AUDIT_SCHEMA = "mayday.audit.v1"

@Serializable
enum class AuditCategory {
    @SerialName("authentication") AUTHENTICATION,
    @SerialName("authorization") AUTHORIZATION,
    @SerialName("token") TOKEN,
    @SerialName("identity") IDENTITY,
    @SerialName("policy") POLICY,
    @SerialName("agent") AGENT,
    @SerialName("configuration") CONFIGURATION,
    @SerialName("system") SYSTEM,
}

@Serializable
enum class AuditOutcome {
    @SerialName("success") SUCCESS,
    @SerialName("denied") DENIED,
    @SerialName("failure") FAILURE,
}

/** A person, an agent, Mayday itself, or a caller without valid credentials. [type]: user, agent, system, anonymous. */
@Serializable
data class AuditParty(val type: String, val id: String, val name: String? = null, val email: String? = null) {
    companion object {
        val system = AuditParty("system", "system", "Mayday")
        val anonymous = AuditParty("anonymous", "anonymous")
    }
}

/** Who acts and on whose behalf. [credential]: session, api_token, delegated_token, sso_token, scim_token. */
@Serializable
data class AuditActor(
    val actor: AuditParty,
    val onBehalfOf: AuditParty? = null,
    val role: String? = null,
    val scopes: List<String> = emptyList(),
    val runId: String? = null,
    val credential: String? = null,
) {
    companion object {
        val system = AuditActor(AuditParty.system, role = "ADMIN")
        val anonymous = AuditActor(AuditParty.anonymous)
    }
}

@Serializable
data class AuditTarget(val type: String, val id: String, val name: String? = null)

/** [channel]: grpc, grpc-web, http, mcp, scim, internal-agent, internal. */
@Serializable
data class AuditSource(val channel: String, val ip: String? = null, val forwardedFor: String? = null, val userAgent: String? = null) {
    companion object {
        val internal = AuditSource("internal")
    }
}

/** What a bounded context reports. [AuditLog] adds the time, instance, sequence number and hash. */
data class AuditEntry(
    val category: AuditCategory,
    val action: String,
    val by: AuditActor,
    val outcome: AuditOutcome = AuditOutcome.SUCCESS,
    val target: AuditTarget? = null,
    val teams: List<String> = emptyList(),
    val details: Map<String, String> = emptyMap(),
    val reason: String? = null,
    val source: AuditSource? = null,
    val traceId: String? = null,
    /** An allowed read. Mayday drops it when MAYDAY_AUDIT_READS is false. */
    val read: Boolean = false,
)

/** One line of the audit stream. The schema is stable: fields are only added. */
@Serializable
data class AuditRecord(
    val schema: String = AUDIT_SCHEMA,
    val id: String,
    val time: Ts,
    val instance: String,
    val seq: Long,
    val category: AuditCategory,
    val action: String,
    val outcome: AuditOutcome,
    val reason: String? = null,
    val actor: AuditParty,
    val onBehalfOf: AuditParty? = null,
    val role: String? = null,
    val scopes: List<String> = emptyList(),
    val runId: String? = null,
    val credential: String? = null,
    val target: AuditTarget? = null,
    val teams: List<String> = emptyList(),
    val source: AuditSource,
    val traceId: String? = null,
    val details: Map<String, String> = emptyMap(),
    /** Hash of the previous record of this instance; empty for the first record. */
    val prev: String,
    /** HMAC-SHA256 over this record without [hash], with the audit key. */
    val hash: String? = null,
)

/**
 * Request facts that driving adapters put in the coroutine context. The authenticator sets [caller] when it resolves
 * the credentials, so the adapter can record the request also when the service refuses it.
 */
class AuditContext(val source: AuditSource) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuditContext>

    @Volatile var caller: AuditActor? = null
}

/** The recording port of the bounded contexts. [emit] never blocks and never throws. */
interface AuditTrail {
    fun emit(entry: AuditEntry)

    companion object {
        val none: AuditTrail = object : AuditTrail {
            override fun emit(entry: AuditEntry) = Unit
        }
    }
}

/** Records with the request source of the current coroutine. */
suspend fun AuditTrail.record(
    category: AuditCategory,
    action: String,
    by: AuditActor,
    target: AuditTarget? = null,
    details: Map<String, String> = emptyMap(),
    teams: List<String> = emptyList(),
    outcome: AuditOutcome = AuditOutcome.SUCCESS,
    reason: String? = null,
) {
    val ctx = currentCoroutineContext()[AuditContext]
    val caller = ctx?.caller
    val actor = if (by.credential == null && caller?.credential != null && caller.actor.id == by.actor.id) by.copy(credential = caller.credential) else by
    emit(AuditEntry(category, action, actor, outcome, target, teams, details, reason, ctx?.source))
}

/** Port to a destination of the stream: stdout, a file, Loki, Microsoft Sentinel, any HTTP collector. */
interface AuditSink {
    val name: String

    /** Delivers the batch or throws. [AuditLog] retries a failed batch. */
    fun write(batch: AuditBatch)
}

/** [lines] are the JSON lines of [records], encoded once for all sinks. */
class AuditBatch(val records: List<AuditRecord>, val lines: List<String>) {
    val size get() = records.size
}

internal data class Pending(val entry: AuditEntry, val time: Instant, val traceId: String?)
