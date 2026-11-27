import { useState } from "react";
import { Bot, Plug, Plus, RotateCw, Trash2, TriangleAlert } from "lucide-react";
import {
  ACTION_LABEL, AGENT_ACTIONS, items, rpc, SEVERITIES, type AgentAction, type AgentConfig, type AgentCredentials, type AgentRun, type AgentTrigger,
  type AuthStyle, type ClientAuth, type CredentialMode, type GrantType, type LlmInput, type LlmProvider, type LlmView, type McpServerView, type OrgRole, type Page,
} from "@/lib/api";
import { RELOAD_SPREAD, useLive, useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago, dateTime, titleCase } from "@/lib/utils";
import { Badge, Button, Card, CardHeader, Dialog, DialogContent, Empty, Field, Input, MultiSelect, NativeSelect, Spinner, Switch, Table, Td, Textarea, Th } from "@/components/ui";
import { RunStatusBadge, RunTrace } from "@/components/common";
import { SecretOnce } from "./Directory";

type Draft = Omit<AgentConfig, "id" | "secretSealed" | "createdAt" | "updatedAt">;
const blank: Draft = { name: "", description: "", kind: "EXTERNAL", delivery: "PUSH", endpointUrl: "", actions: ["ANALYZE"], triggers: [], role: "VIEWER", enabled: true, instructions: "" };

export function AgentsPage() {
  const { isAdmin, me } = useSession();
  const toast = useToast();
  const { data, reload } = useQuery(() => items(rpc.agents.listAgents<Page<AgentConfig>>()), []);
  const runs = useQuery(() => items(rpc.agentRuns.listRuns<Page<AgentRun>>({ limit: 25 })), []);
  useLive((t) => t === "agent.changed", reload, RELOAD_SPREAD);
  useLive((t) => t === "agent_run.updated", runs.reload, RELOAD_SPREAD);
  const [edit, setEdit] = useState<{ id?: string; draft: Draft } | null>(null);
  const [creds, setCreds] = useState<AgentCredentials | null>(null);

  const save = async () => {
    if (!edit) return;
    try {
      if (edit.id) await rpc.agents.updateAgent({ id: edit.id, agent: edit.draft });
      else setCreds(await rpc.agents.createAgent<AgentCredentials>(edit.draft));
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
                    {a.kind === "EXTERNAL" && <Button size="sm" variant="ghost" onClick={() => rpc.agents.rotateAgentCredentials<AgentCredentials>({ id: a.id }).then(setCreds).catch(toast.error)}><RotateCw /> Credentials</Button>}
                    {a.kind === "EXTERNAL" && <Button size="icon" variant="ghost" aria-label="Delete" onClick={() => toast.undoable(`${a.name} will be deleted.`, () => rpc.agents.deleteAgent({ id: a.id }).then(reload))}><Trash2 /></Button>}
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
            <thead><tr><Th>Agent</Th><Th>Action</Th><Th>Subject</Th><Th>Status</Th><Th>Trigger</Th><Th>Started</Th><Th>Trace</Th></tr></thead>
            <tbody>
              {runs.data.map((r) => (
                <tr key={r.id}>
                  <Td>{r.agentName}</Td><Td>{ACTION_LABEL[r.action]}</Td>
                  <Td><a className="hover:underline" href={r.subjectKind === "INCIDENT" ? `/incidents/${r.subjectLabel}` : `/alerts/${r.subjectId}`}>{r.subjectLabel}</a></Td>
                  <Td><RunStatusBadge status={r.status} /></Td><Td className="text-muted-foreground">{r.trigger.toLowerCase()}</Td><Td className="text-muted-foreground">{ago(r.createdAt)}</Td><Td><RunTrace traceId={r.traceId} /></Td>
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
                the gRPC method AgentRunService.ClaimRuns or the MCP tool claim_agent_runs. Each run carries a delegated token when a person started it.
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

const EFFORTS = ["low", "medium", "high", "xhigh", "max"];
const PROVIDER_LABEL: Record<LlmProvider, string> = { anthropic: "Anthropic", openai: "OpenAI-compatible" };
const defaultAuthStyle = (p: LlmProvider): AuthStyle => (p === "openai" ? "BEARER" : "API_KEY");
const EDITABLE = [
  "provider", "model", "baseUrl", "effort", "enabled", "authStyle", "credential", "tokenUrl", "issuer", "grant", "clientAuth", "clientId",
  "assertionFile", "tokenFile", "scope",
] as const;
type HeaderRow = { name: string; value: string };
const rowsOf = (h: Record<string, string>): HeaderRow[] => Object.entries(h).map(([name, value]) => ({ name, value }));
const mapOf = (rows: HeaderRow[]) => Object.fromEntries(rows.filter((r) => r.name.trim()).map((r) => [r.name.trim(), r.value]));

/** Editable name/value rows, for custom headers and token parameters. */
function PairRows({ rows, onChange, locked, namePlaceholder, valuePlaceholder, addLabel }: {
  rows: HeaderRow[]; onChange: (r: HeaderRow[]) => void; locked: boolean; namePlaceholder: string; valuePlaceholder?: string; addLabel: string;
}) {
  return (
    <div className="space-y-2">
      {rows.map((r, i) => (
        <div key={i} className="flex gap-2">
          <Input aria-label="Name" value={r.name} readOnly={locked} placeholder={namePlaceholder}
            onChange={(e) => onChange(rows.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
          <Input aria-label="Value" value={r.value} readOnly={locked} placeholder={valuePlaceholder}
            onChange={(e) => onChange(rows.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
          {!locked && <Button type="button" variant="ghost" size="icon" aria-label="Remove" onClick={() => onChange(rows.filter((_, j) => j !== i))}><Trash2 /></Button>}
        </div>
      ))}
      {!locked && <Button type="button" variant="ghost" size="sm" onClick={() => onChange([...rows, { name: "", value: "" }])}><Plus /> {addLabel}</Button>}
    </div>
  );
}

export function LlmPage() {
  const toast = useToast();
  const { reloadMe } = useSession();
  const { data, setData } = useQuery(() => rpc.agents.getLlm<LlmView>(), []);
  if (!data) return <div className="flex justify-center p-10"><Spinner /></div>;
  return (
    <LlmForm key={JSON.stringify(data)} view={data} onSaved={(v) => { setData(v); reloadMe(); toast.show("LLM provider saved.", "success"); }} onError={toast.error} />
  );
}

function LlmForm({ view, onSaved, onError }: { view: LlmView; onSaved: (v: LlmView) => void; onError: (e: unknown) => void }) {
  const [draft, setDraft] = useState<LlmView>({
    ...view, authStyle: view.authStyle ?? defaultAuthStyle(view.provider), credential: view.credential ?? "STATIC",
    grant: view.grant ?? "CLIENT_CREDENTIALS", clientAuth: view.clientAuth ?? "BASIC",
  });
  const [apiKey, setApiKey] = useState("");
  const [clientSecret, setClientSecret] = useState("");
  const [rows, setRows] = useState<HeaderRow[]>(rowsOf(view.headers));
  const [headersDirty, setHeadersDirty] = useState(false);
  const [params, setParams] = useState<HeaderRow[]>(rowsOf(view.tokenParams ?? {}));
  const [paramsDirty, setParamsDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const managed = (f: string) => view.managedFields.includes(f);
  const lockHint = (f: string, hint?: string) => (managed(f) ? "Set by the environment" : hint);
  const set = <K extends keyof LlmView>(k: K, v: LlmView[K]) => setDraft((d) => ({ ...d, [k]: v }));
  const paused = view.pausedUntil && new Date(view.pausedUntil).getTime() > Date.now() ? view.pausedUntil : undefined;

  const submit = async (extra: LlmInput = {}) => {
    const input: LlmInput = { ...extra };
    const target = input as Record<string, unknown>;
    for (const k of EDITABLE) if (!managed(k) && draft[k] !== view[k]) target[k] = draft[k];
    if (apiKey && !managed("apiKey")) input.apiKey = apiKey;
    if (clientSecret && !managed("clientSecret")) input.clientSecret = clientSecret;
    if (headersDirty && !managed("headers")) {
      input.headers = mapOf(rows);
      input.replaceHeaders = true;
    }
    if (paramsDirty && !managed("tokenParams")) {
      input.tokenParams = mapOf(params);
      input.replaceTokenParams = true;
    }
    setBusy(true);
    try { onSaved(await rpc.agents.updateLlm<LlmView>(input)); } catch (e) { onError(e); } finally { setBusy(false); }
  };

  const setProvider = (p: LlmProvider) => setDraft((d) => ({
    ...d, provider: p, authStyle: !managed("authStyle") && d.authStyle === defaultAuthStyle(d.provider) ? defaultAuthStyle(p) : d.authStyle,
  }));
  const editRows = (next: HeaderRow[]) => { setRows(next); setHeadersDirty(true); };
  const editParams = (next: HeaderRow[]) => { setParams(next); setParamsDirty(true); };
  const exchange = draft.grant === "TOKEN_EXCHANGE";
  const hasSecret = view.hasClientSecret || clientSecret !== "";
  const secretHint = (f: "apiKey" | "clientSecret", isSet: boolean, unset: string) =>
    managed(f) ? "Set by the environment" : isSet ? "A value is set. Enter a new one to replace it." : unset;
  const removeSecret = (f: "apiKey" | "clientSecret") => submit({ [f]: "" });

  return (
    <Card className="max-w-3xl p-5">
      <form className="space-y-5" onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <div className="flex items-center gap-2">
          <span className="font-medium">{PROVIDER_LABEL[view.provider] ?? view.provider}</span>
          {view.ready ? <Badge tone="success">Ready</Badge> : <Badge tone="warning">Not ready</Badge>}
          {paused && <Badge tone="warning">Paused</Badge>}
        </div>
        {paused && (
          <div role="status" className="flex gap-3 rounded-lg border border-warning/50 bg-warning-soft p-3">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" />
            <div>
              <p>The AI gateway budget of the agent is used up. The agent is paused until {dateTime(paused)}. Save these settings to resume it now.</p>
              {view.pauseReason && <p className="mt-1 text-[12px] text-muted-foreground">{view.pauseReason}</p>}
            </div>
          </div>
        )}
        <p className="text-muted-foreground">
          The built-in agent uses this provider to analyze alerts, keep timelines complete, and draft postmortems and runbooks. Mayday stores secrets
          encrypted and never shows them again. Incidents and alerts work without a provider.
        </p>

        <section className="space-y-3">
          <div className="eyebrow">Provider</div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="API" hint={lockHint("provider", "The protocol of the base URL.")}>
              <NativeSelect value={draft.provider} disabled={managed("provider")} onChange={(e) => setProvider(e.target.value as LlmProvider)}>
                <option value="anthropic">Anthropic</option><option value="openai">OpenAI-compatible</option>
              </NativeSelect>
            </Field>
            <Field label="Model" hint={lockHint("model")}>
              <Input value={draft.model} readOnly={managed("model")} onChange={(e) => set("model", e.target.value)} />
            </Field>
            <Field label="Base URL (optional)" hint={lockHint("baseUrl", "For an AI gateway or proxy in your network.")}>
              <Input value={draft.baseUrl} readOnly={managed("baseUrl")} onChange={(e) => set("baseUrl", e.target.value)}
                placeholder={draft.provider === "openai" ? "https://ai-gateway.example.com/v1" : "https://api.anthropic.com"} />
            </Field>
            <Field label="Effort" hint={lockHint("effort")}>
              <NativeSelect value={draft.effort} disabled={managed("effort")} onChange={(e) => set("effort", e.target.value)}>
                {EFFORTS.map((x) => <option key={x}>{x}</option>)}
              </NativeSelect>
            </Field>
          </div>
        </section>

        <section className="space-y-3 border-t pt-4">
          <div className="eyebrow">Authentication</div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Credential source" hint={lockHint("credential")}>
              <NativeSelect value={draft.credential} disabled={managed("credential")} onChange={(e) => set("credential", e.target.value as CredentialMode)}>
                <option value="NONE">None</option>
                <option value="STATIC">Static API key</option>
                <option value="OAUTH2">OAuth 2.0 token (workload identity)</option>
                <option value="FILE">Token file</option>
              </NativeSelect>
            </Field>
            {draft.credential !== "NONE" && (
              <Field label="Auth header" hint={lockHint("authStyle", `The default for ${PROVIDER_LABEL[draft.provider]} is ${defaultAuthStyle(draft.provider) === "BEARER" ? "Authorization: Bearer" : "x-api-key"}.`)}>
                <NativeSelect value={draft.authStyle} disabled={managed("authStyle")} onChange={(e) => set("authStyle", e.target.value as AuthStyle)}>
                  <option value="API_KEY">x-api-key</option><option value="BEARER">Authorization: Bearer</option>
                </NativeSelect>
              </Field>
            )}
          </div>
          {draft.credential === "STATIC" && (
            <Field label="API key" hint={secretHint("apiKey", view.hasApiKey, "Or set MAYDAY_LLM_API_KEY on the server.")}>
              <div className="flex gap-2">
                <Input type="password" value={apiKey} readOnly={managed("apiKey")} onChange={(e) => setApiKey(e.target.value)}
                  placeholder={view.hasApiKey ? "••••••••" : "sk-..."} autoComplete="off" />
                {view.hasApiKey && !managed("apiKey") && <Button type="button" variant="ghost" onClick={() => removeSecret("apiKey")}>Remove</Button>}
              </div>
            </Field>
          )}
          {draft.credential === "OAUTH2" && (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Grant" hint={lockHint("grant", exchange ? "RFC 8693. The token file is the subject token." : "RFC 6749. Client secret or a workload identity token as client assertion.")}>
                <NativeSelect value={draft.grant} disabled={managed("grant")} onChange={(e) => set("grant", e.target.value as GrantType)}>
                  <option value="CLIENT_CREDENTIALS">Client credentials</option><option value="TOKEN_EXCHANGE">Token exchange</option>
                </NativeSelect>
              </Field>
              <Field label="Issuer" hint={lockHint("issuer", "Mayday reads the token endpoint from the issuer metadata.")}>
                <Input value={draft.issuer} readOnly={managed("issuer")} onChange={(e) => set("issuer", e.target.value)} placeholder="https://idp.example.com" />
              </Field>
              <Field label="Token URL (optional)" hint={lockHint("tokenUrl", "Set it when the issuer publishes no metadata.")}>
                <Input value={draft.tokenUrl} readOnly={managed("tokenUrl")} onChange={(e) => set("tokenUrl", e.target.value)} placeholder="https://idp.example.com/oauth2/token" />
              </Field>
              <Field label={exchange ? "Client ID (optional)" : "Client ID"} hint={lockHint("clientId")}>
                <Input value={draft.clientId} readOnly={managed("clientId")} onChange={(e) => set("clientId", e.target.value)} />
              </Field>
              <Field label="Client secret" hint={secretHint("clientSecret", view.hasClientSecret, exchange ? "Optional." : "Leave empty to use a workload identity token.")}>
                <div className="flex gap-2">
                  <Input type="password" value={clientSecret} readOnly={managed("clientSecret")} onChange={(e) => setClientSecret(e.target.value)}
                    placeholder={view.hasClientSecret ? "••••••••" : ""} autoComplete="off" />
                  {view.hasClientSecret && !managed("clientSecret") && <Button type="button" variant="ghost" onClick={() => removeSecret("clientSecret")}>Remove</Button>}
                </div>
              </Field>
              {hasSecret && (
                <Field label="Client authentication" hint={lockHint("clientAuth")}>
                  <NativeSelect value={draft.clientAuth} disabled={managed("clientAuth")} onChange={(e) => set("clientAuth", e.target.value as ClientAuth)}>
                    <option value="BASIC">HTTP Basic (client_secret_basic)</option><option value="POST">Form fields (client_secret_post)</option>
                  </NativeSelect>
                </Field>
              )}
              <Field label="Workload identity token file" hint={lockHint("assertionFile", "A token that the platform writes and rotates, for example a projected Kubernetes service account token.")}>
                <Input value={draft.assertionFile} readOnly={managed("assertionFile")} onChange={(e) => set("assertionFile", e.target.value)}
                  placeholder="/var/run/secrets/mayday/workload-identity/token" />
              </Field>
              <Field label="Scope (optional)" hint={lockHint("scope")}>
                <Input value={draft.scope} readOnly={managed("scope")} onChange={(e) => set("scope", e.target.value)} placeholder="ai-gateway.invoke" />
              </Field>
              <Field label="Token parameters (optional)" hint={lockHint("tokenParams", "Extra fields of the token request, for example audience or resource.")} className="sm:col-span-2">
                <PairRows rows={params} onChange={editParams} locked={managed("tokenParams")} namePlaceholder="audience" valuePlaceholder="https://ai-gateway.example.com" addLabel="Add parameter" />
              </Field>
            </div>
          )}
          {draft.credential === "FILE" && (
            <Field label="Token file" hint={lockHint("tokenFile", "Another process writes and refreshes the token in this file. Mayday reads it before each call.")}>
              <Input value={draft.tokenFile} readOnly={managed("tokenFile")} onChange={(e) => set("tokenFile", e.target.value)} placeholder="/var/run/gateway/token" />
            </Field>
          )}
        </section>

        <section className="space-y-3 border-t pt-4">
          <div className="eyebrow">Custom headers</div>
          <Field label="Headers (optional)" hint={lockHint("headers", "Sent with every call to the provider and to MCP servers that use the agent identity, for example the agent ID from your agent registry.")}>
            <PairRows rows={rows} onChange={editRows} locked={managed("headers")} namePlaceholder="x-company-agent-id" addLabel="Add header" />
          </Field>
        </section>

        <div className="flex items-center justify-between border-t pt-4">
          <label className="flex items-center gap-2">
            <Switch checked={draft.enabled} disabled={managed("enabled")} onCheckedChange={(c) => set("enabled", c)} /> Enabled
            {managed("enabled") && <span className="text-[11px] text-muted-foreground">Set by the environment</span>}
          </label>
          <Button type="submit" disabled={busy}>{busy && <Spinner />} Save</Button>
        </div>
      </form>
    </Card>
  );
}

const blankMcp = { name: "", url: "", headerName: "Authorization", credential: "", toolAllowlist: "", useAgentIdentity: false };

export function McpPage() {
  const { isAdmin } = useSession();
  const toast = useToast();
  const { data, reload } = useQuery(() => items(rpc.agents.listMcpServers<Page<McpServerView>>()), []);
  const [form, setForm] = useState(blankMcp);
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
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  {s.name} {!s.enabled && <Badge tone="warning">Disabled</Badge>} {s.useAgentIdentity && <Badge tone="outline">Agent identity</Badge>}
                </div>
                <div className="truncate text-[12px] text-muted-foreground">
                  {s.url}{s.toolAllowlist.length ? ` · ${s.toolAllowlist.length} tools allowed` : " · all tools"}
                </div>
              </div>
              {isAdmin && <Button size="icon" variant="ghost" aria-label="Remove" onClick={() => toast.undoable(`${s.name} will be removed.`, () => rpc.agents.deleteMcpServer({ id: s.id }).then(reload))}><Trash2 /></Button>}
            </div>
          ))}
          {data?.length === 0 && <Empty title="No MCP servers" />}
        </div>
        {isAdmin && (
          <form className="space-y-3 border-t p-4" onSubmit={async (e) => {
            e.preventDefault();
            try {
              await rpc.agents.saveMcpServer({
                name: form.name, url: form.url, useAgentIdentity: form.useAgentIdentity, enabled: true,
                headerName: form.useAgentIdentity ? "" : form.headerName, credential: !form.useAgentIdentity && form.credential ? form.credential : undefined,
                toolAllowlist: form.toolAllowlist.split(",").map((x) => x.trim()).filter(Boolean),
              });
              setForm(blankMcp);
              reload();
            } catch (err) { toast.error(err); }
          }}>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Name"><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="Grafana" /></Field>
              <Field label="URL"><Input type="url" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} required placeholder="https://mcp.internal/grafana" /></Field>
            </div>
            <label className="flex items-start gap-2">
              <Switch checked={form.useAgentIdentity} onCheckedChange={(c) => setForm({ ...form, useAgentIdentity: c })} />
              <span>
                Use agent identity
                <span className="block text-[11px] text-muted-foreground">Sends the token and custom headers of the LLM provider settings, for example to the tools gateway of your cloud.</span>
              </span>
            </label>
            {!form.useAgentIdentity && (
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Auth header"><Input value={form.headerName} onChange={(e) => setForm({ ...form, headerName: e.target.value })} /></Field>
                <Field label="Credential"><Input type="password" value={form.credential} onChange={(e) => setForm({ ...form, credential: e.target.value })} placeholder="Bearer ..." autoComplete="off" /></Field>
              </div>
            )}
            <Field label="Allowed tools (optional)" hint="Comma separated. Empty allows all tools."><Input value={form.toolAllowlist} onChange={(e) => setForm({ ...form, toolAllowlist: e.target.value })} /></Field>
            <Button type="submit" variant="outline"><Plus /> Add MCP server</Button>
          </form>
        )}
      </Card>
    </div>
  );
}
