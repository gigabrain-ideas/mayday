import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { Check, CheckCheck, ExternalLink, Link2, Send, Siren } from "lucide-react";
import { rpc, waitsForHuman, type Alert, type Incident } from "@/lib/api";
import { useLive, useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago, dateTime } from "@/lib/utils";
import { Badge, Button, Card, CardHeader, Empty, Input, Spinner, Textarea } from "@/components/ui";
import { ActorIcon, ActorName, AlertStatusBadge, DictateButton, SeverityBadge } from "@/components/common";
import { AgentActions, AgentRuns } from "@/components/AgentPanel";
import { AlertEscalation } from "@/pages/Escalation";

export function AlertPage() {
  const { id = "" } = useParams();
  const { data: a, error, reload, setData } = useQuery(() => rpc.alerts.getAlert<Alert>({ id }), [id]);
  const { teamName, userName, canWrite } = useSession();
  const toast = useToast();
  const [text, setText] = useState("");
  const [incidentRef, setIncidentRef] = useState("");
  const incident = useQuery(a?.incidentId ? () => rpc.incidents.getIncident<Incident>({ ref: a.incidentId! }) : null, [a?.incidentId]);
  useLive((t, sid) => t === "alert.updated" && sid === id, reload);

  useEffect(() => { if (error && a) toast.error(error); }, [error]);

  if (error && !a) return <Empty title="Alert not found">{error.message}</Empty>;
  if (!a) return <div className="flex justify-center p-16"><Spinner /></div>;
  /** True when the call succeeded. */
  const act = async (call: Promise<Alert>) => {
    try { setData(await call); return true; } catch (e) { toast.error(e); return false; }
  };

  return (
    <>
      <div className="mb-5">
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <Link to="/alerts" className="eyebrow hover:text-foreground">Alerts</Link>
          <span className="eyebrow">/</span>
          <SeverityBadge severity={a.severity} />
          <AlertStatusBadge status={a.status} />
          {a.status === "ACKNOWLEDGED" && waitsForHuman(a) && <Badge tone="outline">By an agent · waiting for a person</Badge>}
          {a.status === "RESOLVED" && waitsForHuman(a) && <Badge tone="outline">Resolved by an agent · waiting for a person</Badge>}
          {a.status === "RESOLVED" && a.resolvedUnderRunbook && <Badge tone="outline">Resolved under an approved runbook</Badge>}
          <Badge tone="outline">×{a.count}</Badge>
        </div>
        <h1 className="text-[22px] font-light leading-snug tracking-tight">{a.title}</h1>
        {canWrite && (
          <div className="mt-4 flex flex-wrap gap-2">
            {waitsForHuman(a) && <Button onClick={() => act(rpc.alerts.acknowledgeAlert({ id: a.id }))}><Check /> Acknowledge</Button>}
            {a.status !== "RESOLVED" && <Button variant="outline" onClick={() => act(rpc.alerts.resolveAlert({ id: a.id, text: "" }))}><CheckCheck /> Resolve</Button>}
            {a.externalUrl && <Button variant="ghost" asChild><a href={a.externalUrl} target="_blank" rel="noreferrer noopener"><ExternalLink /> Source</a></Button>}
          </div>
        )}
      </div>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div className="space-y-5">
          <Card>
            <CardHeader title="Updates" />
            {a.description && <p className="whitespace-pre-wrap border-b px-5 py-4 text-muted-foreground">{a.description}</p>}
            {canWrite && (
              <form className="flex gap-2 border-b p-4" onSubmit={(e) => { e.preventDefault(); if (text.trim()) act(rpc.alerts.addAlertUpdate({ id: a.id, text })).then((ok) => ok && setText("")); }}>
                <Textarea value={text} onChange={(e) => setText(e.target.value)} placeholder="Add an update" className="min-h-14" />
                <DictateButton onText={(t) => setText((p) => (p ? p + " " : "") + t)} />
                <Button type="submit" size="icon" aria-label="Post"><Send /></Button>
              </form>
            )}
            <ol className="divide-y">
              {[...a.updates].reverse().map((u, i) => (
                <li key={i} className="flex gap-3 px-5 py-3">
                  <ActorIcon actor={u.actor} className="mt-0.5 size-4" />
                  <div className="flex-1">
                    <div className="flex text-[12px]"><span className="font-medium"><ActorName actor={u.actor} userName={userName} /></span><span className="ml-auto text-muted-foreground">{ago(u.at)}</span></div>
                    <p className="whitespace-pre-wrap">{u.text}</p>
                  </div>
                </li>
              ))}
              {a.updates.length === 0 && <li className="px-5 py-6 text-center text-muted-foreground">No updates yet.</li>}
            </ol>
          </Card>
          <Card>
            <CardHeader title="Agent runs" />
            <AgentRuns subject={{ kind: "ALERT", ref: a.id }} subjectId={a.id} />
          </Card>
        </div>
        <div className="space-y-5">
          <AlertEscalation alert={a} />
          <AgentActions subject={{ kind: "ALERT", ref: a.id }} actions={["ANALYZE", "REMEDIATE"]} />
          <Card>
            <CardHeader title="Incident" />
            <div className="p-4">
              {incident.data ? (
                <Link to={`/incidents/${incident.data.key}`} className="flex items-center gap-2 hover:underline"><Siren className="size-4" />{incident.data.key} · {incident.data.title}</Link>
              ) : canWrite ? (
                <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); act(rpc.alerts.linkAlert({ id: a.id, incident: incidentRef })); }}>
                  <Input value={incidentRef} onChange={(e) => setIncidentRef(e.target.value)} placeholder="INC-42" />
                  <Button type="submit" variant="outline" disabled={!incidentRef}><Link2 /> Link</Button>
                </form>
              ) : <span className="text-muted-foreground">Not linked</span>}
            </div>
          </Card>
          <Card>
            <CardHeader title="Details" />
            <div className="divide-y px-4 py-2 text-[12.5px]">
              {[["Team", teamName(a.teamId)], ["Source", a.source], ["First seen", dateTime(a.firstSeenAt)], ["Last seen", dateTime(a.lastSeenAt)],
                ["Resolved", dateTime(a.resolvedAt)], ["Resolved by", a.resolvedBy ? a.resolvedBy.kind === "USER" ? userName(a.resolvedBy.id) : a.resolvedBy.name : "—"], ["Fingerprint", a.fingerprint.slice(0, 16)]].map(([k, v]) => (
                <div key={k} className="flex justify-between gap-3 py-1.5"><span className="eyebrow">{k}</span><span>{v}</span></div>
              ))}
            </div>
            {Object.keys(a.labels).length > 0 && (
              <div className="flex flex-wrap gap-1 border-t p-4">{Object.entries(a.labels).map(([k, v]) => <Badge key={k} tone="outline">{k}={v}</Badge>)}</div>
            )}
          </Card>
        </div>
      </div>
    </>
  );
}
