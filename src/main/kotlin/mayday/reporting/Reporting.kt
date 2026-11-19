package mayday.reporting

import kotlinx.serialization.Serializable
import mayday.alerting.AlertFilter
import mayday.alerting.AlertRepository
import mayday.directory.DirectoryService
import mayday.identity.Access
import mayday.identity.Principal
import mayday.incidents.IncidentFilter
import mayday.incidents.IncidentRepository
import mayday.incidents.PostmortemStatus
import mayday.shared.Invalid
import mayday.shared.Severity
import mayday.shared.Ts
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Serializable
data class ReportQuery(
    val teamIds: List<String> = emptyList(),
    val from: Ts,
    val to: Ts,
    val severities: List<Severity> = emptyList(),
)

@Serializable
data class TeamRow(val teamId: String, val teamName: String, val incidents: Int, val open: Int, val mttrMinutes: Double?, val alerts: Int)

@Serializable
data class DayRow(val day: String, val bySeverity: Map<String, Int>, val alerts: Int)

@Serializable
data class IncidentRow(val key: String, val title: String, val severity: Severity, val status: String, val durationMinutes: Double?, val teamIds: List<String>)

@Serializable
data class Report(
    val query: ReportQuery,
    val incidents: Int,
    val open: Int,
    val resolved: Int,
    val alerts: Int,
    val alertsWithIncident: Int,
    val bySeverity: Map<String, Int>,
    val mttaMinutes: Double?,
    val mttrMinutes: Double?,
    val mttdMinutes: Double?,
    val postmortemsRequired: Int,
    val postmortemsPublished: Int,
    val postmortemsOverdue: Int,
    val byTeam: List<TeamRow>,
    val byDay: List<DayRow>,
    val longest: List<IncidentRow>,
)

class ReportService(
    private val incidents: IncidentRepository,
    private val alerts: AlertRepository,
    private val directory: DirectoryService,
) {
    fun report(by: Principal, q: ReportQuery, now: Instant = Instant.now()): Report {
        Access.requireRead(by)
        if (!q.to.isAfter(q.from)) throw Invalid("The end of the period must be after the start")
        if (Duration.between(q.from, q.to).toDays() > 731) throw Invalid("The period can be at most two years")
        val teamIds = q.teamIds.map { directory.team(it).id }.toSet()
        val teams = directory.teams().associateBy { it.id }
        val sevs = q.severities.toSet()

        var count = 0; var open = 0; var resolved = 0
        val bySeverity = Severity.entries.associate { it.name to 0 }.toMutableMap()
        val mtta = Mean(); val mttr = Mean(); val mttd = Mean()
        var pmRequired = 0; var pmPublished = 0; var pmOverdue = 0
        val teamCount = HashMap<String, Int>(); val teamOpen = HashMap<String, Int>(); val teamMttr = HashMap<String, Mean>()
        val teamAlerts = HashMap<String, Int>()
        val days = sortedMapOf<String, MutableMap<String, Int>>()
        val dayAlerts = HashMap<String, Int>()
        val longest = java.util.PriorityQueue<IncidentRow>(compareBy { it.durationMinutes ?: 0.0 })

        var day = q.from.truncatedTo(ChronoUnit.DAYS)
        while (day.isBefore(q.to)) {
            days[day.atOffset(ZoneOffset.UTC).toLocalDate().toString()] = Severity.entries.associate { it.name to 0 }.toMutableMap()
            day = day.plus(1, ChronoUnit.DAYS)
        }

        incidents.scan(IncidentFilter(severities = sevs, teamIds = teamIds, from = q.from, to = q.to)) { inc ->
            count++
            if (inc.isOpen) open++ else resolved++
            bySeverity.merge(inc.severity.name, 1, Int::plus)
            inc.acknowledgedAt?.let { mtta.add(minutes(inc.reportedAt, it)) }
            val start = inc.impactStartedAt ?: inc.reportedAt
            val duration = inc.resolvedAt?.let { minutes(start, it) }
            duration?.let { mttr.add(it) }
            if (inc.impactStartedAt != null && inc.discoveredAt != null) mttd.add(minutes(inc.impactStartedAt, inc.discoveredAt))
            if (inc.postmortemRequired) {
                pmRequired++
                if (inc.postmortem?.status == PostmortemStatus.PUBLISHED) pmPublished++
                else if (inc.postmortemDueAt?.isBefore(now) == true) pmOverdue++
            }
            for (t in inc.teamIds) {
                if (teamIds.isNotEmpty() && t !in teamIds) continue
                teamCount.merge(t, 1, Int::plus)
                if (inc.isOpen) teamOpen.merge(t, 1, Int::plus)
                duration?.let { teamMttr.getOrPut(t) { Mean() }.add(it) }
            }
            days[inc.reportedAt.atOffset(ZoneOffset.UTC).toLocalDate().toString()]?.merge(inc.severity.name, 1, Int::plus)
            longest.add(IncidentRow(inc.key, inc.title, inc.severity, inc.status.name, duration ?: minutes(start, now), inc.teamIds))
            if (longest.size > 10) longest.poll()
        }

        var alertCount = 0; var alertsLinked = 0
        alerts.scan(AlertFilter(severities = sevs, teamIds = teamIds, from = q.from, to = q.to)) { a ->
            alertCount++
            if (a.incidentId != null) alertsLinked++
            teamAlerts.merge(a.teamId, 1, Int::plus)
            dayAlerts.merge(a.firstSeenAt.atOffset(ZoneOffset.UTC).toLocalDate().toString(), 1, Int::plus)
        }

        val teamRows = (teamCount.keys + teamAlerts.keys + teamIds).distinct().map { t ->
            TeamRow(t, teams[t]?.name ?: t, teamCount[t] ?: 0, teamOpen[t] ?: 0, teamMttr[t]?.value(), teamAlerts[t] ?: 0)
        }.sortedByDescending { it.incidents }

        return Report(
            query = q.copy(teamIds = teamIds.toList()),
            incidents = count,
            open = open,
            resolved = resolved,
            alerts = alertCount,
            alertsWithIncident = alertsLinked,
            bySeverity = bySeverity,
            mttaMinutes = mtta.value(),
            mttrMinutes = mttr.value(),
            mttdMinutes = mttd.value(),
            postmortemsRequired = pmRequired,
            postmortemsPublished = pmPublished,
            postmortemsOverdue = pmOverdue,
            byTeam = teamRows,
            byDay = days.map { (d, m) -> DayRow(d, m, dayAlerts[d] ?: 0) },
            longest = longest.sortedByDescending { it.durationMinutes ?: 0.0 },
        )
    }

    private fun minutes(a: Instant, b: Instant) = Duration.between(a, b).toSeconds() / 60.0

    private class Mean {
        private var sum = 0.0; private var n = 0
        fun add(v: Double) { if (v >= 0) { sum += v; n++ } }
        fun value(): Double? = if (n == 0) null else Math.round(sum / n * 10) / 10.0
    }
}
