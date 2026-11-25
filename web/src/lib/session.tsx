import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { auth, items, rpc, type Me, type Page, type Project, type Team, type User } from "./api";
import { closeLive, useLive } from "./hooks";

interface Session {
  me: Me;
  teams: Team[];
  projects: Project[];
  users: User[];
  teamName(id: string): string;
  userName(id?: string | null): string;
  isAdmin: boolean;
  canWrite: boolean;
  reloadDirectory(): void;
  reloadMe(): void;
  logout(): void;
}

const Ctx = createContext<Session | null>(null);

export function SessionProvider({ me, onLogout, onReloadMe, children }: { me: Me; onLogout: () => void; onReloadMe: () => void; children: ReactNode }) {
  const [teams, setTeams] = useState<Team[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const reloadDirectory = useCallback(() => {
    items(rpc.directory.listTeams<Page<Team>>()).then(setTeams).catch(() => {});
    items(rpc.directory.listProjects<Page<Project>>()).then(setProjects).catch(() => {});
    items(rpc.users.listUsers<Page<User>>({ limit: 500 })).then(setUsers).catch(() => {});
  }, []);
  useEffect(reloadDirectory, [reloadDirectory]);
  useLive((t) => t === "agent.changed", onReloadMe);

  const value = useMemo<Session>(() => {
    const teamById = new Map(teams.map((t) => [t.id, t]));
    const userById = new Map(users.map((u) => [u.id, u]));
    return {
      me, teams, projects, users,
      teamName: (id) => teamById.get(id)?.name ?? id.slice(0, 8),
      userName: (id) => (id ? userById.get(id)?.name ?? "Unknown" : "—"),
      isAdmin: me.user.orgRole === "ADMIN",
      canWrite: me.user.orgRole !== "VIEWER",
      reloadDirectory,
      reloadMe: onReloadMe,
      logout: async () => {
        const r = await auth.logout().catch(() => ({ logoutUrl: "/" }));
        closeLive();
        onLogout();
        if (r.logoutUrl && r.logoutUrl !== "/") window.location.href = r.logoutUrl;
      },
    };
  }, [me, teams, projects, users, reloadDirectory, onLogout, onReloadMe]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession() {
  const c = useContext(Ctx);
  if (!c) throw new Error("SessionProvider missing");
  return c;
}

/** Team owners: team role ADMIN, project admin of the team's project, or administrator. */
export function useTeamOwner() {
  const { me, isAdmin } = useSession();
  return (team?: Team) => !!team && (isAdmin || me.user.teams[team.id] === "ADMIN" || (!!team.projectId && me.user.projectAdmin.includes(team.projectId)));
}
