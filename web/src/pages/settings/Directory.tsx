import { useState } from "react";
import { Copy, KeyRound, Plus, Trash2, Users } from "lucide-react";
import { items, rpc, type ApiToken, type IssuedToken, type OrgRole, type Page, type TeamRole, type TokenScope, type User } from "@/lib/api";
import { useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago, titleCase } from "@/lib/utils";
import { Badge, Button, Card, CardHeader, Dialog, DialogContent, Empty, Field, Input, NativeSelect, Spinner, Switch, Table, Td, Th } from "@/components/ui";

export function DirectoryPage() {
  const { teams, projects, isAdmin, reloadDirectory } = useSession();
  const toast = useToast();
  const [team, setTeam] = useState({ name: "", projectId: "" });
  const [project, setProject] = useState("");
  const run = (p: Promise<unknown>) => p.then(reloadDirectory).catch(toast.error);
  return (
    <div className="grid gap-5 xl:grid-cols-2">
      <Card>
        <CardHeader title="Projects" />
        {isAdmin && (
          <form className="flex gap-2 border-b p-4" onSubmit={(e) => { e.preventDefault(); run(rpc.directory.createProject({ name: project })); setProject(""); }}>
            <Input value={project} onChange={(e) => setProject(e.target.value)} placeholder="New project name" required />
            <Button type="submit" variant="outline"><Plus /> Add</Button>
          </form>
        )}
        <div className="divide-y">
          {projects.map((p) => (
            <div key={p.id} className="flex items-center gap-3 px-4 py-3">
              <div className="flex-1"><div>{p.name}</div><div className="text-[12px] text-muted-foreground">{p.slug} · {teams.filter((t) => t.projectId === p.id).length} teams</div></div>
              {isAdmin && <Button variant="ghost" size="icon" aria-label="Delete project" onClick={() => toast.undoable(`Project ${p.name} will be deleted.`, () => rpc.directory.deleteProject({ id: p.id }).then(reloadDirectory))}><Trash2 /></Button>}
            </div>
          ))}
          {projects.length === 0 && <Empty title="No projects">Projects group teams and share settings.</Empty>}
        </div>
      </Card>
      <Card>
        <CardHeader title="Teams" />
        {isAdmin && (
          <form className="flex flex-wrap gap-2 border-b p-4" onSubmit={(e) => { e.preventDefault(); run(rpc.directory.createTeam({ name: team.name, projectId: team.projectId || undefined })); setTeam({ name: "", projectId: "" }); }}>
            <Input value={team.name} onChange={(e) => setTeam({ ...team, name: e.target.value })} placeholder="New team name" required className="flex-1" />
            <NativeSelect value={team.projectId} onChange={(e) => setTeam({ ...team, projectId: e.target.value })} className="w-44">
              <option value="">No project</option>
              {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </NativeSelect>
            <Button type="submit" variant="outline"><Plus /> Add</Button>
          </form>
        )}
        <div className="divide-y">
          {teams.map((t) => (
            <div key={t.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="flex-1"><div>{t.name}</div><div className="text-[12px] text-muted-foreground">{t.slug}</div></div>
              {isAdmin ? (
                <NativeSelect value={t.projectId ?? ""} onChange={(e) => run(rpc.directory.updateTeam({ id: t.id, name: t.name, description: t.description, projectId: e.target.value }))} className="w-44" aria-label="Project">
                  <option value="">No project</option>
                  {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </NativeSelect>
              ) : <span className="text-muted-foreground">{projects.find((p) => p.id === t.projectId)?.name}</span>}
              {isAdmin && <Button variant="ghost" size="icon" aria-label="Delete team" onClick={() => toast.undoable(`Team ${t.name} will be deleted.`, () => rpc.directory.deleteTeam({ id: t.id }).then(reloadDirectory))}><Trash2 /></Button>}
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}

export function UsersPage() {
  const { isAdmin, teams, me, reloadDirectory } = useSession();
  const toast = useToast();
  const [q, setQ] = useState("");
  const { data, reload } = useQuery(() => rpc.users.listUsers<Page<User>>({ limit: 200, q: q || undefined }), [q]);
  const [editing, setEditing] = useState<User | null>(null);
  const [invite, setInvite] = useState({ email: "", name: "", role: "MEMBER" as OrgRole });
  const update = async (id: string, body: { role?: string; teams?: Record<string, TeamRole>; replaceTeams?: boolean; disabled?: boolean }) => {
    try { await rpc.users.updateUser({ id, ...body }); reload(); reloadDirectory(); } catch (e) { toast.error(e); }
  };
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by name or email" className="w-72" />
        {isAdmin && (
          <form className="ml-auto flex flex-wrap gap-2" onSubmit={async (e) => {
            e.preventDefault();
            try { await rpc.users.createUser(invite); setInvite({ email: "", name: "", role: "MEMBER" }); reload(); reloadDirectory(); } catch (err) { toast.error(err); }
          }}>
            <Input type="email" value={invite.email} onChange={(e) => setInvite({ ...invite, email: e.target.value })} placeholder="email@company.com" required className="w-56" />
            <NativeSelect value={invite.role} onChange={(e) => setInvite({ ...invite, role: e.target.value as OrgRole })} className="w-32">
              <option value="VIEWER">Viewer</option><option value="MEMBER">Member</option><option value="ADMIN">Admin</option>
            </NativeSelect>
            <Button type="submit" variant="outline"><Plus /> Add user</Button>
          </form>
        )}
      </div>
      <p className="text-[12px] text-muted-foreground">Users from single sign-on are created at their first login. Viewers can read. Members can manage incidents and start agents. Admins configure everything and enforce settings.</p>
      <Card>
        {!data ? <div className="flex justify-center p-10"><Spinner /></div> : (
          <Table>
            <thead><tr><Th>Name</Th><Th>Email</Th><Th>Role</Th><Th>Teams</Th><Th /></tr></thead>
            <tbody>
              {data.items.map((u) => (
                <tr key={u.id} className={u.disabled ? "opacity-50" : ""}>
                  <Td>{u.name}</Td>
                  <Td className="text-muted-foreground">{u.email}</Td>
                  <Td>
                    {isAdmin && u.id !== me.user.id ? (
                      <NativeSelect value={u.orgRole} onChange={(e) => update(u.id, { role: e.target.value })} className="w-32" aria-label="Role">
                        <option value="VIEWER">Viewer</option><option value="MEMBER">Member</option><option value="ADMIN">Admin</option>
                      </NativeSelect>
                    ) : <Badge tone={u.orgRole === "ADMIN" ? "solid" : "neutral"}>{titleCase(u.orgRole)}</Badge>}
                  </Td>
                  <Td className="text-muted-foreground">
                    {Object.entries(u.teams).map(([t, r]) => `${teams.find((x) => x.id === t)?.name ?? "?"}${r === "ADMIN" ? " (admin)" : ""}`).join(", ") || "—"}
                  </Td>
                  <Td className="text-right">{isAdmin && <Button size="sm" variant="ghost" onClick={() => setEditing(u)}><Users /> Teams</Button>}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      <Dialog open={!!editing} onOpenChange={(o) => !o && setEditing(null)}>
        {editing && (
          <DialogContent title={`Teams of ${editing.name}`} description="Team admins manage settings of their team.">
            <div className="space-y-2">
              {teams.map((t) => (
                <div key={t.id} className="flex items-center justify-between gap-3">
                  <span>{t.name}</span>
                  <NativeSelect value={editing.teams[t.id] ?? ""} className="w-36" aria-label={`Role in ${t.name}`}
                    onChange={(e) => {
                      const next = { ...editing.teams };
                      if (e.target.value) next[t.id] = e.target.value as TeamRole; else delete next[t.id];
                      setEditing({ ...editing, teams: next });
                    }}>
                    <option value="">Not a member</option><option value="MEMBER">Member</option><option value="ADMIN">Team admin</option>
                  </NativeSelect>
                </div>
              ))}
              <div className="flex justify-between gap-2 pt-4">
                <Button variant="destructive" size="sm" onClick={() => { update(editing.id, { disabled: !editing.disabled }); setEditing(null); }}>{editing.disabled ? "Enable account" : "Disable account"}</Button>
                <Button onClick={() => { update(editing.id, { teams: editing.teams, replaceTeams: true }); setEditing(null); }}>Save</Button>
              </div>
            </div>
          </DialogContent>
        )}
      </Dialog>
    </div>
  );
}

export function SecretOnce({ label, value }: { label: string; value: string }) {
  const toast = useToast();
  return (
    <Field label={label} hint="Copy it now. Mayday stores only a hash and cannot show it again.">
      <div className="flex gap-2">
        <Input readOnly value={value} onFocus={(e) => e.target.select()} className="font-mono read-only:opacity-100" />
        <Button variant="outline" size="icon" aria-label="Copy" onClick={() => navigator.clipboard.writeText(value).then(() => toast.show("Copied."))}><Copy /></Button>
      </div>
    </Field>
  );
}

export function TokensPage() {
  const { isAdmin } = useSession();
  const toast = useToast();
  const { data, reload } = useQuery(() => items(rpc.users.listTokens<Page<ApiToken>>()), []);
  const [form, setForm] = useState({ name: "", scopes: ["READ"] as TokenScope[], ttlDays: "90" });
  const [issued, setIssued] = useState<IssuedToken | null>(null);
  const all: TokenScope[] = ["READ", "WRITE", "ALERTS_INGEST", ...(isAdmin ? ["ADMIN" as TokenScope] : [])];
  return (
    <div className="grid gap-5 xl:grid-cols-[1fr_380px]">
      <Card>
        <CardHeader title="Your API tokens" />
        {!data ? <div className="flex justify-center p-10"><Spinner /></div> : data.length === 0 ? (
          <Empty icon={<KeyRound />} title="No tokens">Tokens let scripts, alert sources and MCP clients act as you.</Empty>
        ) : (
          <div className="divide-y">
            {data.map((t) => (
              <div key={t.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <div className="flex-1">
                  <div>{t.name} <code className="text-muted-foreground">{t.prefix}…</code></div>
                  <div className="text-[12px] text-muted-foreground">created {ago(t.createdAt)}{t.expiresAt ? ` · expires ${new Date(t.expiresAt).toLocaleDateString()}` : " · no expiry"}</div>
                </div>
                <div className="flex gap-1">{t.scopes.map((s) => <Badge key={s} tone="outline">{s}</Badge>)}</div>
                <Button variant="ghost" size="icon" aria-label="Revoke" onClick={() => toast.undoable(`Token ${t.name} will be revoked.`, () => rpc.users.revokeToken({ id: t.id }).then(reload))}><Trash2 /></Button>
              </div>
            ))}
          </div>
        )}
      </Card>
      <Card className="p-4">
        <form className="space-y-4" onSubmit={async (e) => {
          e.preventDefault();
          try {
            setIssued(await rpc.users.issueToken<IssuedToken>({ name: form.name, scopes: form.scopes, ttlDays: form.ttlDays ? Number(form.ttlDays) : undefined }));
            setForm({ ...form, name: "" });
            reload();
          } catch (err) { toast.error(err); }
        }}>
          <div className="eyebrow">New token</div>
          <Field label="Name"><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Prometheus Alertmanager" required /></Field>
          <Field label="Scopes">
            <div className="flex flex-wrap gap-3">
              {all.map((s) => (
                <label key={s} className="flex items-center gap-1.5">
                  <input type="checkbox" checked={form.scopes.includes(s)} onChange={(e) => setForm({ ...form, scopes: e.target.checked ? [...form.scopes, s] : form.scopes.filter((x) => x !== s) })} className="accent-current" />
                  {s.toLowerCase().replace("_", " ")}
                </label>
              ))}
            </div>
          </Field>
          <Field label="Expires after (days)" hint="Empty means no expiry.">
            <Input type="number" min={1} value={form.ttlDays} onChange={(e) => setForm({ ...form, ttlDays: e.target.value })} />
          </Field>
          <Button type="submit" className="w-full"><KeyRound /> Create token</Button>
          {issued && <SecretOnce label="New token" value={issued.secret} />}
        </form>
      </Card>
      {isAdmin && <AgentAdminCard />}
    </div>
  );
}

function AgentAdminCard() {
  const { me, reloadMe } = useSession();
  const toast = useToast();
  const orgAllowed = !!me.agentAdminOrgAllowed;
  const on = !!me.user.allowAgentAdmin;
  return (
    <Card className="p-4 xl:col-span-2">
      <div className="flex items-start gap-4">
        <div className="flex-1 space-y-1">
          <div className="eyebrow">Agents and your admin rights</div>
          <p>When this is on and an agent exchanges your token, the agent keeps your admin scope. It can then change settings, users and roles as you.</p>
          <p className="text-[12px] text-muted-foreground">
            {orgAllowed
              ? on ? "On. Turn it off when you do not need it." : "Off. Agents get read and write access only."
              : "The organization does not allow it. An administrator can change this in Policies (Admin rights through token exchange). When the organization turns it off, everyone must opt in again."}
          </p>
        </div>
        <Switch
          aria-label="Let agents keep my admin scope"
          checked={on}
          disabled={!orgAllowed && !on}
          onCheckedChange={(allow) => rpc.users.setAgentAdmin({ allow }).then(reloadMe).catch(toast.error)}
        />
      </div>
    </Card>
  );
}
