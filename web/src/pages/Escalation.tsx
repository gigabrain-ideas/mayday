import { useState } from "react";
import { ArrowDown, ArrowUp, BellRing, ChevronsUp, Pencil, Plus, Trash2 } from "lucide-react";
import {
  items, rpc, type Alert, type Escalation, type EscalationLevel, type EscalationPolicy, type EscalationStatus, type EscalationTarget, type Schedule, type Team, type TeamMember,
} from "@/lib/api";
import { useLive, useQuery } from "@/lib/hooks";
import { useSession, useTeamOwner } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago, dateTime } from "@/lib/utils";
import { Badge, Button, Card, CardHeader, Dialog, DialogContent, Empty, Field, Input, NativeSelect, Spinner, Switch } from "@/components/ui";

const STATUS_TONE: Record<EscalationStatus, "warning" | "neutral" | "success" | "critical" | "outline"> = {
  ACTIVE: "warning", ACKNOWLEDGED: "neutral", RESOLVED: "success", EXHAUSTED: "critical", CANCELLED: "outline",
};

function useTargetName() {
  const { userName, teamName } = useSession();
  const all = useQuery(() => items(rpc.oncall.listSchedules<{ items: Schedule[] }>({})), []);
  const schedules = all.data ?? [];
  const name = (t: EscalationTarget) => {
    if (t.kind === "USER") return userName(t.id);
    const s = schedules.find((x) => x.id === t.id);
    return s ? `${teamName(s.teamId)} · ${s.name}` : "Deleted schedule";
  };
  return { name, schedules };
}

export function EscalationPolicies({ team, members }: { team: Team; members: TeamMember[] }) {
  const isOwner = useTeamOwner()(team);
  const toast = useToast();
  const list = useQuery(() => items(rpc.escalation.listPolicies<{ items: EscalationPolicy[] }>({ teamId: team.id })), [team.id]);
  useLive((t) => t === "escalation.changed", list.reload);
  const targets = useTargetName();
  const [editing, setEditing] = useState<EscalationPolicy | "new" | null>(null);
  return (
    <Card>
      <CardHeader title="Escalation policies" action={isOwner && <Button size="sm" variant="outline" onClick={() => setEditing("new")}><Plus /> New policy</Button>} />
      {!list.data ? <div className="flex justify-center p-6"><Spinner /></div> : list.data.length === 0 ? (
        <Empty icon={<BellRing />} title="No escalation policies">
          {isOwner ? "Tell Mayday who to page when an alert fires, and who is next when nobody acknowledges." : "A team owner can create an escalation policy."}
        </Empty>
      ) : (
        <div className="divide-y">
          {list.data.map((p) => (
            <div key={p.id} className="space-y-2 px-4 py-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{p.name}</span>
                {p.teamDefault && <Badge tone="solid">Default</Badge>}
                {p.repeat > 0 && <Badge tone="outline">Repeats {p.repeat}×</Badge>}
                <span className="flex-1" />
                {isOwner && <Button size="sm" variant="ghost" onClick={() => setEditing(p)}><Pencil /> Edit</Button>}
                {isOwner && <Button size="icon" variant="ghost" aria-label={`Delete ${p.name}`}
                  onClick={() => toast.undoable(`Policy ${p.name} will be deleted. Its active escalations stop.`, () => rpc.escalation.deletePolicy({ id: p.id }))}><Trash2 /></Button>}
              </div>
              {p.description && <p className="text-[12px] text-muted-foreground">{p.description}</p>}
              <ol className="space-y-1">
                {p.levels.map((l, i) => (
                  <li key={i} className="flex flex-wrap items-center gap-2 text-[12.5px]">
                    <span className="w-5 text-[11px] text-muted-foreground">{i + 1}</span>
                    <span className="flex-1">{l.targets.map(targets.name).join(", ")}</span>
                    <span className="text-[11.5px] text-muted-foreground">{i < p.levels.length - 1 || p.repeat > 0 ? `next after ${l.delayMinutes} min` : `ack within ${l.delayMinutes} min`}</span>
                  </li>
                ))}
              </ol>
            </div>
          ))}
        </div>
      )}
      <Dialog open={!!editing} onOpenChange={(o) => !o && setEditing(null)}>
        {editing && <PolicyForm team={team} members={members} schedules={targets.schedules} policy={editing === "new" ? undefined : editing}
          first={!list.data?.length} onDone={() => { setEditing(null); list.reload(); }} />}
      </Dialog>
    </Card>
  );
}

function PolicyForm({ team, members, schedules, policy, first, onDone }: {
  team: Team; members: TeamMember[]; schedules: Schedule[]; policy?: EscalationPolicy; first: boolean; onDone: () => void;
}) {
  const toast = useToast();
  const { users, teamName } = useSession();
  const [f, setF] = useState(() => ({
    name: policy?.name ?? "Default",
    description: policy?.description ?? "",
    repeat: String(policy?.repeat ?? 0),
    teamDefault: policy?.teamDefault ?? first,
    levels: policy?.levels.map((l) => ({ ...l, targets: [...l.targets] })) ?? [{ delayMinutes: 5, targets: [] as EscalationTarget[] }],
  }));
  const setLevel = (i: number, l: EscalationLevel) => setF({ ...f, levels: f.levels.map((x, j) => (j === i ? l : x)) });
  const move = (i: number, d: number) => {
    const l = [...f.levels];
    [l[i], l[i + d]] = [l[i + d], l[i]];
    setF({ ...f, levels: l });
  };
  const own = schedules.filter((s) => s.teamId === team.id);
  const others = schedules.filter((s) => s.teamId !== team.id);
  const memberIds = new Set(members.map((m) => m.userId));
  const people = [...users.filter((u) => !u.disabled)].sort((a, b) => Number(memberIds.has(b.id)) - Number(memberIds.has(a.id)) || a.name.localeCompare(b.name));
  const label = (t: EscalationTarget) => t.kind === "USER"
    ? users.find((u) => u.id === t.id)?.name ?? "Unknown person"
    : (() => { const s = schedules.find((x) => x.id === t.id); return s ? `${teamName(s.teamId)} · ${s.name} (on call)` : "Deleted schedule"; })();
  const save = async () => {
    const body = { teamId: team.id, name: f.name, description: f.description, repeat: Number(f.repeat), teamDefault: f.teamDefault,
      levels: f.levels.map((l) => ({ delayMinutes: Number(l.delayMinutes), targets: l.targets })) };
    try {
      if (policy) await rpc.escalation.updatePolicy({ ...body, id: policy.id, version: policy.version });
      else await rpc.escalation.createPolicy(body);
      onDone();
    } catch (e) { toast.error(e); }
  };
  return (
    <DialogContent title={policy ? `Edit ${policy.name}` : `New escalation policy for ${team.name}`}
      description="Mayday pages level 1 when an alert fires. When nobody acknowledges in time, it pages the next level.">
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); save(); }}>
        <Field label="Name"><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>
        <Field label="Description"><Input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} placeholder="Business hours and nights" /></Field>
        <div className="space-y-3">
          {f.levels.map((l, i) => (
            <div key={i} className="space-y-2 rounded-xl border p-3">
              <div className="flex items-center gap-2">
                <span className="eyebrow flex-1">Level {i + 1}</span>
                <Button type="button" size="icon" variant="ghost" aria-label="Move level up" disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp /></Button>
                <Button type="button" size="icon" variant="ghost" aria-label="Move level down" disabled={i === f.levels.length - 1} onClick={() => move(i, 1)}><ArrowDown /></Button>
                <Button type="button" size="icon" variant="ghost" aria-label="Remove level" disabled={f.levels.length === 1}
                  onClick={() => setF({ ...f, levels: f.levels.filter((_, j) => j !== i) })}><Trash2 /></Button>
              </div>
              {l.targets.map((t) => (
                <div key={`${t.kind}:${t.id}`} className="flex items-center gap-2 rounded-lg border px-2 py-1">
                  <span className="flex-1">{label(t)}</span>
                  <Button type="button" size="icon" variant="ghost" aria-label="Remove target"
                    onClick={() => setLevel(i, { ...l, targets: l.targets.filter((x) => x !== t) })}><Trash2 /></Button>
                </div>
              ))}
              <NativeSelect value="" aria-label={`Add to level ${i + 1}`} onChange={(e) => {
                const [kind, id] = e.target.value.split(":");
                if (id && !l.targets.some((t) => t.kind === kind && t.id === id)) setLevel(i, { ...l, targets: [...l.targets, { kind: kind as EscalationTarget["kind"], id }] });
              }}>
                <option value="">Add who to page…</option>
                {own.length > 0 && <optgroup label="On call for a schedule of this team">{own.map((s) => <option key={s.id} value={`SCHEDULE:${s.id}`}>{s.name}</option>)}</optgroup>}
                {others.length > 0 && <optgroup label="On call for a schedule of another team">{others.map((s) => <option key={s.id} value={`SCHEDULE:${s.id}`}>{teamName(s.teamId)} · {s.name}</option>)}</optgroup>}
                <optgroup label="A person">{people.map((u) => <option key={u.id} value={`USER:${u.id}`}>{u.name}{memberIds.has(u.id) ? "" : ` · ${u.email}`}</option>)}</optgroup>
              </NativeSelect>
              <Field label="Minutes to acknowledge before the next level">
                <Input type="number" min={1} value={l.delayMinutes} onChange={(e) => setLevel(i, { ...l, delayMinutes: e.target.value as unknown as number })} required />
              </Field>
            </div>
          ))}
          {f.levels.length < 10 && (
            <Button type="button" size="sm" variant="outline" onClick={() => setF({ ...f, levels: [...f.levels, { delayMinutes: 10, targets: [] }] })}><Plus /> Add level</Button>
          )}
        </div>
        <div className="grid grid-cols-2 items-end gap-3">
          <Field label="Repeat all levels" hint="Extra cycles after the last level (0–9).">
            <Input type="number" min={0} max={9} value={f.repeat} onChange={(e) => setF({ ...f, repeat: e.target.value })} required />
          </Field>
          <label className="flex items-center gap-2 pb-2 text-[12.5px]">
            <Switch checked={f.teamDefault} onCheckedChange={(v) => setF({ ...f, teamDefault: v })} />
            Default for new alerts of {team.name}
          </label>
        </div>
        <p className="text-[11.5px] text-muted-foreground">Administrators can limit the minutes to acknowledge and the alert severities that page (settings “Longest escalation delay” and “Page from alert severity”).</p>
        <Button type="submit" className="w-full">Save policy</Button>
      </form>
    </DialogContent>
  );
}

export function AlertEscalation({ alert }: { alert: Alert }) {
  const { userName, canWrite } = useSession();
  const toast = useToast();
  const esc = useQuery(() => rpc.escalation.getEscalation<Escalation>({ id: alert.id }), [alert.id]);
  useLive((t, sid) => (t === "alert.paged" || t === "alert.escalation_exhausted" || t === "alert.updated") && sid === alert.id, esc.reload);
  const policy = useQuery(esc.data ? () => rpc.escalation.getPolicy<EscalationPolicy>({ id: esc.data!.policyId }) : null, [esc.data?.policyId]);
  const e = esc.data;
  const escalate = async () => {
    try { esc.setData(await rpc.escalation.escalateNow<Escalation>({ id: alert.id })); } catch (err) { toast.error(err); }
  };
  return (
    <Card>
      <CardHeader title="Escalation" action={canWrite && alert.status === "FIRING" &&
        <Button size="sm" variant="outline" onClick={escalate}><ChevronsUp /> Escalate now</Button>} />
      {esc.loading && !e && !esc.error ? <div className="flex justify-center p-6"><Spinner /></div> : !e ? (
        <p className="px-4 py-4 text-[12.5px] text-muted-foreground">Nobody was paged. The team has no default policy, or the severity is below the paging threshold.</p>
      ) : (
        <div className="space-y-3 p-4">
          <div className="flex flex-wrap items-center gap-2 text-[12px]">
            <Badge tone={STATUS_TONE[e.status]}>{e.status.toLowerCase()}</Badge>
            <span className="text-muted-foreground">{policy.data?.name ?? "Deleted policy"}</span>
          </div>
          {e.status === "ACTIVE" && e.nextAt && <div className="text-[12px] text-muted-foreground">Next level at {dateTime(e.nextAt)} unless acknowledged</div>}
          <ol className="space-y-2">
            {[...e.pages].reverse().map((p, i) => (
              <li key={i} className="text-[12.5px]">
                <div className="flex gap-2">
                  <span className="eyebrow">Level {p.level}{p.cycle > 0 && ` · cycle ${p.cycle + 1}`}</span>
                  {p.manual && <Badge tone="outline">Manual</Badge>}
                  <span className="ml-auto text-muted-foreground">{ago(p.at)}</span>
                </div>
                <div>{p.userIds.length ? p.userIds.map(userName).join(", ") : <Badge tone="critical">Nobody reached</Badge>}</div>
              </li>
            ))}
          </ol>
        </div>
      )}
    </Card>
  );
}
