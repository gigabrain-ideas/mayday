package mayday

import mayday.alerting.Alert
import mayday.alerting.AlertService
import mayday.alerting.AlertStatus
import mayday.alerting.AlertUpdate
import mayday.identity.OrgRole
import mayday.identity.TeamRole
import mayday.identity.User
import mayday.incidents.ActionItem
import mayday.incidents.EntryKind
import mayday.incidents.Incident
import mayday.incidents.IncidentStatus
import mayday.incidents.Postmortem
import mayday.incidents.PostmortemStatus
import mayday.incidents.TimelineEntry
import mayday.policy.Enforcement
import mayday.policy.Keys
import mayday.policy.Scope
import mayday.policy.SettingValue
import mayday.shared.Actor
import mayday.shared.ActorKind
import mayday.shared.Ids
import mayday.shared.Severity
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** Sample data for local trials and screenshots. Runs only when the store has no incidents. */
object Demo {
    suspend fun seed(app: App) {
        val p = app.ports
        if (p.incidents.list(mayday.incidents.IncidentFilter(), null, 1).items.isNotEmpty()) return
        val now = Instant.now()
        val d = app.directory
        val payments = d.createProject("Payments", "payments", "Checkout, billing and payment processing")
        val platform = d.createProject("Platform", "platform", "Shared infrastructure")
        val checkout = d.createTeam("Checkout", "checkout", payments.id)
        val billing = d.createTeam("Billing", "billing", payments.id)
        val sre = d.createTeam("SRE", "sre", platform.id)
        val identityTeam = d.createTeam("Identity", "identity", platform.id)
        val teams = listOf(checkout, billing, sre, identityTeam)

        suspend fun user(email: String, name: String, role: OrgRole, t: Map<String, TeamRole>) =
            (p.users.byEmail(email) ?: User(Ids.new(), email, name, role, t, createdAt = now)).copy(orgRole = role, teams = t, name = name).also { p.users.save(it) }
        val admin = user("admin@example.com", "Alex Admin", OrgRole.ADMIN, mapOf(sre.id to TeamRole.ADMIN))
        val ana = user("ana@example.com", "Ana Responder", OrgRole.MEMBER, mapOf(checkout.id to TeamRole.ADMIN, billing.id to TeamRole.MEMBER))
        val sam = user("sam@example.com", "Sam Oncall", OrgRole.MEMBER, mapOf(sre.id to TeamRole.MEMBER, identityTeam.id to TeamRole.MEMBER, checkout.id to TeamRole.MEMBER))
        val lee = user("lee@example.com", "Lee Backend", OrgRole.MEMBER, mapOf(checkout.id to TeamRole.MEMBER, sre.id to TeamRole.MEMBER))
        user("vic@example.com", "Vic Viewer", OrgRole.VIEWER, emptyMap())
        val actorAna = Actor(ActorKind.USER, ana.id, ana.name)
        val actorAdmin = Actor(ActorKind.USER, admin.id, admin.name)
        val agent = Actor(ActorKind.AGENT, "mayday", "Mayday agent", ana.id)

        p.settings.save(SettingValue(Scope.ORG, "org", Keys.POSTMORTEM_MIN_SEVERITY, "SEV2", Enforcement.MINIMUM, updatedBy = actorAdmin, updatedAt = now))
        p.settings.save(SettingValue(Scope.ORG, "org", Keys.AGENT_AUTO_REMEDIATION, "false", Enforcement.LOCKED, updatedBy = actorAdmin, updatedAt = now))
        p.settings.save(SettingValue(Scope.PROJECT, payments.id, Keys.POSTMORTEM_MIN_SEVERITY, "SEV3", updatedBy = actorAdmin, updatedAt = now))
        p.settings.save(SettingValue(Scope.ORG, "org", Keys.MCP_WRITE, "true", updatedBy = actorAdmin, updatedAt = now))
        app.policy.invalidate()

        val monday = now.atZone(java.time.ZoneOffset.UTC).toLocalDate().with(java.time.DayOfWeek.MONDAY).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC)
        fun rotation(team: mayday.directory.Team, name: String, unit: mayday.oncall.ShiftUnit, people: List<User>) = mayday.oncall.Schedule(
            Ids.new(), team.id, name, "Europe/Berlin", monday, 1, unit, people.map { it.id }, createdAt = now, updatedAt = now)
        val checkoutPrimary = rotation(checkout, "Primary", mayday.oncall.ShiftUnit.WEEKS, listOf(ana, sam, lee))
        val checkoutSecondary = rotation(checkout, "Secondary", mayday.oncall.ShiftUnit.WEEKS, listOf(lee, ana, sam))
        val srePrimary = rotation(sre, "Primary", mayday.oncall.ShiftUnit.DAYS, listOf(sam, lee, admin))
        listOf(checkoutPrimary, checkoutSecondary, srePrimary).forEach { p.schedules.save(it, null) }

        fun target(s: mayday.oncall.Schedule) = mayday.escalation.EscalationTarget(mayday.escalation.TargetKind.SCHEDULE, s.id)
        fun target(u: User) = mayday.escalation.EscalationTarget(mayday.escalation.TargetKind.USER, u.id)
        fun level(minutes: Int, vararg t: mayday.escalation.EscalationTarget) = mayday.escalation.EscalationLevel(minutes, t.toList())
        val checkoutPolicy = mayday.escalation.EscalationPolicy(Ids.new(), checkout.id, "Default", "Primary first, then Secondary, then the team owner.",
            listOf(level(5, target(checkoutPrimary)), level(10, target(checkoutSecondary)), level(15, target(ana))), 1, true, now, now)
        val sreLead = listOf(admin, lee).first { it.id != app.onCall.onCallFor(srePrimary.id, now) }
        val srePolicy = mayday.escalation.EscalationPolicy(Ids.new(), sre.id, "Default", "", listOf(level(10, target(srePrimary)), level(15, target(sreLead))), 0, true, now, now)
        listOf(checkoutPolicy, srePolicy).forEach { p.escalationPolicies.save(it, null) }
        p.overrides.save(mayday.oncall.ScheduleOverride(Ids.new(), checkoutPrimary.id, checkout.id, lee.id, now.plus(Duration.ofDays(2)),
            now.plus(Duration.ofDays(4)), "Sam is at a conference", ana.id, now))

        val titles = listOf(
            "Checkout API returns HTTP 502 for card payments" to checkout,
            "Invoice generation delayed by 40 minutes" to billing,
            "Elevated latency on the edge load balancers" to sre,
            "Login fails for SSO users in EU region" to identityTeam,
            "Payment webhook queue backlog" to billing,
            "Disk pressure on Kafka brokers" to sre,
            "Cart service memory leak after deploy" to checkout,
            "Token refresh errors in mobile app" to identityTeam,
            "DNS resolution failures in us-east" to sre,
            "Duplicate charges for a small set of orders" to checkout,
        )
        val rnd = Random(42)
        var number = 0L
        val history = (0 until 46).map { i ->
            val (title, team) = titles[i % titles.size]
            val sev = Severity.entries[listOf(0, 1, 1, 2, 2, 2, 3, 3, 4)[rnd.nextInt(9)]]
            val start = now.minus(Duration.ofHours(rnd.nextLong(6, 24 * 60)))
            Triple(title, team, sev) to start
        }.sortedBy { it.second }
        for ((t, start) in history) {
            val (title, team, sev) = t
            number = p.incidents.nextNumber()
            val discovered = start.plus(Duration.ofMinutes(rnd.nextLong(1, 25)))
            val reported = discovered.plus(Duration.ofMinutes(rnd.nextLong(1, 10)))
            val ack = reported.plus(Duration.ofMinutes(rnd.nextLong(1, 15)))
            val resolved = ack.plus(Duration.ofMinutes(rnd.nextLong(10, 480)))
            val required = sev.atLeast(if (team.projectId == payments.id) Severity.SEV3 else Severity.SEV2)
            val published = required && rnd.nextInt(3) > 0
            val inc = Incident(
                id = Ids.new(), number = number, key = "INC-$number", title = title,
                description = "Customers reported problems. The on-call engineer confirmed the impact with dashboards.",
                severity = sev, status = IncidentStatus.CLOSED, teamIds = listOf(team.id), commanderId = ana.id,
                impactStartedAt = start, impactEndedAt = resolved, discoveredAt = discovered, reportedAt = reported,
                acknowledgedAt = ack, resolvedAt = resolved, postmortemRequired = required,
                postmortemDueAt = if (required) resolved.plus(Duration.ofDays(7)) else null,
                postmortem = if (published) Postmortem(PostmortemStatus.PUBLISHED, "The service was degraded for some customers.",
                    "Some requests failed.", "A configuration change removed capacity.", "An alert fired.", "We rolled back the change.",
                    "Add a canary stage.", listOf(ActionItem("Add a canary stage to the deploy pipeline", "Ana Responder")), actorAna, resolved) else null,
                createdBy = actorAna, createdAt = reported, updatedAt = resolved,
            )
            p.incidents.save(inc, null)
            p.timeline.append(TimelineEntry(Ids.new(), inc.id, reported, EntryKind.STATUS, actorAna, "Incident declared as $sev."))
            p.timeline.append(TimelineEntry(Ids.new(), inc.id, resolved, EntryKind.STATUS, actorAna, "Status changed from MONITORING to RESOLVED."))
        }

        // Current incident with a rich timeline.
        number = p.incidents.nextNumber()
        val start = now.minus(Duration.ofMinutes(52))
        val alert = Alert(
            id = Ids.new(), fingerprint = AlertService.fingerprint("prometheus", "CheckoutHighErrorRate", checkout.id, mapOf("service" to "checkout-api")),
            source = "prometheus", title = "CheckoutHighErrorRate: 5xx ratio above 5% for 5m", description = "checkout-api 5xx ratio is 12.4%",
            severity = Severity.SEV1, status = AlertStatus.ACKNOWLEDGED, teamId = checkout.id,
            labels = mapOf("service" to "checkout-api", "region" to "eu-west-1", "severity" to "critical"),
            firstSeenAt = start.plus(Duration.ofMinutes(3)), lastSeenAt = now.minus(Duration.ofMinutes(2)), count = 17,
            acknowledgedBy = actorAna, updates = listOf(AlertUpdate(start.plus(Duration.ofMinutes(9)), actorAna, "Acknowledged.")),
        )
        val current = Incident(
            id = Ids.new(), number = number, key = "INC-$number", title = "Checkout API returns HTTP 502 for card payments",
            description = "About 12% of card payments fail with HTTP 502 since 14:05 UTC. PayPal and wallet payments are not affected.",
            severity = Severity.SEV1, status = IncidentStatus.IDENTIFIED, teamIds = listOf(checkout.id, sre.id), commanderId = ana.id,
            impactStartedAt = start, discoveredAt = start.plus(Duration.ofMinutes(3)), reportedAt = start.plus(Duration.ofMinutes(6)),
            acknowledgedAt = start.plus(Duration.ofMinutes(9)), postmortemRequired = true, alertIds = listOf(alert.id),
            labels = mapOf("service" to "checkout-api", "region" to "eu-west-1"),
            resolutionNotes = "We rolled back checkout-api to v2024.11.3. The connection pool limit to the card processor was restored to 200. Error rate went back to 0.1% within 4 minutes.",
            createdBy = Actor.system, createdAt = start.plus(Duration.ofMinutes(6)), updatedAt = now.minus(Duration.ofMinutes(5)),
        )
        p.alerts.save(alert.copy(incidentId = current.id), null)
        p.incidents.save(current, null)
        val paged = alert.firstSeenAt
        p.escalations.save(mayday.escalation.Escalation(alert.id, checkout.id, checkoutPolicy.id, mayday.escalation.EscalationStatus.ACKNOWLEDGED, 1, 0, null,
            listOf(mayday.escalation.EscalationPage(paged, 1, 0, listOfNotNull(app.onCall.onCallFor(checkoutPrimary.id, paged))),
                mayday.escalation.EscalationPage(paged.plus(Duration.ofMinutes(5)), 2, 0, listOfNotNull(app.onCall.onCallFor(checkoutSecondary.id, paged)))),
            paged, start.plus(Duration.ofMinutes(9))), null)
        val tl = listOf(
            3L to (EntryKind.ALERT to (Actor.system to "Alert from prometheus: CheckoutHighErrorRate: 5xx ratio above 5% for 5m")),
            6L to (EntryKind.STATUS to (Actor.system to "Incident declared as SEV1 with status INVESTIGATING.")),
            7L to (EntryKind.AGENT to (Actor(ActorKind.AGENT, "mayday", "Mayday agent") to "Mayday agent started automatically: Analyze.")),
            9L to (EntryKind.NOTE to (actorAna to "I am incident commander. Joining the bridge.")),
            14L to (EntryKind.AGENT to (agent to "Summary: checkout-api returns 502 for card payments only.\nEvidence: errors started 2 minutes after deploy v2024.11.4; upstream 'card-processor' connection pool is exhausted (pool_wait_seconds p99 = 30s).\nProbable cause: the deploy reduced the pool size from 200 to 20.\nNext steps: roll back checkout-api to v2024.11.3.")),
            19L to (EntryKind.STATUS to (actorAna to "Status changed from INVESTIGATING to IDENTIFIED.")),
            23L to (EntryKind.NOTE to (actorAna to "Rollback to v2024.11.3 started.")),
            31L to (EntryKind.NOTE to (Actor(ActorKind.USER, "sam", "Sam Oncall") to "Error rate is at 0.1%. Monitoring.")),
        )
        tl.forEach { (m, v) -> p.timeline.append(TimelineEntry(Ids.new(), current.id, start.plus(Duration.ofMinutes(m)), v.first, v.second.first, v.second.second)) }

        // Open alerts without incidents.
        listOf(
            Triple("KafkaDiskUsageHigh: broker-3 disk above 80%", sre, Severity.SEV3),
            Triple("IdentityTokenLatency: p99 above 800ms", identityTeam, Severity.SEV4),
            Triple("BillingJobRetries: retries above threshold", billing, Severity.SEV3),
        ).forEachIndexed { i, (title, team, sev) ->
            val at = now.minus(Duration.ofMinutes(15L + i * 23))
            val open = Alert(Ids.new(), AlertService.fingerprint("prometheus", title, team.id, emptyMap()), "prometheus", title,
                severity = sev, teamId = team.id, firstSeenAt = at, lastSeenAt = now.minus(Duration.ofMinutes(1)), count = 3L + i)
            p.alerts.save(open, null)
            if (team.id == sre.id) {
                p.escalations.save(mayday.escalation.Escalation(open.id, sre.id, srePolicy.id, mayday.escalation.EscalationStatus.ACTIVE, 1, 0, at.plus(Duration.ofMinutes(25)),
                    listOf(mayday.escalation.EscalationPage(at, 1, 0, listOfNotNull(app.onCall.onCallFor(srePrimary.id, at))),
                        mayday.escalation.EscalationPage(at.plus(Duration.ofMinutes(10)), 2, 0, listOf(sreLead.id))), at, at.plus(Duration.ofMinutes(10))), null)
            }
        }
        teams.size
    }
}
