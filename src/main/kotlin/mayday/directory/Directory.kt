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
    fun get(id: String): Project?
    fun bySlug(slug: String): Project?
    fun list(): List<Project>
    fun save(project: Project)
    fun delete(id: String)
}

interface TeamRepository {
    fun get(id: String): Team?
    fun bySlug(slug: String): Team?
    fun list(): List<Team>
    fun save(team: Team)
    fun delete(id: String)
}

private val SLUG = Regex("^[a-z0-9][a-z0-9-]{0,62}$")

fun slugify(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(63).ifEmpty { "x" }

class DirectoryService(
    private val projects: ProjectRepository,
    private val teams: TeamRepository,
    private val clock: Clock,
) {
    fun projects() = projects.list().sortedBy { it.name.lowercase() }
    fun teams() = teams.list().sortedBy { it.name.lowercase() }
    fun team(id: String) = teams.get(id) ?: teams.bySlug(id) ?: throw NotFound("Team")
    fun project(id: String) = projects.get(id) ?: projects.bySlug(id) ?: throw NotFound("Project")
    fun teamOrNull(id: String) = teams.get(id) ?: teams.bySlug(id)

    fun createProject(name: String, slug: String?, description: String = ""): Project {
        val s = validSlug(slug ?: slugify(name))
        if (projects.bySlug(s) != null) throw Conflict("A project with slug '$s' exists")
        if (name.isBlank()) throw Invalid("Name is required")
        return Project(Ids.new(), s, name.trim(), description, clock.now()).also(projects::save)
    }

    fun updateProject(id: String, name: String?, description: String?): Project {
        val p = project(id)
        return p.copy(name = name?.trim()?.ifEmpty { null } ?: p.name, description = description ?: p.description)
            .also(projects::save)
    }

    fun deleteProject(id: String) {
        val p = project(id)
        if (teams.list().any { it.projectId == p.id }) throw Conflict("Move or delete the teams of this project first")
        projects.delete(p.id)
    }

    fun createTeam(name: String, slug: String?, projectId: String?, description: String = ""): Team {
        val s = validSlug(slug ?: slugify(name))
        if (teams.bySlug(s) != null) throw Conflict("A team with slug '$s' exists")
        if (name.isBlank()) throw Invalid("Name is required")
        val pid = projectId?.let { project(it).id }
        return Team(Ids.new(), s, name.trim(), pid, description, clock.now()).also(teams::save)
    }

    fun updateTeam(id: String, name: String?, projectId: String?, description: String?): Team {
        val t = team(id)
        val pid = when {
            projectId == null -> t.projectId
            projectId.isEmpty() -> null
            else -> project(projectId).id
        }
        return t.copy(name = name?.trim()?.ifEmpty { null } ?: t.name, projectId = pid, description = description ?: t.description)
            .also(teams::save)
    }

    fun deleteTeam(id: String) = teams.delete(team(id).id)

    private fun validSlug(s: String): String {
        if (!SLUG.matches(s)) throw Invalid("Slug must use lowercase letters, digits and dashes")
        return s
    }
}
