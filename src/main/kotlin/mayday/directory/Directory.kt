package mayday.directory

import kotlinx.serialization.Serializable
import mayday.shared.Clock
import mayday.shared.Conflict
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Ts

@Serializable
data class Project(
    val id: String,
    val slug: String,
    val name: String,
    val description: String = "",
    val createdAt: Ts,
)

@Serializable
data class Team(
    val id: String,
    val slug: String,
    val name: String,
    val projectId: String? = null,
    val description: String = "",
    val createdAt: Ts,
)

interface ProjectRepository {
    suspend fun get(id: String): Project?
    suspend fun bySlug(slug: String): Project?
    suspend fun list(): List<Project>
    suspend fun save(project: Project)
    suspend fun delete(id: String)
}

interface TeamRepository {
    suspend fun get(id: String): Team?
    suspend fun bySlug(slug: String): Team?
    suspend fun list(): List<Team>
    suspend fun save(team: Team)
    suspend fun delete(id: String)
}

private val SLUG = Regex("^[a-z0-9][a-z0-9-]{0,62}$")

fun slugify(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(63).ifEmpty { "x" }

class DirectoryService(
    private val projects: ProjectRepository,
    private val teams: TeamRepository,
    private val clock: Clock,
) {
    suspend fun projects() = projects.list().sortedBy { it.name.lowercase() }
    suspend fun teams() = teams.list().sortedBy { it.name.lowercase() }
    suspend fun team(id: String) = teams.get(id) ?: teams.bySlug(id) ?: throw NotFound("Team")
    suspend fun project(id: String) = projects.get(id) ?: projects.bySlug(id) ?: throw NotFound("Project")
    suspend fun teamOrNull(id: String) = teams.get(id) ?: teams.bySlug(id)

    suspend fun createProject(name: String, slug: String?, description: String = ""): Project {
        val s = validSlug(slug ?: slugify(name))
        if (projects.bySlug(s) != null) throw Conflict("A project with slug '$s' exists")
        if (name.isBlank()) throw Invalid("Name is required")
        return Project(Ids.new(), s, name.trim(), description, clock.now()).also { projects.save(it) }
    }

    suspend fun updateProject(id: String, name: String?, description: String?): Project {
        val p = project(id)
        return p.copy(name = name?.trim()?.ifEmpty { null } ?: p.name, description = description ?: p.description)
            .also { projects.save(it) }
    }

    suspend fun deleteProject(id: String) {
        val p = project(id)
        if (teams.list().any { it.projectId == p.id }) throw Conflict("Move or delete the teams of this project first")
        projects.delete(p.id)
    }

    suspend fun createTeam(name: String, slug: String?, projectId: String?, description: String = ""): Team {
        val s = validSlug(slug ?: slugify(name))
        if (teams.bySlug(s) != null) throw Conflict("A team with slug '$s' exists")
        if (name.isBlank()) throw Invalid("Name is required")
        val pid = projectId?.let { project(it).id }
        return Team(Ids.new(), s, name.trim(), pid, description, clock.now()).also { teams.save(it) }
    }

    suspend fun updateTeam(id: String, name: String?, projectId: String?, description: String?): Team {
        val t = team(id)
        val pid = when {
            projectId == null -> t.projectId
            projectId.isEmpty() -> null
            else -> project(projectId).id
        }
        return t.copy(name = name?.trim()?.ifEmpty { null } ?: t.name, projectId = pid, description = description ?: t.description)
            .also { teams.save(it) }
    }

    suspend fun deleteTeam(id: String) = teams.delete(team(id).id)

    private fun validSlug(s: String): String {
        if (!SLUG.matches(s)) throw Invalid("Slug must use lowercase letters, digits and dashes")
        return s
    }
}
