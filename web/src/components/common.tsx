import { Bot, CircleAlert, CircleCheck, CircleDot, Cog, Mic, MicOff, TriangleAlert, User as UserIcon } from "lucide-react";
import type { Actor, AlertStatus, IncidentStatus, RunStatus, Severity } from "@/lib/api";
import { Badge, Button, Tip } from "@/components/ui";
import { useDictation } from "@/lib/hooks";
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
