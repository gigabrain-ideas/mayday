import { Bot, CircleAlert, CircleCheck, CircleDot, Cog, Copy, ExternalLink, Mic, MicOff, TriangleAlert, User as UserIcon } from "lucide-react";
import { useState } from "react";
import { traceUrl, type Actor, type AlertStatus, type IncidentStatus, type RunStatus, type Severity, type User } from "@/lib/api";
import { Badge, Button, Input, NativeSelect, Tip } from "@/components/ui";
import { person, usePeopleSearch } from "@/lib/people";
import { useDictation } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { titleCase } from "@/lib/utils";

/** Severity is signalled by color and by an icon, never by color alone. */
export function SeverityBadge({ severity }: { severity: Severity }) {
  if (severity === "SEV1") return <Badge tone="critical"><CircleAlert />SEV1</Badge>;
  if (severity === "SEV2") return <Badge tone="critical"><TriangleAlert />SEV2</Badge>;
  if (severity === "SEV3") return <Badge tone="warning"><TriangleAlert />SEV3</Badge>;
  return <Badge tone="outline">{severity}</Badge>;
}

export function IncidentStatusBadge({ status }: { status: IncidentStatus }) {
  if (status === "RESOLVED" || status === "CLOSED") return <Badge tone="success"><CircleCheck />{titleCase(status)}</Badge>;
  if (status === "INVESTIGATING") return <Badge tone="warning"><CircleDot />{titleCase(status)}</Badge>;
  return <Badge tone="neutral"><CircleDot />{titleCase(status)}</Badge>;
}

export function AlertStatusBadge({ status }: { status: AlertStatus }) {
  if (status === "RESOLVED") return <Badge tone="success"><CircleCheck />Resolved</Badge>;
  if (status === "FIRING") return <Badge tone="critical"><CircleAlert />Firing</Badge>;
  return <Badge tone="warning"><CircleDot />Acknowledged</Badge>;
}

export function RunStatusBadge({ status }: { status: RunStatus }) {
  if (status === "SUCCEEDED") return <Badge tone="success"><CircleCheck />Done</Badge>;
  if (status === "FAILED") return <Badge tone="critical"><CircleAlert />Failed</Badge>;
  if (status === "CANCELLED") return <Badge tone="outline">Cancelled</Badge>;
  if (status === "REJECTED") return <Badge tone="outline">Rejected</Badge>;
  if (status === "AWAITING_APPROVAL") return <Badge tone="warning"><CircleDot />Needs approval</Badge>;
  return <Badge tone="warning"><CircleDot className="animate-pulse" />{titleCase(status)}</Badge>;
}

export function ActorIcon({ actor, className = "size-4" }: { actor: Actor; className?: string }) {
  if (actor.kind === "AGENT") return <Bot className={className} strokeWidth={2} />;
  if (actor.kind === "SYSTEM") return <Cog className={className} strokeWidth={2} />;
  return <UserIcon className={className} strokeWidth={2} />;
}

export function ActorName({ actor, userName }: { actor: Actor; userName: (id?: string) => string }) {
  return (
    <span>
      {actor.name}
      {actor.onBehalfOf && <span className="text-muted-foreground"> for {userName(actor.onBehalfOf)}</span>}
    </span>
  );
}

/** Voice input. Stylus input (for example Apple Pencil Scribble) works in every text field without extra code. */
export function DictateButton({ onText }: { onText: (t: string) => void }) {
  const d = useDictation(onText);
  if (!d.supported) return null;
  return (
    <Tip content={d.listening ? "Stop dictation" : "Dictate with your voice"}>
      <Button type="button" variant={d.listening ? "destructive" : "ghost"} size="icon" onClick={d.toggle} aria-label="Dictate">
        {d.listening ? <MicOff /> : <Mic />}
      </Button>
    </Tip>
  );
}

/** A load that failed. Never show an empty list in its place: the data is unknown, not empty. */
export function LoadFailed({ what, error, onRetry }: { what: string; error?: { message: string }; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-10 text-center" role="alert">
      <CircleAlert className="size-6 text-destructive" />
      <div className="font-medium">Mayday could not load the {what}</div>
      <p className="max-w-md text-[12px] text-muted-foreground">{error?.message || "The server did not answer."} Mayday tries again when the connection is back.</p>
      <Button size="sm" variant="outline" onClick={onRetry}>Try again</Button>
    </div>
  );
}

export function PageHeader({ title, eyebrow, actions, children }: { title: React.ReactNode; eyebrow?: React.ReactNode; actions?: React.ReactNode; children?: React.ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        {eyebrow && <div className="eyebrow mb-1">{eyebrow}</div>}
        <h1 className="text-[20px] font-light leading-tight tracking-tight">{title}</h1>
        {children}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** Link to the OpenTelemetry trace of an agent run. Without MAYDAY_TRACE_URL_TEMPLATE the trace ID is shown for copying. */
export function RunTrace({ traceId }: { traceId?: string }) {
  const { me } = useSession();
  const toast = useToast();
  if (!traceId) return null;
  const url = traceUrl(me.traceUrlTemplate, traceId);
  if (url) {
    return (
      <a href={url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-[12px] hover:underline">
        <ExternalLink className="size-3.5" /> View trace
      </a>
    );
  }
  return (
    <Tip content="Copy the trace ID">
      <button type="button" onClick={() => navigator.clipboard?.writeText(traceId).then(() => toast.show("Trace ID copied."))}
        className="inline-flex items-center gap-1 font-mono text-[12px] text-muted-foreground hover:text-foreground">
        <Copy className="size-3.5" /> {traceId}
      </button>
    </Tip>
  );
}

/** Picks a person found on the server by name or email. [value] stays in the list while the search changes. */
export function PersonSelect({ label, placeholder, value = "", onPick, exclude, disabled }: {
  label: string; placeholder: string; value?: string; onPick: (id: string) => void; exclude?: (u: User) => boolean; disabled?: boolean;
}) {
  const [q, setQ] = useState("");
  const found = usePeopleSearch(q).filter((u) => !exclude?.(u));
  const picked = value ? person(value) : undefined;
  const options = picked && !found.some((u) => u.id === picked.id) ? [picked, ...found] : found;
  return (
    <div className="space-y-2">
      <Input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Find a person" aria-label="Find a person" disabled={disabled} />
      <NativeSelect value={value} aria-label={label} disabled={disabled} onChange={(e) => e.target.value && onPick(e.target.value)}>
        <option value="">{placeholder}</option>
        {options.map((u) => <option key={u.id} value={u.id}>{u.name} · {u.email}</option>)}
      </NativeSelect>
    </div>
  );
}
