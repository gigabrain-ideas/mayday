package mayday

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import mayday.adapters.memory.MemoryStore
import mayday.directory.DirectoryService
import mayday.policy.Keys
import mayday.policy.ORG_ID
import mayday.policy.PolicyService
import mayday.policy.Scope
import mayday.policy.SettingRepository
import mayday.policy.SettingValue
import mayday.shared.Actor
import mayday.shared.Clock
import mayday.shared.InProcessEventBus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every replica caches the policy. A change on another replica must win over a read that was in flight when the change arrived. */
class PolicyCacheTest {
    private val store = MemoryStore(null)

    /** The first read takes its snapshot, then waits, like a slow database query. */
    private inner class SlowFirstRead : SettingRepository by store.settings {
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        @Volatile private var first = true
        override suspend fun all(): List<SettingValue> {
            val snapshot = store.settings.all()
            if (first) { first = false; reading.complete(Unit); release.await() }
            return snapshot
        }
    }

    @Test
    fun `a change that arrives during a read is not lost`() = suspendTest {
        val repo = SlowFirstRead()
        val policy = PolicyService(repo, store.changeRequests, DirectoryService(store.projects, store.teams, Clock.system), InProcessEventBus(), Clock.system)
        val read = async(Dispatchers.IO) { policy.bool(Keys.AGENT_AUTO_REMEDIATION, emptyList()) }
        repo.reading.await()
        store.settings.save(SettingValue(Scope.ORG, ORG_ID, Keys.AGENT_AUTO_REMEDIATION, "true", updatedBy = Actor.system, updatedAt = Instant.now()))
        policy.invalidate()
        repo.release.complete(Unit)
        read.await()
        assertEquals(true, policy.bool(Keys.AGENT_AUTO_REMEDIATION, emptyList()), "the change on another replica wins")
    }
}
