import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { Bell, Copy, FileText, Pencil, Plus, ScrollText, Send, Trash2 } from "lucide-react";
import {
  items, rpc, INCIDENT_STATUSES, SEVERITIES, type ActionItem, type Alert, type Incident, type IncidentStatus, type Page, type Postmortem, type Severity, type TimelineEntry,
} from "@/lib/api";
import { useLive, useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago, between, cn, dateTime, fromLocalInput, time, titleCase, toLocalInput } from "@/lib/utils";
import { Markdown } from "@/lib/markdown";
import {
  Badge, Button, Card, CardHeader, Empty, Field, Input, MultiSelect, NativeSelect, Spinner, Tabs, TabsContent, TabsList, TabsTrigger, Textarea,
} from "@/components/ui";
import { ActorIcon, ActorName, AlertStatusBadge, DictateButton, IncidentStatusBadge, SeverityBadge } from "@/components/common";
import { AgentActions, AgentRuns, useAgentTrigger } from "@/components/AgentPanel";

export function IncidentPage() {
  const { key = "" } = useParams();
  const { data: inc, error, reload, setData } = useQuery(() => rpc.incidents.getIncident<Incident>({ ref: key }), [key]);
  const timeline = useQuery(() => items(rpc.incidents.listTimeline<Page<TimelineEntry>>({ ref: key })), [key]);
  const [tab, setTab] = useState("timeline");
  useLive((t, id) => t.startsWith("incident.") && id === inc?.id, () => { reload(); timeline.reload(); });
  const toast = useToast();

  if (error) return <Empty title="Incident not found">{error.message}</Empty>;
  if (!inc) return <div className="flex justify-center p-16"><Spinner /></div>;

  const patch = async (body: Partial<Incident> & Record<string, unknown>) => {
    try {
      setData(await rpc.incidents.updateIncident<Incident>({ ref: inc.key, patch: { ...body, version: inc.version } }));
      timeline.reload();
    } catch (e) {
      toast.error(e);
      reload();
    }
  };

  return (
    <>
      <Header inc={inc} patch={patch} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
        <Card className="min-w-0">
          <Tabs value={tab} onValueChange={setTab}>
            <TabsList className="px-3 pt-1">
              <TabsTrigger value="timeline">Timeline</TabsTrigger>
              <TabsTrigger value="resolution">Resolution</TabsTrigger>
              <TabsTrigger value="postmortem">Postmortem{inc.postmortemRequired && !inc.postmortem ? " •" : ""}</TabsTrigger>
              <TabsTrigger value="runbook">Runbook</TabsTrigger>
              <TabsTrigger value="agents">Agent runs</TabsTrigger>
            </TabsList>
            <TabsContent value="timeline"><Timeline inc={inc} entries={timeline.data} onAdded={timeline.reload} /></TabsContent>
            <TabsContent value="resolution"><Resolution inc={inc} onSaved={setData} onRunbook={() => setTab("runbook")} /></TabsContent>
            <TabsContent value="postmortem"><PostmortemTab inc={inc} onSaved={setData} /></TabsContent>
            <TabsContent value="runbook"><RunbookTab inc={inc} /></TabsContent>
            <TabsContent value="agents"><AgentRuns subject={{ kind: "INCIDENT", ref: inc.key }} subjectId={inc.id} /></TabsContent>
          </Tabs>
        </Card>
        <div className="space-y-5">
          <AgentActions subject={{ kind: "INCIDENT", ref: inc.key }} actions={["ANALYZE", "PATCH", "REMEDIATE", "TIMELINE"]} />
          <Details inc={inc} patch={patch} />
          <LinkedAlerts inc={inc} />
        </div>
      </div>
    </>
  );
}

function Header({ inc, patch }: { inc: Incident; patch: (b: Record<string, unknown>) => void }) {
  const { teams, canWrite } = useSession();
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(inc.title);
  useEffect(() => setTitle(inc.title), [inc.title]);
  return (
    <div className="mb-5">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Link to="/incidents" className="eyebrow hover:text-foreground">Incidents</Link>
        <span className="eyebrow">/</span>
        <span className="eyebrow text-foreground">{inc.key}</span>
        <SeverityBadge severity={inc.severity} />
        <IncidentStatusBadge status={inc.status} />
        {inc.postmortemRequired && <Badge tone={inc.postmortem?.status === "PUBLISHED" ? "success" : "outline"}><FileText />Postmortem required</Badge>}
      </div>
      {editing ? (
        <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); patch({ title }); setEditing(false); }}>
          <Input value={title} onChange={(e) => setTitle(e.target.value)} className="h-11 text-[18px]" autoFocus />
          <Button type="submit">Save</Button>
          <Button type="button" variant="ghost" onClick={() => setEditing(false)}>Cancel</Button>
        </form>
      ) : (
        <h1 className="group flex items-start gap-2 text-[22px] font-light leading-snug tracking-tight">
          {inc.title}
          {canWrite && <button onClick={() => setEditing(true)} className="mt-1.5 rounded-full p-1 text-muted-foreground opacity-0 hover:bg-accent group-hover:opacity-100 focus:opacity-100" aria-label="Edit title"><Pencil className="size-4" /></button>}
        </h1>
      )}
      {canWrite && (
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <NativeSelect value={inc.status} onChange={(e) => patch({ status: e.target.value as IncidentStatus })} className="w-48" aria-label="Status">
            {INCIDENT_STATUSES.map((s) => <option key={s} value={s}>{titleCase(s)}</option>)}
          </NativeSelect>
          <NativeSelect value={inc.severity} onChange={(e) => patch({ severity: e.target.value as Severity })} className="w-28" aria-label="Severity">
            {SEVERITIES.map((s) => <option key={s}>{s}</option>)}
          </NativeSelect>
          <MultiSelect label="Teams" value={inc.teamIds} onChange={(v) => v.length && patch({ teamIds: v })} options={teams.map((t) => ({ value: t.id, label: t.name }))} />
        </div>
      )}
    </div>
  );
}

function Timeline({ inc, entries, onAdded }: { inc: Incident; entries?: TimelineEntry[]; onAdded: () => void }) {
  const { userName, canWrite } = useSession();
  const toast = useToast();
  const [text, setText] = useState("");
  const [at, setAt] = useState("");
  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!text.trim()) return;
    try {
      await rpc.incidents.addTimelineEntry({ ref: inc.key, text, at: fromLocalInput(at) });
      setText(""); setAt("");
      onAdded();
    } catch (err) { toast.error(err); }
  };
  return (
    <div>
      {inc.description && <div className="border-b px-5 py-4 whitespace-pre-wrap text-muted-foreground">{inc.description}</div>}
      {canWrite && (
        <form onSubmit={add} className="flex flex-col gap-2 border-b p-4">
          <div className="flex gap-2">
            <Textarea value={text} onChange={(e) => setText(e.target.value)} placeholder="Add an update. Write, use a stylus, or dictate." className="min-h-16"
              onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) add(e); }} />
            <DictateButton onText={(t) => setText((p) => (p ? p + " " : "") + t)} />
          </div>
          <div className="flex items-center justify-end gap-2">
            <Input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} className="w-56" aria-label="Event time (optional)" title="Event time (optional)" />
            <Button type="submit" size="sm" disabled={!text.trim()}><Send /> Post</Button>
          </div>
        </form>
      )}
      {!entries ? <div className="flex justify-center p-8"><Spinner /></div> : (
        <ol className="relative px-5 py-4">
          {[...entries].reverse().map((e) => (
            <li key={e.id} className="relative flex gap-3 pb-5 last:pb-0">
              <div className={cn("relative z-10 flex size-7 shrink-0 items-center justify-center rounded-full border bg-card",
                e.actor.kind === "AGENT" && "border-foreground", e.kind === "ALERT" && "border-destructive/50 text-destructive")}>
                <ActorIcon actor={e.actor} className="size-3.5" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-baseline gap-x-2 text-[12px]">
                  <span className="font-medium"><ActorName actor={e.actor} userName={userName} /></span>
                  <span className="eyebrow">{e.kind.toLowerCase()}</span>
                  <span className="ml-auto text-muted-foreground" title={dateTime(e.at)}>{time(e.at)} · {ago(e.at)}</span>
                </div>
                <div className="mt-1">{e.actor.kind === "AGENT" && e.text.includes("\n") ? <Markdown text={e.text} /> : <p className="whitespace-pre-wrap">{e.text}</p>}</div>
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function Resolution({ inc, onSaved, onRunbook }: { inc: Incident; onSaved: (i: Incident) => void; onRunbook: () => void }) {
  const { canWrite } = useSession();
  const toast = useToast();
  const [notes, setNotes] = useState(inc.resolutionNotes);
  const dirty = notes !== inc.resolutionNotes;
  const trigger = useAgentTrigger({ kind: "INCIDENT", ref: inc.key }, onRunbook);
  useEffect(() => { if (!dirty) setNotes(inc.resolutionNotes); }, [inc.resolutionNotes]); // eslint-disable-line react-hooks/exhaustive-deps
  const save = async () => {
    try { onSaved(await rpc.incidents.saveResolution<Incident>({ ref: inc.key, notes })); toast.show("Resolution saved.", "success"); } catch (e) { toast.error(e); }
  };
  return (
    <div className="space-y-3 p-5">
      <p className="text-muted-foreground">Explain how the issue was solved: the commands, the checks, the decisions. A runbook can be generated from this text.</p>
      <div className="flex gap-2">
        <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} disabled={!canWrite} className="min-h-56" placeholder="We rolled back the deploy. Then we..." />
        {canWrite && <DictateButton onText={(t) => setNotes((p) => (p ? p + " " : "") + t)} />}
      </div>
      {canWrite && (
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="outline" onClick={() => trigger.open("RUNBOOK")} disabled={!inc.resolutionNotes.trim() || dirty}><ScrollText /> Generate runbook</Button>
          <Button onClick={save} disabled={!dirty}>Save</Button>
        </div>
      )}
      {trigger.dialog}
    </div>
  );
}

const PM_FIELDS: [keyof Postmortem, string, string][] = [
  ["summary", "Summary", "What happened, in two or three sentences."],
  ["impact", "Impact", "Who was affected, how long, how much."],
  ["rootCause", "Root cause", "The cause and the contributing factors."],
  ["detection", "Detection", "How we found out. Could we have found out sooner?"],
  ["resolution", "Resolution", "What stopped the impact."],
  ["lessons", "Lessons", "What went well, what went wrong, where we were lucky."],
];

function PostmortemTab({ inc, onSaved }: { inc: Incident; onSaved: (i: Incident) => void }) {
  const { canWrite, userName } = useSession();
  const toast = useToast();
  const empty: Postmortem = { status: "DRAFT", summary: "", impact: "", rootCause: "", detection: "", resolution: "", lessons: "", actionItems: [] };
  const [pm, setPm] = useState<Postmortem>(inc.postmortem ?? empty);
  const [dirty, setDirty] = useState(false);
  const trigger = useAgentTrigger({ kind: "INCIDENT", ref: inc.key });
  useEffect(() => { if (!dirty) setPm(inc.postmortem ?? empty); }, [inc.postmortem]); // eslint-disable-line react-hooks/exhaustive-deps
  const set = (p: Partial<Postmortem>) => { setPm((x) => ({ ...x, ...p })); setDirty(true); };
  const save = async () => {
    try { onSaved(await rpc.incidents.savePostmortem<Incident>({ ref: inc.key, postmortem: pm })); setDirty(false); toast.show("Postmortem saved.", "success"); } catch (e) { toast.error(e); }
  };
  const items = pm.actionItems;
  const setItem = (i: number, v: Partial<ActionItem>) => set({ actionItems: items.map((x, j) => (j === i ? { ...x, ...v } : x)) });

  return (
    <div className="space-y-4 p-5">
      <div className="flex flex-wrap items-center gap-2">
        {inc.postmortemRequired ? <Badge tone="warning">Required by policy</Badge> : <Badge tone="outline">Optional</Badge>}
        {inc.postmortemDueAt && <span className="text-[12px] text-muted-foreground">Due {dateTime(inc.postmortemDueAt)}</span>}
        {pm.updatedBy && <span className="text-[12px] text-muted-foreground">Last change by {pm.updatedBy.name}{pm.updatedBy.onBehalfOf ? ` for ${userName(pm.updatedBy.onBehalfOf)}` : ""}</span>}
        {canWrite && <Button variant="outline" size="sm" className="ml-auto" onClick={() => trigger.open("POSTMORTEM")}><FileText /> Draft with agent</Button>}
      </div>
      {PM_FIELDS.map(([k, label, hint]) => (
        <Field key={k} label={label}>
          <div className="flex gap-2">
            <Textarea value={pm[k] as string} onChange={(e) => set({ [k]: e.target.value } as Partial<Postmortem>)} placeholder={hint} disabled={!canWrite} className="min-h-20" />
            {canWrite && <DictateButton onText={(t) => set({ [k]: ((pm[k] as string) ? pm[k] + " " : "") + t } as Partial<Postmortem>)} />}
          </div>
        </Field>
      ))}
      <Field label="Action items">
        <div className="space-y-2">
          {items.map((it, i) => (
            <div key={i} className="flex items-center gap-2">
              <input type="checkbox" checked={it.done} onChange={(e) => setItem(i, { done: e.target.checked })} disabled={!canWrite} className="size-4 accent-current" aria-label="Done" />
              <Input value={it.text} onChange={(e) => setItem(i, { text: e.target.value })} disabled={!canWrite} className={cn(it.done && "line-through text-muted-foreground")} />
              <Input value={it.owner} onChange={(e) => setItem(i, { owner: e.target.value })} placeholder="Owner" disabled={!canWrite} className="w-40" />
              {canWrite && <Button variant="ghost" size="icon" onClick={() => set({ actionItems: items.filter((_, j) => j !== i) })} aria-label="Remove"><Trash2 /></Button>}
            </div>
          ))}
          {canWrite && <Button variant="ghost" size="sm" onClick={() => set({ actionItems: [...items, { text: "", owner: "", done: false }] })}><Plus /> Add action item</Button>}
        </div>
      </Field>
      {canWrite && (
        <div className="flex items-center justify-end gap-2 border-t pt-4">
          <NativeSelect value={pm.status} onChange={(e) => set({ status: e.target.value as Postmortem["status"] })} className="w-40" aria-label="Postmortem status">
            <option value="DRAFT">Draft</option><option value="IN_REVIEW">In review</option><option value="PUBLISHED">Published</option>
          </NativeSelect>
          <Button onClick={save} disabled={!dirty}>Save postmortem</Button>
        </div>
      )}
      {trigger.dialog}
    </div>
  );
}

function RunbookTab({ inc }: { inc: Incident }) {
  const toast = useToast();
  const { userName } = useSession();
  if (!inc.runbook) {
    return <Empty icon={<ScrollText />} title="No runbook yet">Write the resolution notes, then press “Generate runbook”. The agent writes it in ASD-STE100 Simplified Technical English.</Empty>;
  }
  const rb = inc.runbook;
  return (
    <div className="p-5">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <h2 className="text-[15px] font-semibold">{rb.title}</h2>
        <span className="text-[12px] text-muted-foreground">by {rb.generatedBy.name}{rb.generatedBy.onBehalfOf ? ` for ${userName(rb.generatedBy.onBehalfOf)}` : ""} · {ago(rb.createdAt)}</span>
        <Button variant="ghost" size="sm" className="ml-auto" onClick={() => navigator.clipboard.writeText(rb.body).then(() => toast.show("Runbook copied as Markdown."))}><Copy /> Copy</Button>
      </div>
      <Markdown text={rb.body} />
    </div>
  );
}

function Details({ inc, patch }: { inc: Incident; patch: (b: Record<string, unknown>) => void }) {
  const { users, userName, canWrite } = useSession();
  const row = (label: string, value: React.ReactNode) => (
    <div className="flex items-center justify-between gap-3 py-1.5"><span className="eyebrow">{label}</span><span className="text-right">{value}</span></div>
  );
  const timeField = (label: string, key: "impactStartedAt" | "impactEndedAt" | "discoveredAt") => (
    <Field label={label}>
      <Input type="datetime-local" value={toLocalInput(inc[key])} disabled={!canWrite} onChange={(e) => e.target.value && patch({ [key]: fromLocalInput(e.target.value) })} />
    </Field>
  );
  return (
    <Card>
      <CardHeader title="Details" />
      <div className="space-y-3 p-4">
        <Field label="Commander">
          <NativeSelect value={inc.commanderId ?? ""} disabled={!canWrite} onChange={(e) => e.target.value && patch({ commanderId: e.target.value })}>
            <option value="">{inc.commanderId ? userName(inc.commanderId) : "Not assigned"}</option>
            {users.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
          </NativeSelect>
        </Field>
        {timeField("Impact started", "impactStartedAt")}
        {timeField("Discovered", "discoveredAt")}
        {timeField("Impact ended", "impactEndedAt")}
        <div className="divide-y border-t pt-2 text-[12.5px]">
          {row("Reported", dateTime(inc.reportedAt))}
          {row("Acknowledged", dateTime(inc.acknowledgedAt))}
          {row("Resolved", dateTime(inc.resolvedAt))}
          {row("Time to acknowledge", between(inc.reportedAt, inc.acknowledgedAt))}
          {row("Impact duration", between(inc.impactStartedAt ?? inc.reportedAt, inc.impactEndedAt ?? inc.resolvedAt))}
          {row("Declared by", inc.createdBy.name)}
        </div>
        {Object.keys(inc.labels).length > 0 && (
          <div className="flex flex-wrap gap-1 pt-1">{Object.entries(inc.labels).map(([k, v]) => <Badge key={k} tone="outline">{k}={v}</Badge>)}</div>
        )}
      </div>
    </Card>
  );
}

function LinkedAlerts({ inc }: { inc: Incident }) {
  const [alerts, setAlerts] = useState<Alert[]>([]);
  useEffect(() => {
    Promise.all(inc.alertIds.slice(0, 20).map((id) => rpc.alerts.getAlert<Alert>({ id }).catch(() => null))).then((a) => setAlerts(a.filter(Boolean) as Alert[]));
  }, [inc.alertIds]);
  if (inc.alertIds.length === 0) return null;
  return (
    <Card>
      <CardHeader title="Alerts" />
      <div className="divide-y">
        {alerts.map((a) => (
          <Link key={a.id} to={`/alerts/${a.id}`} className="flex items-start gap-3 px-4 py-3 hover:bg-accent/60">
            <Bell className="mt-0.5 size-4 shrink-0" />
            <div className="min-w-0 flex-1">
              <div className="line-clamp-2">{a.title}</div>
              <div className="mt-1 flex items-center gap-2 text-[12px] text-muted-foreground"><AlertStatusBadge status={a.status} /> ×{a.count} · {a.source}</div>
            </div>
          </Link>
        ))}
      </div>
    </Card>
  );
}
