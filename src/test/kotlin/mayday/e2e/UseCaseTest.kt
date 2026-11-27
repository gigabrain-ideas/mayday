package mayday.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.RequestOptions
import com.microsoft.playwright.options.SelectOption
import kotlinx.coroutines.runBlocking
import mayday.identity.TeamRole
import mayday.oncall.ScheduleInput
import mayday.oncall.ShiftUnit
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The main flows of work, as a user does them in the web interface. See docs/testing.md#use-case-tests. */
@TestPropertySource(properties = ["spring.grpc.server.inprocess.name=e2e-use-cases"])
class UseCaseTest : BrowserContract() {
    @org.springframework.beans.factory.annotation.Autowired private lateinit var pager: mayday.RecordingPager

    @Test
    fun `1 an administrator creates a team with no people`() {
        signIn()
        val name = unique("Payments API")
        createTeam(name)

        openOnCall(name)
        assertThat(card("Members").getByText("No members")).isVisible()
    }

    @Test
    fun `2 an administrator creates a team with three people`() {
        signIn()
        val people = List(3) { unique("responder${it + 1}").replace(' ', '-') + "@example.com" }
        page.navigate("/settings/users")
        for (email in people) {
            page.getByPlaceholder("email@company.com").fill(email)
            button("Add user").click()
            assertThat(page.getByRole(AriaRole.CELL, Page.GetByRoleOptions().setName(email))).isVisible()
        }
        val name = unique("Search")
        createTeam(name)

        openOnCall(name)
        val members = card("Members")
        for (email in people) {
            members.getByRole(AriaRole.SEARCHBOX, Locator.GetByRoleOptions().setName("Find a person")).fill(email)
            members.getByRole(AriaRole.COMBOBOX, Locator.GetByRoleOptions().setName("Add member"))
                .selectOption(SelectOption().setLabel("${email.substringBefore('@')} · $email"))
            assertThat(members.getByText(email, Locator.GetByTextOptions().setExact(true))).isVisible()
        }
        assertThat(members.getByRole(AriaRole.COMBOBOX, Locator.GetByRoleOptions().setName(Regex("^Role of ").toPattern()))).hasCount(3)
    }

    @Test
    fun `3 a monitoring tool raises an alert and responders see it`() {
        signIn()
        val title = unique("Disk 91% full on db-1")
        val res = page.request().post("/api/v1/alerts", RequestOptions.create()
            .setHeader("Authorization", "Bearer ${token("admin")}")
            .setData(mapOf("title" to title, "severity" to "SEV4", "team" to "checkout", "source" to "prometheus")))
        assertEquals(201, res.status(), res.text())

        page.navigate("/alerts")
        val row = page.getByRole(AriaRole.ROW).filter(Locator.FilterOptions().setHasText(title))
        assertThat(row).containsText("Firing")
        row.click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(title))).isVisible()
        assertThat(card("Details").getByText("prometheus")).isVisible()
    }

    @Test
    fun `4 a declared incident raises an alert for the incident`() {
        signIn()
        val title = unique("Checkout returns errors")
        declareIncident(title, "Checkout")

        val linked = card("Alerts")
        assertThat(linked).isVisible()
        assertThat(linked.getByRole(AriaRole.LINK)).hasCount(1)
        assertThat(linked.getByRole(AriaRole.LINK)).containsText("Firing")
        linked.getByRole(AriaRole.LINK).click()
        assertThat(card("Incident").getByText(title)).isVisible()
    }

    @Test
    fun `5 a responder acknowledges an incident and it shows as acknowledged`() {
        signIn()
        declareIncident(unique("Login is slow"), "Checkout")
        val details = card("Details")
        assertThat(detailRow(details, "Acknowledged")).containsText("—")

        page.getByRole(AriaRole.COMBOBOX, Page.GetByRoleOptions().setName("Status")).selectOption("IDENTIFIED")

        assertThat(page.getByText("Identified", Page.GetByTextOptions().setExact(true)).first()).isVisible()
        assertThat(detailRow(details, "Acknowledged")).not().containsText("—")
        assertThat(detailRow(details, "Time to acknowledge")).not().containsText("—")
        assertThat(page.getByText("Status changed", Page.GetByTextOptions().setExact(false)).first()).isVisible()

        card("Alerts").getByRole(AriaRole.LINK).click()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Acknowledge"))).hasCount(0)
        assertThat(card("Updates")).containsText("Acknowledged.")
    }

    @Test
    fun `6 the on-call page shows who is on call in every team`() {
        val sre = userOf("admin")
        runBlocking {
            val admin = principal("admin")
            app.identity.setTeamMember(admin, other.id, sre.id, TeamRole.MEMBER)
            val start = Instant.now().minus(1, ChronoUnit.HOURS)
            app.onCall.create(admin, ScheduleInput(team.id, unique("Checkout primary"), rotationStart = start, shiftUnit = ShiftUnit.WEEKS, participants = listOf(userOf("lead").id)))
            app.onCall.create(admin, ScheduleInput(other.id, unique("SRE primary"), rotationStart = start, shiftUnit = ShiftUnit.WEEKS, participants = listOf(sre.id)))
        }
        signIn()
        page.navigate("/oncall")

        assertThat(page.getByRole(AriaRole.COMBOBOX, Page.GetByRoleOptions().setName("Team"))).hasValue("")
        assertThat(nowCard("Checkout · Checkout primary")).containsText("lead")
        assertThat(nowCard("SRE · SRE primary")).containsText("admin")
    }

    @Test
    fun `7 an agent acknowledges an alert and a person still acknowledges it`() {
        val title = unique("Queue backlog grows")
        val alert = runBlocking {
            val a = app.alerts.ingest(principal("admin"), mayday.alerting.AlertSignal(title, mayday.shared.Severity.SEV3, "checkout", source = "prometheus")).alert
            val agent = mayday.identity.Principal(mayday.shared.Actor(mayday.shared.ActorKind.AGENT, "mayday", "Mayday agent"), null,
                mayday.identity.OrgRole.MEMBER, setOf(mayday.identity.TokenScope.READ, mayday.identity.TokenScope.WRITE), "run-e2e")
            app.alerts.acknowledge(agent, a.id)
        }
        signIn()
        page.navigate("/alerts/${alert.id}")

        val waiting = page.getByText("By an agent · waiting for a person")
        assertThat(waiting).isVisible()
        button("Acknowledge").click()

        assertThat(waiting).hasCount(0)
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Acknowledge"))).hasCount(0)
    }

    @Test
    fun `8 a team owner approves a runbook, an agent closes its alert alone, and a person confirms any other agent close`() {
        val source = unique("disk-exporter").replace(' ', '-')
        signIn()
        openOnCall("Checkout")
        val runbooks = card("Runbooks for agents")
        runbooks.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("New runbook")).click()
        val title = unique("Disk full on db hosts")
        page.getByPlaceholder("Disk full on a database host").fill(title)
        page.locator("textarea").fill("1. Delete log files older than 7 days.\n2. Verify that disk use is below 80%.")
        page.getByPlaceholder("prometheus").fill(source)
        button("Save draft").click()
        val row = runbooks.locator("div.px-4").filter(Locator.FilterOptions().setHasText(title))
        assertThat(row.getByText("Draft", Locator.GetByTextOptions().setExact(true))).isVisible()
        row.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Approve")).click()
        assertThat(row.getByText("Approved for agents")).isVisible()
        val blocked = runbooks.getByRole(AriaRole.STATUS).filter(Locator.FilterOptions().setHasText("Agents cannot follow the approved runbooks of this team alone"))
        assertThat(blocked).isVisible()
        assertThat(blocked).containsText("Mayday starts no runbook run")
        runBlocking { app.policy.set(principal("admin"), mayday.policy.Scope.TEAM, team.id, mayday.policy.Keys.AGENT_AUTO_REMEDIATION, "true", mayday.policy.Enforcement.NONE) }
        try {
            assertThat(blocked).hasCount(0)

            val agent = mayday.shared.Actor(mayday.shared.ActorKind.AGENT, "mayday", "Mayday agent")
            val agentPrincipal = mayday.identity.Principal(agent, null, mayday.identity.OrgRole.MEMBER, setOf(mayday.identity.TokenScope.READ, mayday.identity.TokenScope.WRITE), "run-e2e")
            val (covered, other) = runBlocking {
                val rb = app.runbooks.list(principal("viewer"), "checkout").single { it.title == title }
                suspend fun fire(t: String, src: String) = app.alerts.ingest(principal("admin"), mayday.alerting.AlertSignal(t, mayday.shared.Severity.SEV3, "checkout", source = src)).alert
                val covered = fire(unique("Disk 95% on db-1"), source)
                app.ports.runs.save(mayday.agents.AgentRun(mayday.shared.Ids.new(), "mayday", "Mayday agent", mayday.agents.AgentAction.ANALYZE, mayday.agents.SubjectKind.ALERT,
                    covered.id, covered.title, mayday.agents.RunStatus.RUNNING, mayday.agents.RunTrigger.AUTO, mayday.shared.Actor.system, createdAt = Instant.now(),
                    alertId = covered.id, runbookId = rb.id), null)
                app.alerts.resolve(agentPrincipal, covered.id, "Old logs deleted. Disk at 40%.")
                val other = fire(unique("Latency high on api"), "grafana")
                app.alerts.resolve(agentPrincipal, other.id, "Looks fine now.")
                covered to other
            }

            page.navigate("/alerts/${covered.id}")
            assertThat(page.getByText("Resolved under an approved runbook", Page.GetByTextOptions().setExact(true))).isVisible()
            assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Acknowledge"))).hasCount(0)

            page.navigate("/alerts/${other.id}")
            val waiting = page.getByText("Resolved by an agent · waiting for a person", Page.GetByTextOptions().setExact(true))
            assertThat(waiting).isVisible()
            button("Acknowledge").click()
            assertThat(waiting).hasCount(0)
            assertThat(card("Updates")).containsText("Acknowledged.")
        } finally {
            runBlocking { app.policy.clear(principal("admin"), mayday.policy.Scope.TEAM, team.id, mayday.policy.Keys.AGENT_AUTO_REMEDIATION) }
        }
    }

    @Test
    fun `9 a person past the first 500 users shows by name and can join a team`() {
        val late = unique("zzz-late").replace(' ', '-') + "@example.com"
        val title = unique("Payments time out")
        runBlocking {
            repeat(501) { app.ports.users.save(mayday.identity.User(mayday.shared.Ids.new(), "zz-bulk-${unique("$it").replace(' ', '-')}@example.com", "Bulk $it", createdAt = Instant.now())) }
            val person = mayday.identity.User(mayday.shared.Ids.new(), late, "Zed Late", createdAt = Instant.now())
            app.ports.users.save(person)
            app.incidents.declare(principal("admin"), mayday.incidents.NewIncident(title, severity = mayday.shared.Severity.SEV3, teamIds = listOf(team.id), commanderId = person.id))
        }
        signIn()
        page.navigate("/incidents")
        assertThat(page.getByRole(AriaRole.ROW).filter(Locator.FilterOptions().setHasText(title))).containsText("Zed Late")

        openOnCall("Checkout")
        val members = card("Members")
        members.getByRole(AriaRole.SEARCHBOX, Locator.GetByRoleOptions().setName("Find a person")).fill("zzz-late")
        members.getByRole(AriaRole.COMBOBOX, Locator.GetByRoleOptions().setName("Add member")).selectOption(SelectOption().setLabel("Zed Late · $late"))
        assertThat(members.getByText(late, Locator.GetByTextOptions().setExact(true))).isVisible()
    }

    @Test
    fun `10 the firing badge follows new alerts and no tab asks the server for the count`() {
        fun fire() = runBlocking { app.alerts.ingest(principal("admin"), mayday.alerting.AlertSignal(unique("CPU high"), mayday.shared.Severity.SEV4, "checkout")) }
        fire()
        signIn()
        val badge = page.locator("aside span[aria-label$=' firing']")
        assertThat(badge).isVisible()
        page.waitForTimeout(500.0)
        val counts = java.util.concurrent.atomic.AtomicInteger()
        page.onRequest { if (it.url().endsWith("/CountAlerts")) counts.incrementAndGet() }
        val before = badge.getAttribute("aria-label")!!.substringBefore(' ').toInt()
        fire()

        assertThat(badge).hasAttribute("aria-label", "${before + 1} firing")
        assertEquals(0, counts.get(), "the count comes with the event")
    }

    @Test
    fun `11 an open incident page shows a change that its live stream missed`() {
        signIn()
        val title = unique("Search is down")
        declareIncident(title, "Checkout")
        val renamed = unique("Search index is down")
        runBlocking {
            val inc = app.incidents.list(principal("admin"), mayday.incidents.IncidentFilter(query = title), null, 1).items.single()
            app.ports.incidents.save(inc.copy(title = renamed, version = inc.version + 1), inc.version)
            app.ports.events.publish(mayday.shared.DomainEvent(mayday.shared.EventBus.RESYNC, "", Instant.now()))
        }

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(renamed))).isVisible()
    }

    @Test
    fun `12 an open alert page marks a page that the pager did not deliver`() {
        val esc = app.escalation
        val policy = runBlocking {
            esc.create(principal("admin"), mayday.escalation.EscalationPolicyInput(team.id, unique("E2E paging"), teamDefault = true,
                levels = List(2) { mayday.escalation.EscalationLevel(5, listOf(mayday.escalation.EscalationTarget(mayday.escalation.TargetKind.USER, userOf("lead").id))) }))
        }
        try {
            val alert = runBlocking { app.alerts.ingest(principal("admin"), mayday.alerting.AlertSignal(unique("Payments 500s"), mayday.shared.Severity.SEV2, "checkout")).alert }
            signIn()
            page.navigate("/alerts/${alert.id}")
            val escalation = card("Escalation")
            assertThat(escalation.getByText("Level 1", Locator.GetByTextOptions().setExact(true))).isVisible()
            pager.fail = true
            pager.delayMs = 1500
            runBlocking { esc.escalateNow(principal("admin"), alert.id) }

            assertThat(escalation.getByText("Not delivered")).isVisible()
        } finally {
            pager.fail = false
            pager.delayMs = 0
            runBlocking { esc.delete(principal("admin"), policy.id) }
        }
    }

    @Test
    fun `13 an incident page keeps the incident when a live reload fails`() {
        signIn()
        val title = unique("Orders are slow")
        declareIncident(title, "Checkout")
        page.route("**/mayday.v1.IncidentService/GetIncident") { it.fulfill(com.microsoft.playwright.Route.FulfillOptions().setStatus(503)) }
        runBlocking {
            val inc = app.incidents.list(principal("admin"), mayday.incidents.IncidentFilter(query = title), null, 1).items.single()
            app.incidents.addEntry(principal("admin"), inc.key, "Orders queue grows.")
        }

        assertThat(page.getByText("Orders queue grows.")).isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(title))).isVisible()
        assertThat(page.getByText("Incident not found")).hasCount(0)
    }

    @Test
    fun `14 a responder keeps the typed update when posting it fails`() {
        val alert = runBlocking { app.alerts.ingest(principal("admin"), mayday.alerting.AlertSignal(unique("Cache misses"), mayday.shared.Severity.SEV4, "checkout")).alert }
        signIn()
        page.navigate("/alerts/${alert.id}")
        page.route("**/mayday.v1.AlertService/AddAlertUpdate") { it.fulfill(com.microsoft.playwright.Route.FulfillOptions().setStatus(503)) }
        val text = page.getByPlaceholder("Add an update")
        text.fill("Restarted cache-2. Misses drop.")
        button("Post").click()

        assertThat(page.locator("[aria-live=polite] svg.text-destructive")).isVisible()
        assertThat(text).hasValue("Restarted cache-2. Misses drop.")
    }

    @Test
    fun `15 a failed load of more incidents shows the error`() {
        val prefix = unique("Batch job late").replace(' ', '-')
        runBlocking { repeat(51) { app.incidents.declare(principal("admin"), mayday.incidents.NewIncident("$prefix $it", severity = mayday.shared.Severity.SEV4, teamIds = listOf(team.id))) } }
        signIn()
        page.navigate("/incidents?q=$prefix")
        val more = button("Load more")
        assertThat(more).isVisible()
        page.route("**/mayday.v1.IncidentService/ListIncidents") { it.fulfill(com.microsoft.playwright.Route.FulfillOptions().setStatus(503)) }
        more.click()

        assertThat(page.locator("[aria-live=polite] svg.text-destructive")).isVisible()
        assertThat(more).isVisible()
    }

    private fun createTeam(name: String) {
        page.navigate("/settings/directory")
        page.getByPlaceholder("New team name").fill(name)
        card("Teams").getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Add")).click()
        assertThat(card("Teams").getByText(name, Locator.GetByTextOptions().setExact(true))).isVisible()
    }

    private fun openOnCall(team: String) {
        page.navigate("/oncall")
        page.getByRole(AriaRole.COMBOBOX, Page.GetByRoleOptions().setName("Team")).selectOption(SelectOption().setLabel(team))
        assertThat(card("Members")).isVisible()
    }

    private fun declareIncident(title: String, team: String) {
        page.navigate("/incidents")
        button("Declare incident").click()
        page.getByPlaceholder("Checkout returns errors").fill(title)
        button("Teams").click()
        page.getByRole(AriaRole.MENUITEMCHECKBOX, Page.GetByRoleOptions().setName(team)).click()
        page.keyboard().press("Escape")
        button("Declare", exact = true).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(title))).isVisible()
    }

    private fun detailRow(details: Locator, label: String): Locator =
        details.locator("div.flex").filter(Locator.FilterOptions().setHas(page.locator("span.eyebrow").getByText(label, Locator.GetByTextOptions().setExact(true))))

    private fun nowCard(eyebrow: String): Locator =
        page.locator("section div.rounded-2xl").filter(Locator.FilterOptions().setHasText(eyebrow))
}
