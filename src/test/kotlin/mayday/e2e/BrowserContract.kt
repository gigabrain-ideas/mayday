package mayday.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import com.microsoft.playwright.assertions.PlaywrightAssertions
import com.microsoft.playwright.options.AriaRole
import mayday.contract.ServerContract
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Drives the real web interface in Chromium against the real server (see [ServerContract]).
 * Headless by default. MAYDAY_E2E_HEADED=true opens a visible browser; MAYDAY_E2E_SLOWMO sets the delay per action in ms (default 400 when headed).
 * A failed test keeps a Playwright trace in build/e2e (open it with `npx playwright show-trace` or https://trace.playwright.dev).
 */
@Tag("e2e")
abstract class BrowserContract : ServerContract() {
    companion object {
        private val headed = System.getenv("MAYDAY_E2E_HEADED").toBoolean()
        private val slowMo = System.getenv("MAYDAY_E2E_SLOWMO")?.toDoubleOrNull() ?: if (headed) 400.0 else 0.0
        private val artifacts = Path.of(System.getProperty("mayday.e2e.artifacts", "build/e2e"))

        @JvmStatic
        @DynamicPropertySource
        fun webDir(registry: DynamicPropertyRegistry) {
            val dir = Path.of(System.getProperty("mayday.e2e.web-dir", "web/dist"))
            check(Files.exists(dir.resolve("index.html"))) { "No web interface in $dir. Run `make web` first." }
            registry.add("mayday.web-dir") { dir.toString() }
        }
    }

    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private lateinit var context: BrowserContext
    protected lateinit var page: Page
    private var failed = false

    @RegisterExtension
    val failures = AfterTestExecutionCallback { failed = it.executionException.isPresent }

    @BeforeAll
    fun launch() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(!headed).setSlowMo(slowMo))
        PlaywrightAssertions.setDefaultAssertionTimeout(10_000.0)
    }

    @AfterAll
    fun shutdown() {
        browser.close()
        playwright.close()
    }

    @BeforeEach
    fun openPage() {
        failed = false
        context = browser.newContext(Browser.NewContextOptions().setBaseURL(base).setViewportSize(1440, 900))
        context.tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
        page = context.newPage()
    }

    @AfterEach
    fun closePage(info: TestInfo) {
        val trace = if (failed) artifacts.resolve("${javaClass.simpleName}-${info.displayName.replace(Regex("[^A-Za-z0-9]+"), "-")}.zip") else null
        context.tracing().stop(trace?.let { Tracing.StopOptions().setPath(it) })
        context.close()
    }

    protected fun assertThat(locator: Locator) = PlaywrightAssertions.assertThat(locator)
    protected fun assertThat(page: Page) = PlaywrightAssertions.assertThat(page)

    /** Signs in with the development sign-in form. */
    protected fun signIn(email: String = "admin@example.com") {
        page.navigate("/")
        page.locator("input[type=email]").fill(email)
        button("Continue").click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Incidents"))).isVisible()
    }

    protected fun button(name: String, exact: Boolean = false): Locator =
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(exact))

    /** A card found by its header title. */
    protected fun card(title: String): Locator =
        page.locator("div.rounded-2xl").filter(Locator.FilterOptions().setHas(page.locator("div.eyebrow").getByText(title, Locator.GetByTextOptions().setExact(true))))

    protected fun unique(prefix: String) = "$prefix ${System.nanoTime().toString().takeLast(6)}"
}
