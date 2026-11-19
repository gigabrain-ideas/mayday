import { useState } from "react";
import { Bot, Plug, Plus, RotateCw, Trash2 } from "lucide-react";
import {
  ACTION_LABEL, AGENT_ACTIONS, api, SEVERITIES, type AgentAction, type AgentConfig, type AgentCredentials, type AgentRun, type AgentTrigger, type LlmView,
  type McpServerView, type OrgRole,
} from "@/lib/api";
import { useLive, useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago, titleCase } from "@/lib/utils";
import { Badge, Button, Card, CardHeader, Dialog, DialogContent, Empty, Field, Input, MultiSelect, NativeSelect, Spinner, Switch, Table, Td, Textarea, Th } from "@/components/ui";
import { RunStatusBadge } from "@/components/common";
import { SecretOnce } from "./Directory";

type Draft = Omit<AgentConfig, "id" | "secretSealed" | "createdAt" | "updatedAt">;
const blank: Draft = { name: "", description: "", kind: "EXTERNAL", delivery: "PUSH", endpointUrl: "", actions: ["ANALYZE"], triggers: [], role: "VIEWER", enabled: true, instructions: "" };

export function AgentsPage() {
  const { isAdmin, me } = useSession();
  const toast = useToast();
  const { data, reload } = useQuery<AgentConfig[]>("/agents");
  const runs = useQuery<AgentRun[]>("/agent-runs?limit=25");
  useLive((t) => t === "agent.changed", reload);
  useLive((t) => t === "agent_run.updated", runs.reload);
  const [edit, setEdit] = useState<{ id?: string; draft: Draft } | null>(null);
  const [creds, setCreds] = useState<AgentCredentials | null>(null);

  const save = async () => {
    if (!edit) return;
    try {
      if (edit.id) await api.put(`/agents/${edit.id}`, edit.draft);
      else setCreds(await api.post<AgentCredentials>("/agents", edit.draft));
      setEdit(null);
      reload();
    } catch (e) { toast.error(e); }
  };

  return (
    <div className="space-y-5">
      {!me.agentReady && (
        <Card className="flex items-center gap-3 p-4">
          <Bot className="size-5" />
          <p className="flex-1 text-muted-foreground">The built-in agent needs an LLM provider. {isAdmin ? "Configure it under LLM provider." : "Ask an administrator to configure it."} External agents work without it.</p>
        </Card>
      )}
      <Card>
        <CardHeader title="Agents" action={isAdmin && <Button size="sm" onClick={() => setEdit({ draft: { ...blank } })}><Plus /> Add external agent</Button>} />
        {!data ? <div className="flex justify-center p-10"><Spinner /></div> : (
          <div className="divide-y">
            {data.map((a) => (
              <div key={a.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <Bot className="size-5" />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{a.name}</span>
                    <Badge tone="outline">{a.kind === "INTERNAL" ? "Built-in" : a.delivery === "PUSH" ? "Push" : "Pull"}</Badge>
                    {!a.enabled && <Badge tone="warning">Disabled</Badge>}
                    <Badge tone="outline">{titleCase(a.role)} identity</Badge>
                  </div>
                  <div className="mt-1 text-[12px] text-muted-foreground">
                    {a.actions.map((x) => ACTION_LABEL[x]).join(" · ")}
                    {a.triggers.length > 0 && ` — automatic: ${a.triggers.map((t) => `${ACTION_LABEL[t.action]} from ${t.minSeverity}`).join(", ")}`}
                  </div>
                </div>
                {isAdmin && (
                  <div className="flex gap-1">
                    <Button size="sm" variant="ghost" onClick={() => setEdit({ id: a.id, draft: { ...a } })}>Edit</Button>
                    {a.kind === "EXTERNAL" && <Button size="sm" variant="ghost" onClick={() => api.post<AgentCredentials>(`/agents/${a.id}/credentials`).then(setCreds).catch(toast.error)}><RotateCw /> Credentials</Button>}
                    {a.kind === "EXTERNAL" && <Button size="icon" variant="ghost" aria-label="Delete" onClick={() => toast.undoable(`${a.name} will be deleted.`, () => api.del(`/agents/${a.id}`).then(reload))}><Trash2 /></Button>}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </Card>
      <Card>
        <CardHeader title="Recent runs" />
        {!runs.data?.length ? <Empty title="No runs yet" /> : (
          <Table>
            <thead><tr><Th>Agent</Th><Th>Action</Th><Th>Subject</Th><Th>Status</Th><Th>Trigger</Th><Th>Started</Th></tr></thead>
            <tbody>
              {runs.data.map((r) => (
                <tr key={r.id}>
                  <Td>{r.agentName}</Td><Td>{ACTION_LABEL[r.action]}</Td>
                  <Td><a className="hover:underline" href={r.subjectKind === "INCIDENT" ? `/incidents/${r.subjectLabel}` : `/alerts/${r.subjectId}`}>{r.subjectLabel}</a></Td>
                  <Td><RunStatusBadge status={r.status} /></Td><Td className="text-muted-foreground">{r.trigger.toLowerCase()}</Td><Td className="text-muted-foreground">{ago(r.createdAt)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      <Dialog open={!!edit} onOpenChange={(o) => !o && setEdit(null)}>
        {edit && <AgentForm draft={edit.draft} isNew={!edit.id} onChange={(d) => setEdit({ ...edit, draft: d })} onSave={save} />}
      </Dialog>
      <Dialog open={!!creds} onOpenChange={(o) => !o && setCreds(null)}>
        {creds && (
          <DialogContent title={`Credentials for ${creds.agent.name}`} description="Give these to the agent. They are shown once.">
            <div className="space-y-4">
              {creds.token && <SecretOnce label="Agent identity token (Bearer, for MCP and the API)" value={creds.token.secret} />}
              {creds.signingSecret && creds.agent.delivery === "PUSH" && <SecretOnce label="Webhook signing secret" value={creds.signingSecret} />}
              <p className="text-[12px] text-muted-foreground">
                Push agents receive POST requests with the header X-Mayday-Signature: sha256=HMAC(secret, timestamp + "." + body). Pull agents call
                POST /api/v1/agent-runs/claim or the MCP tool claim_agent_runs. Each run carries a delegated token when a person started it.
              </p>
            </div>
          </DialogContent>
        )}
      </Dialog>
    </div>
  );
}

function AgentForm({ draft, isNew, onChange, onSave }: { draft: Draft; isNew: boolean; onChange: (d: Draft) => void; onSave: () => void }) {
  const { teams } = useSession();
  const internal = draft.kind === "INTERNAL";
  const setTrigger = (i: number, t: Partial<AgentTrigger>) => onChange({ ...draft, triggers: draft.triggers.map((x, j) => (j === i ? { ...x, ...t } : x)) });
  return (
    <DialogContent title={isNew ? "Add external agent" : `Edit ${draft.name}`} className="max-w-2xl"
      description={internal ? "The built-in agent uses the LLM provider and the MCP tool gateway." : "An external agent receives work by webhook (push) or asks for it (pull), and reports back through MCP or the API."}>
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); onSave(); }}>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Name"><Input value={draft.name} onChange={(e) => onChange({ ...draft, name: e.target.value })} required /></Field>
          <Field label="Own identity role" hint="Used for automatic runs. Manual runs use the role of the person.">
            <NativeSelect value={draft.role} onChange={(e) => onChange({ ...draft, role: e.target.value as OrgRole })}>
              <option value="VIEWER">Viewer (read)</option><option value="MEMBER">Member (read and write)</option><option value="ADMIN">Admin</option>
            </NativeSelect>
          </Field>
        </div>
        <Field label="Description"><Input value={draft.description} onChange={(e) => onChange({ ...draft, description: e.target.value })} /></Field>
        {!internal && (
          <div className="grid gap-3 sm:grid-cols-[160px_1fr]">
            <Field label="Delivery">
              <NativeSelect value={draft.delivery} onChange={(e) => onChange({ ...draft, delivery: e.target.value as Draft["delivery"] })}>
                <option value="PUSH">Push (webhook)</option><option value="PULL">Pull (agent polls)</option>
              </NativeSelect>
            </Field>
            {draft.delivery === "PUSH" && <Field label="Endpoint URL"><Input type="url" value={draft.endpointUrl ?? ""} onChange={(e) => onChange({ ...draft, endpointUrl: e.target.value })} placeholder="https://agent.internal/mayday" required /></Field>}
          </div>
        )}
        <Field label="Actions">
          <div className="flex flex-wrap gap-3">
            {AGENT_ACTIONS.map((a) => (
              <label key={a} className="flex items-center gap-1.5">
                <input type="checkbox" className="accent-current" checked={draft.actions.includes(a)}
                  onChange={(e) => onChange({ ...draft, actions: e.target.checked ? [...draft.actions, a] : draft.actions.filter((x) => x !== a) })} />
                {ACTION_LABEL[a]}
              </label>
            ))}
          </div>
        </Field>
        <Field label="Automatic triggers" hint="When a matching alert fires, the agent starts by itself. Team policy can turn this off.">
          <div className="space-y-2">
            {draft.triggers.map((t, i) => (
              <div key={i} className="flex flex-wrap items-center gap-2">
                <NativeSelect value={t.action} onChange={(e) => setTrigger(i, { action: e.target.value as AgentAction })} className="w-44">
                  {draft.actions.map((a) => <option key={a} value={a}>{ACTION_LABEL[a]}</option>)}
                </NativeSelect>
                <span className="text-muted-foreground">for alerts from</span>
                <NativeSelect value={t.minSeverity} onChange={(e) => setTrigger(i, { minSeverity: e.target.value as AgentTrigger["minSeverity"] })} className="w-24">
                  {SEVERITIES.map((s) => <option key={s}>{s}</option>)}
                </NativeSelect>
                <MultiSelect label="Teams" allLabel="All" value={t.teamIds} onChange={(v) => setTrigger(i, { teamIds: v })} options={teams.map((x) => ({ value: x.id, label: x.name }))} />
                <Button type="button" variant="ghost" size="icon" aria-label="Remove trigger" onClick={() => onChange({ ...draft, triggers: draft.triggers.filter((_, j) => j !== i) })}><Trash2 /></Button>
              </div>
            ))}
            <Button type="button" variant="ghost" size="sm" disabled={!draft.actions.length}
              onClick={() => onChange({ ...draft, triggers: [...draft.triggers, { event: "ALERT_CREATED", minSeverity: "SEV2", teamIds: [], action: draft.actions[0] }] })}><Plus /> Add trigger</Button>
          </div>
        </Field>
        {internal && (
          <Field label="Organization instructions" hint="Added to the system prompt. For example: our services run on Kubernetes in eu-west-1.">
            <Textarea value={draft.instructions} onChange={(e) => onChange({ ...draft, instructions: e.target.value })} />
          </Field>
        )}
        <div className="flex items-center justify-between border-t pt-4">
          <label className="flex items-center gap-2"><Switch checked={draft.enabled} onCheckedChange={(c) => onChange({ ...draft, enabled: c })} /> Enabled</label>
          <Button type="submit">Save</Button>
        </div>
      </form>
    </DialogContent>
  );
}

export function LlmPage() {
  const toast = useToast();
  const { reloadMe } = useSession();
  const { data, setData } = useQuery<LlmView>("/llm");
  const [key, setKey] = useState("");
  if (!data) return <div className="flex justify-center p-10"><Spinner /></div>;
  const save = async (body: Record<string, unknown>) => {
    try { setData(await api.put<LlmView>("/llm", body)); reloadMe(); toast.show("LLM provider saved.", "success"); } catch (e) { toast.error(e); }
  };
  return (
    <Card className="max-w-2xl p-5">
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); save({ model: data.model, effort: data.effort, baseUrl: data.baseUrl, enabled: data.enabled, apiKey: key || undefined }); setKey(""); }}>
        <div className="flex items-center gap-2">
          <span className="font-medium">Anthropic</span>
          {data.ready ? <Badge tone="success">Ready</Badge> : <Badge tone="warning">Not ready</Badge>}
        </div>
        <p className="text-muted-foreground">The built-in agent uses this provider to analyze alerts, keep timelines complete, and draft postmortems and runbooks. Mayday stores the key encrypted.</p>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Model"><Input value={data.model} onChange={(e) => setData({ ...data, model: e.target.value })} /></Field>
          <Field label="Effort">
            <NativeSelect value={data.effort} onChange={(e) => setData({ ...data, effort: e.target.value })}>
              {["low", "medium", "high", "xhigh", "max"].map((x) => <option key={x}>{x}</option>)}
            </NativeSelect>
          </Field>
        </div>
        <Field label="API key" hint={data.hasApiKey ? "A key is set. Enter a new one to replace it." : "Or set ANTHROPIC_API_KEY on the server."}>
          <Input type="password" value={key} onChange={(e) => setKey(e.target.value)} placeholder={data.hasApiKey ? "••••••••" : "sk-ant-..."} autoComplete="off" />
        </Field>
        <Field label="Base URL (optional)" hint="For a gateway or proxy in your network."><Input value={data.baseUrl} onChange={(e) => setData({ ...data, baseUrl: e.target.value })} placeholder="https://api.anthropic.com" /></Field>
        <div className="flex items-center justify-between border-t pt-4">
          <label className="flex items-center gap-2"><Switch checked={data.enabled} onCheckedChange={(c) => setData({ ...data, enabled: c })} /> Enabled</label>
          <Button type="submit">Save</Button>
        </div>
      </form>
    </Card>
  );
}

export function McpPage() {
  const { isAdmin } = useSession();
  const toast = useToast();
  const { data, reload } = useQuery<McpServerView[]>("/mcp-servers");
  const [form, setForm] = useState({ name: "", url: "", headerName: "Authorization", credential: "", toolAllowlist: "" });
  const origin = window.location.origin;
  return (
    <div className="grid gap-5 xl:grid-cols-2">
      <Card className="p-5">
        <div className="eyebrow mb-3">Mayday MCP server</div>
        <p className="mb-3 text-muted-foreground">Agents connect to Mayday with the Streamable HTTP transport. Read tools are always on. Write tools follow the setting <code>mcp.write_enabled</code> and the RBAC of the token.</p>
        <Field label="Endpoint"><Input readOnly value={`${origin}/mcp`} onFocus={(e) => e.target.select()} /></Field>
        <pre className="mt-4 overflow-x-auto rounded-lg bg-muted p-3 text-[12px]">{`{
  "mcpServers": {
    "mayday": {
      "type": "http",
      "url": "${origin}/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}`}</pre>
        <p className="mt-3 text-[12px] text-muted-foreground">
          Use a personal API token to act as yourself, an agent identity token to act as the agent, or a delegated token from a run (or from
          POST /oauth/token with token exchange) to act on behalf of a person. A JWT access token from your SSO provider also works.
        </p>
      </Card>
      <Card>
        <CardHeader title="Tool gateway" />
        <p className="px-4 pt-3 text-muted-foreground">MCP servers that the built-in agent can use, for example your observability, Git or ticket systems.</p>
        <div className="divide-y">
          {data?.map((s) => (
            <div key={s.id} className="flex items-center gap-3 px-4 py-3">
              <Plug className="size-4" />
              <div className="min-w-0 flex-1"><div>{s.name} {!s.enabled && <Badge tone="warning">Disabled</Badge>}</div><div className="truncate text-[12px] text-muted-foreground">{s.url}{s.toolAllowlist.length ? ` · ${s.toolAllowlist.length} tools allowed` : " · all tools"}</div></div>
              {isAdmin && <Button size="icon" variant="ghost" aria-label="Remove" onClick={() => toast.undoable(`${s.name} will be removed.`, () => api.del(`/mcp-servers/${s.id}`).then(reload))}><Trash2 /></Button>}
            </div>
          ))}
          {data?.length === 0 && <Empty title="No MCP servers" />}
        </div>
        {isAdmin && (
          <form className="space-y-3 border-t p-4" onSubmit={async (e) => {
            e.preventDefault();
            try {
              await api.post("/mcp-servers", { ...form, credential: form.credential || undefined, toolAllowlist: form.toolAllowlist.split(",").map((x) => x.trim()).filter(Boolean) });
              setForm({ name: "", url: "", headerName: "Authorization", credential: "", toolAllowlist: "" });
              reload();
            } catch (err) { toast.error(err); }
          }}>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Name"><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="Grafana" /></Field>
              <Field label="URL"><Input type="url" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} required placeholder="https://mcp.internal/grafana" /></Field>
              <Field label="Auth header"><Input value={form.headerName} onChange={(e) => setForm({ ...form, headerName: e.target.value })} /></Field>
              <Field label="Credential"><Input type="password" value={form.credential} onChange={(e) => setForm({ ...form, credential: e.target.value })} placeholder="Bearer ..." autoComplete="off" /></Field>
            </div>
            <Field label="Allowed tools (optional)" hint="Comma separated. Empty allows all tools."><Input value={form.toolAllowlist} onChange={(e) => setForm({ ...form, toolAllowlist: e.target.value })} /></Field>
            <Button type="submit" variant="outline"><Plus /> Add MCP server</Button>
          </form>
        )}
      </Card>
    </div>
  );
}
