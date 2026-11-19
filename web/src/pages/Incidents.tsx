import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { Plus, Search, Siren } from "lucide-react";
import { rpc, SEVERITIES, type Incident, type Page, type Severity } from "@/lib/api";
import { useLive, useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago, between, cn, fromLocalInput } from "@/lib/utils";
import { Button, Card, Dialog, DialogContent, Empty, Field, Input, MultiSelect, NativeSelect, Spinner, Table, Td, Textarea, Th } from "@/components/ui";
import { DictateButton, IncidentStatusBadge, PageHeader, SeverityBadge } from "@/components/common";

export function IncidentsPage() {
  const [params, setParams] = useSearchParams();
  const { teams, teamName, userName, canWrite } = useSession();
  const nav = useNavigate();
  const scope = params.get("scope") ?? "open";
  const severity = (params.get("severity")?.split(",").filter(Boolean) ?? []) as Severity[];
  const team = params.get("team")?.split(",").filter(Boolean) ?? [];
  const [q, setQ] = useState(params.get("q") ?? "");
  const [extra, setExtra] = useState<Incident[]>([]);
  const [cursor, setCursor] = useState<string | undefined>();
  const filter = { open: scope === "open", severity, team, q: params.get("q") || undefined, limit: 50 };
  const list = (cursor?: string) => rpc.incidents.listIncidents<Page<Incident>>({ ...filter, cursor });
  const { data, loading, reload } = useQuery(() => list(), [JSON.stringify(filter)]);
  useLive((t) => t.startsWith("incident."), () => { setExtra([]); reload(); });

  const set = (k: string, v: string | string[]) => {
    const n = new URLSearchParams(params);
    const val = Array.isArray(v) ? v.join(",") : v;
    if (val) n.set(k, val); else n.delete(k);
    setParams(n, { replace: true });
    setExtra([]);
  };

  const items = [...(data?.items ?? []), ...extra];
  const next = extra.length ? cursor : data?.nextCursor;
  const more = async () => {
    const p = await list(next);
    setExtra((e) => [...e, ...p.items]);
    setCursor(p.nextCursor);
  };

  return (
    <>
      <PageHeader title="Incidents" eyebrow={`${items.length}${next ? "+" : ""} ${scope === "open" ? "open" : "total"}`} actions={canWrite && <DeclareIncident />} />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="inline-flex rounded-full border bg-card p-0.5">
          {["open", "all"].map((s) => (
            <button key={s} onClick={() => set("scope", s === "open" ? "" : s)}
              className={cn("h-8 rounded-full px-4 text-[11.5px] font-medium uppercase tracking-wider", scope === s ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground")}>
              {s}
            </button>
          ))}
        </div>
        <MultiSelect label="Severity" value={severity} onChange={(v) => set("severity", v)} options={SEVERITIES.map((s) => ({ value: s, label: s }))} />
        <MultiSelect label="Team" value={team} onChange={(v) => set("team", v)} options={teams.map((t) => ({ value: t.slug, label: t.name }))} />
        <form className="relative ml-auto w-full sm:w-72" onSubmit={(e) => { e.preventDefault(); set("q", q); }}>
          <Search className="absolute left-3 top-2.5 size-4 text-muted-foreground" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} onBlur={() => set("q", q)} placeholder="Search key or title" className="pl-9" />
        </form>
      </div>
      <Card>
        {loading && !data ? (
          <div className="flex justify-center p-10"><Spinner /></div>
        ) : items.length === 0 ? (
          <Empty icon={<Siren />} title={scope === "open" ? "No open incidents" : "No incidents match"}>
            {scope === "open" ? "All clear. Incidents appear here when someone declares one or a severe alert fires." : "Change the filters to see more."}
          </Empty>
        ) : (
          <Table>
            <thead>
              <tr><Th>Key</Th><Th>Title</Th><Th>Severity</Th><Th>Status</Th><Th className="hidden lg:table-cell">Teams</Th><Th className="hidden md:table-cell">Commander</Th><Th>Reported</Th><Th className="hidden md:table-cell">Duration</Th></tr>
            </thead>
            <tbody>
              {items.map((i) => (
                <tr key={i.id} onClick={() => nav(`/incidents/${i.key}`)} className="cursor-pointer transition-colors hover:bg-accent/60">
                  <Td className="whitespace-nowrap font-medium"><Link to={`/incidents/${i.key}`} onClick={(e) => e.stopPropagation()}>{i.key}</Link></Td>
                  <Td className="max-w-[28rem]"><span className="line-clamp-1">{i.title}</span></Td>
                  <Td><SeverityBadge severity={i.severity} /></Td>
                  <Td><IncidentStatusBadge status={i.status} /></Td>
                  <Td className="hidden text-muted-foreground lg:table-cell">{i.teamIds.map(teamName).join(", ")}</Td>
                  <Td className="hidden text-muted-foreground md:table-cell">{userName(i.commanderId)}</Td>
                  <Td className="whitespace-nowrap text-muted-foreground">{ago(i.reportedAt)}</Td>
                  <Td className="hidden whitespace-nowrap text-muted-foreground md:table-cell">{between(i.impactStartedAt ?? i.reportedAt, i.resolvedAt ?? new Date().toISOString())}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
        {next && <div className="flex justify-center border-t p-3"><Button variant="ghost" size="sm" onClick={more}>Load more</Button></div>}
      </Card>
    </>
  );
}

function DeclareIncident() {
  const { teams, users } = useSession();
  const nav = useNavigate();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ title: "", description: "", severity: "SEV2" as Severity, teamIds: [] as string[], commanderId: "", impactStartedAt: "" });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (form.teamIds.length === 0) return toast.show("Select at least one responder team.", "error");
    setBusy(true);
    try {
      const inc = await rpc.incidents.declareIncident<Incident>({
        ...form, commanderId: form.commanderId || undefined, impactStartedAt: fromLocalInput(form.impactStartedAt),
      });
      setOpen(false);
      nav(`/incidents/${inc.key}`);
    } catch (err) {
      toast.error(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Button onClick={() => setOpen(true)}><Plus /> Declare incident</Button>
      <DialogContent title="Declare an incident" description="Tell responders what is happening. You can change everything later.">
        <form onSubmit={submit} className="space-y-4">
          <Field label="Title"><Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="Checkout returns errors" required autoFocus /></Field>
          <Field label="What is happening">
            <div className="flex gap-2">
              <Textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Symptoms, scope, first observations" />
              <DictateButton onText={(t) => setForm((f) => ({ ...f, description: (f.description ? f.description + " " : "") + t }))} />
            </div>
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Severity">
              <NativeSelect value={form.severity} onChange={(e) => setForm({ ...form, severity: e.target.value as Severity })}>
                {SEVERITIES.map((s) => <option key={s}>{s}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Impact started">
              <Input type="datetime-local" value={form.impactStartedAt} onChange={(e) => setForm({ ...form, impactStartedAt: e.target.value })} />
            </Field>
          </div>
          <Field label="Responder teams">
            <MultiSelect label="Teams" allLabel="Select" value={form.teamIds} onChange={(v) => setForm({ ...form, teamIds: v })} options={teams.map((t) => ({ value: t.id, label: t.name }))} />
          </Field>
          <Field label="Incident commander">
            <NativeSelect value={form.commanderId} onChange={(e) => setForm({ ...form, commanderId: e.target.value })}>
              <option value="">Not assigned</option>
              {users.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
            </NativeSelect>
          </Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={busy}>{busy ? <Spinner /> : <Siren />} Declare</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
