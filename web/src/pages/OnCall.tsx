import { useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import { ArrowDown, ArrowUp, CalendarClock, Lock, Pencil, Plus, Trash2, UserPlus, Users } from "lucide-react";
import { items, rpc, type OnCall, type Schedule, type ScheduleOverride, type Shift, type ShiftUnit, type Team, type TeamMember, type TeamRole } from "@/lib/api";
import { useLive, useQuery } from "@/lib/hooks";
import { useSession, useTeamOwner } from "@/lib/session";
import { EscalationPolicies } from "@/pages/Escalation";
import { useToast } from "@/lib/toast";
import { cn, dateTime, duration, fromLocalInput, toLocalInput } from "@/lib/utils";
import { PageHeader } from "@/components/common";
import { Badge, Button, Card, CardHeader, Dialog, DialogContent, Empty, Field, Input, NativeSelect, Spinner } from "@/components/ui";

const DAY = 86_400_000;
const UNITS: ShiftUnit[] = ["HOURS", "DAYS", "WEEKS"];
const ZONES: string[] = (() => {
  try { return (Intl as unknown as { supportedValuesOf(k: string): string[] }).supportedValuesOf("timeZone"); } catch { return ["UTC"]; }
})();

function every(s: Schedule) {
  const unit = s.shiftUnit.toLowerCase();
  return s.shiftLength === 1 ? `Every ${unit.slice(0, -1)}` : `Every ${s.shiftLength} ${unit}`;
}

export function OnCallPage() {
  const { teams, teamName } = useSession();
  const [params, setParams] = useSearchParams();
  const ref = params.get("team") ?? "";
  const team = teams.find((t) => t.id === ref || t.slug === ref);
  const teamId = team?.id ?? "";
  const setTeamId = (id: string) => setParams(id ? { team: teams.find((t) => t.id === id)?.slug ?? id } : {}, { replace: true });
  const now = useQuery(() => items(rpc.oncall.getOnCall<{ items: OnCall[] }>({ teamIds: teamId ? [teamId] : [] })), [teamId]);
  useLive((t) => t === "oncall.changed", now.reload);
  return (
    <>
      <PageHeader title="On-call" eyebrow="Who responds now"
        actions={
          <NativeSelect value={teamId} onChange={(e) => setTeamId(e.target.value)} className="w-52" aria-label="Team">
            <option value="">All teams</option>
            {teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </NativeSelect>
        } />
      <section className="mb-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {!now.data ? <div className="col-span-full flex justify-center p-8"><Spinner /></div>
          : now.data.length === 0 ? <Card className="col-span-full"><Empty icon={<CalendarClock />} title="No schedules">{team ? "Team owners create schedules for their team." : "Select a team to create its first schedule."}</Empty></Card>
          : now.data.map((c) => <NowCard key={c.scheduleId} entry={c} team={teamName(c.teamId)} />)}
      </section>
      {team ? (
        <div className="grid gap-5 xl:grid-cols-[1fr_360px]">
          <Schedules team={team} />
          <Members team={team} />
        </div>
      ) : <p className="text-[12px] text-muted-foreground">Select a team to see its schedules, overrides, escalation policies and members.</p>}
    </>
  );
}

function NowCard({ entry, team }: { entry: OnCall; team: string }) {
  const { userName } = useSession();
  return (
    <Card className="p-4">
      <div className="eyebrow">{team} · {entry.scheduleName}</div>
      {entry.userId ? (
        <>
          <div className="mt-2 text-[18px] font-light">{userName(entry.userId)}</div>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-[12px] text-muted-foreground">
            {entry.until && <span>until {dateTime(entry.until)}</span>}
            {entry.overrideId && <Badge tone="outline">Override</Badge>}
          </div>
        </>
      ) : <div className="mt-2"><Badge tone="critical">Nobody on call</Badge></div>}
    </Card>
  );
}

function Schedules({ team }: { team: Team }) {
  const isOwner = useTeamOwner()(team);
  const list = useQuery(() => items(rpc.oncall.listSchedules<{ items: Schedule[] }>({ teamId: team.id })), [team.id]);
  const members = useQuery(() => items(rpc.directory.listTeamMembers<{ items: TeamMember[] }>({ id: team.id })), [team.id]);
  useLive((t) => t === "oncall.changed", () => { list.reload(); members.reload(); });
  const [picked, setPicked] = useState<string | null>(null);
  const [editing, setEditing] = useState<Schedule | "new" | null>(null);
  const schedule = list.data?.find((s) => s.id === picked) ?? list.data?.[0];
  return (
    <div className="space-y-5">
      <Card>
        <CardHeader title="Schedules" action={isOwner && <Button size="sm" variant="outline" onClick={() => setEditing("new")}><Plus /> New schedule</Button>} />
        {!list.data ? <div className="flex justify-center p-8"><Spinner /></div> : list.data.length === 0 ? (
          <Empty icon={<CalendarClock />} title="No schedules">{isOwner ? "Create a rotation of team members." : "A team owner can create a rotation."}</Empty>
        ) : (
          <>
            <div className="flex flex-wrap gap-1 border-b px-4 py-2">
              {list.data.map((s) => (
                <button key={s.id} onClick={() => setPicked(s.id)}
                  className={cn("rounded-full px-3 py-1 text-[11.5px] font-medium uppercase tracking-wider", s.id === schedule?.id ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:bg-accent")}>
                  {s.name}
                </button>
              ))}
            </div>
            {schedule && <ScheduleView key={schedule.id} schedule={schedule} isOwner={isOwner} onEdit={() => setEditing(schedule)} />}
          </>
        )}
      </Card>
      {schedule && <Overrides schedule={schedule} team={team} members={members.data ?? []} isOwner={isOwner} />}
      <EscalationPolicies team={team} members={members.data ?? []} />
      <Dialog open={!!editing} onOpenChange={(o) => !o && setEditing(null)}>
        {editing && <ScheduleForm team={team} members={members.data ?? []} schedule={editing === "new" ? undefined : editing}
          onDone={(s) => { setEditing(null); list.reload(); if (s) setPicked(s.id); }} />}
      </Dialog>
    </div>
  );
}

function ScheduleView({ schedule, isOwner, onEdit }: { schedule: Schedule; isOwner: boolean; onEdit: () => void }) {
  const { userName } = useSession();
  const toast = useToast();
  const [from] = useState(() => { const d = new Date(); d.setHours(0, 0, 0, 0); return d; });
  const to = useMemo(() => new Date(from.getTime() + 14 * DAY), [from]);
  const shifts = useQuery(() => items(rpc.oncall.listShifts<{ items: Shift[] }>({ scheduleId: schedule.id, from, to })), [schedule.id, schedule.version]);
  useLive((t) => t === "oncall.changed", shifts.reload);
  const span = to.getTime() - from.getTime();
  const people = [...new Set((shifts.data ?? []).map((s) => s.userId ?? ""))];
  return (
    <div className="space-y-4 p-4">
      <div className="flex flex-wrap items-center gap-2 text-[12px] text-muted-foreground">
        <span>{every(schedule)} · {schedule.timeZone} · first handoff {dateTime(schedule.rotationStart)}</span>
        <span className="flex-1" />
        {isOwner && <Button size="sm" variant="ghost" onClick={onEdit}><Pencil /> Edit</Button>}
        {isOwner && <Button size="sm" variant="ghost" aria-label="Delete schedule"
          onClick={() => toast.undoable(`Schedule ${schedule.name} and its overrides will be deleted.`, () => rpc.oncall.deleteSchedule({ id: schedule.id }))}><Trash2 /></Button>}
      </div>
      {!shifts.data ? <div className="flex justify-center p-6"><Spinner /></div> : (
        <>
          <div className="relative h-10 overflow-hidden rounded-xl border" aria-label="Next 14 days">
            {shifts.data.map((s) => {
              const left = ((new Date(s.startsAt).getTime() - from.getTime()) / span) * 100;
              const width = ((new Date(s.endsAt).getTime() - new Date(s.startsAt).getTime()) / span) * 100;
              const shade = ["bg-foreground/80", "bg-foreground/55", "bg-foreground/35", "bg-foreground/20"][people.indexOf(s.userId ?? "") % 4];
              return (
                <div key={s.startsAt} title={`${s.userId ? userName(s.userId) : "Nobody"} · ${dateTime(s.startsAt)} – ${dateTime(s.endsAt)}`}
                  className={cn("absolute inset-y-0 border-r border-background", !s.userId ? "bg-destructive/70" : s.overrideId ? "bg-warning" : shade)}
                  style={{ left: `${left}%`, width: `${width}%` }} />
              );
            })}
            {Array.from({ length: 13 }, (_, i) => <div key={i} className="absolute inset-y-0 w-px bg-background/60" style={{ left: `${((i + 1) / 14) * 100}%` }} />)}
          </div>
          <div className="flex justify-between text-[11px] text-muted-foreground"><span>{from.toLocaleDateString()}</span><span>{new Date(to.getTime() - 1).toLocaleDateString()}</span></div>
          <div className="divide-y rounded-xl border">
            {shifts.data.map((s) => (
              <div key={s.startsAt} className="flex flex-wrap items-center gap-3 px-3 py-2">
                <span className="w-40 truncate">{s.userId ? userName(s.userId) : <Badge tone="critical">Nobody</Badge>}</span>
                <span className="flex-1 text-[12px] text-muted-foreground">{dateTime(s.startsAt)} – {dateTime(s.endsAt)} · {duration((new Date(s.endsAt).getTime() - new Date(s.startsAt).getTime()) / 60000)}</span>
                {s.overrideId && <Badge tone="warning">Override</Badge>}
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function ScheduleForm({ team, members, schedule, onDone }: { team: Team; members: TeamMember[]; schedule?: Schedule; onDone: (s?: Schedule) => void }) {
  const toast = useToast();
  const [f, setF] = useState(() => ({
    name: schedule?.name ?? "Primary",
    timeZone: schedule?.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? "UTC",
    rotationStart: toLocalInput(schedule?.rotationStart ?? new Date().toISOString()),
    shiftLength: String(schedule?.shiftLength ?? 1),
    shiftUnit: schedule?.shiftUnit ?? ("WEEKS" as ShiftUnit),
    participants: schedule?.participants ?? [],
  }));
  const name = (id: string) => members.find((m) => m.userId === id)?.name ?? id.slice(0, 8);
  const move = (i: number, d: number) => {
    const p = [...f.participants];
    [p[i], p[i + d]] = [p[i + d], p[i]];
    setF({ ...f, participants: p });
  };
  const save = async () => {
    const body = { teamId: team.id, name: f.name, timeZone: f.timeZone, rotationStart: fromLocalInput(f.rotationStart), shiftLength: Number(f.shiftLength), shiftUnit: f.shiftUnit, participants: f.participants };
    try {
      onDone(schedule
        ? await rpc.oncall.updateSchedule<Schedule>({ ...body, id: schedule.id, version: schedule.version })
        : await rpc.oncall.createSchedule<Schedule>(body));
    } catch (e) { toast.error(e); }
  };
  return (
    <DialogContent title={schedule ? `Edit ${schedule.name}` : `New schedule for ${team.name}`} description="Shifts hand off at the start time, counted in the time zone of the schedule.">
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); save(); }}>
        <Field label="Name"><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="First handoff"><Input type="datetime-local" value={f.rotationStart} onChange={(e) => setF({ ...f, rotationStart: e.target.value })} required /></Field>
          <Field label="Time zone">
            <Input list="mayday-zones" value={f.timeZone} onChange={(e) => setF({ ...f, timeZone: e.target.value })} required />
            <datalist id="mayday-zones">{ZONES.map((z) => <option key={z} value={z} />)}</datalist>
          </Field>
          <Field label="Shift length"><Input type="number" min={1} value={f.shiftLength} onChange={(e) => setF({ ...f, shiftLength: e.target.value })} required /></Field>
          <Field label="Unit">
            <NativeSelect value={f.shiftUnit} onChange={(e) => setF({ ...f, shiftUnit: e.target.value as ShiftUnit })}>
              {UNITS.map((u) => <option key={u} value={u}>{u.toLowerCase()}</option>)}
            </NativeSelect>
          </Field>
        </div>
        <Field label="Rotation order" hint="Only team members can be in the rotation.">
          <div className="space-y-1">
            {f.participants.map((id, i) => (
              <div key={id} className="flex items-center gap-2 rounded-lg border px-2 py-1">
                <span className="w-5 text-[11px] text-muted-foreground">{i + 1}</span>
                <span className="flex-1">{name(id)}</span>
                <Button type="button" size="icon" variant="ghost" aria-label="Move up" disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp /></Button>
                <Button type="button" size="icon" variant="ghost" aria-label="Move down" disabled={i === f.participants.length - 1} onClick={() => move(i, 1)}><ArrowDown /></Button>
                <Button type="button" size="icon" variant="ghost" aria-label="Remove" onClick={() => setF({ ...f, participants: f.participants.filter((x) => x !== id) })}><Trash2 /></Button>
              </div>
            ))}
            <NativeSelect value="" aria-label="Add to rotation" onChange={(e) => e.target.value && setF({ ...f, participants: [...f.participants, e.target.value] })}>
              <option value="">Add a team member…</option>
              {members.filter((m) => !m.disabled && !f.participants.includes(m.userId)).map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
            </NativeSelect>
          </div>
        </Field>
        <Button type="submit" className="w-full">Save schedule</Button>
      </form>
    </DialogContent>
  );
}

function Overrides({ schedule, team, members, isOwner }: { schedule: Schedule; team: Team; members: TeamMember[]; isOwner: boolean }) {
  const { me, userName, canWrite } = useSession();
  const toast = useToast();
  const [range] = useState(() => ({ from: new Date(Date.now() - DAY), to: new Date(Date.now() + 92 * DAY) }));
  const list = useQuery(() => items(rpc.oncall.listOverrides<{ items: ScheduleOverride[] }>({ scheduleId: schedule.id, ...range })), [schedule.id]);
  useLive((t) => t === "oncall.changed", list.reload);
  const isMember = !!me.user.teams[team.id];
  const [open, setOpen] = useState(false);
  return (
    <Card>
      <CardHeader title="Overrides" action={canWrite && (isOwner || isMember) && <Button size="sm" variant="outline" onClick={() => setOpen(true)}><UserPlus /> Add override</Button>} />
      {!list.data ? <div className="flex justify-center p-6"><Spinner /></div> : list.data.length === 0 ? (
        <Empty title="No overrides">When someone is sick or busy, a team member covers for a set period.</Empty>
      ) : (
        <div className="divide-y">
          {list.data.map((o) => (
            <div key={o.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="flex-1">
                <div>{userName(o.userId)}</div>
                <div className="text-[12px] text-muted-foreground">{dateTime(o.startsAt)} – {dateTime(o.endsAt)}{o.reason && ` · ${o.reason}`}</div>
              </div>
              {(isOwner || o.userId === me.user.id) && (
                <Button variant="ghost" size="icon" aria-label="Remove override"
                  onClick={() => toast.undoable(`The override of ${userName(o.userId)} will be removed.`, () => rpc.oncall.deleteOverride({ id: o.id }))}><Trash2 /></Button>
              )}
            </div>
          ))}
        </div>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        {open && <OverrideForm schedule={schedule} members={members} isOwner={isOwner} onDone={() => { setOpen(false); list.reload(); }} />}
      </Dialog>
    </Card>
  );
}

function OverrideForm({ schedule, members, isOwner, onDone }: { schedule: Schedule; members: TeamMember[]; isOwner: boolean; onDone: () => void }) {
  const { me } = useSession();
  const toast = useToast();
  const [f, setF] = useState(() => ({
    userId: me.user.id,
    startsAt: toLocalInput(new Date().toISOString()),
    endsAt: toLocalInput(new Date(Date.now() + DAY).toISOString()),
    reason: "",
  }));
  const choices = isOwner ? members.filter((m) => !m.disabled) : members.filter((m) => m.userId === me.user.id);
  return (
    <DialogContent title={`Override ${schedule.name}`} description={isOwner ? "Put any team member on call for a set period." : "Put yourself on call for a set period."}>
      <form className="space-y-4" onSubmit={async (e) => {
        e.preventDefault();
        try {
          await rpc.oncall.createOverride({ scheduleId: schedule.id, userId: f.userId, startsAt: fromLocalInput(f.startsAt), endsAt: fromLocalInput(f.endsAt), reason: f.reason });
          onDone();
        } catch (err) { toast.error(err); }
      }}>
        <Field label="Who covers">
          <NativeSelect value={f.userId} onChange={(e) => setF({ ...f, userId: e.target.value })} disabled={!isOwner}>
            {choices.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
          </NativeSelect>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="From"><Input type="datetime-local" value={f.startsAt} onChange={(e) => setF({ ...f, startsAt: e.target.value })} required /></Field>
          <Field label="Until"><Input type="datetime-local" value={f.endsAt} onChange={(e) => setF({ ...f, endsAt: e.target.value })} required /></Field>
        </div>
        <Field label="Reason"><Input value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} placeholder="Ana is sick" /></Field>
        <Button type="submit" className="w-full">Add override</Button>
      </form>
    </DialogContent>
  );
}

function Members({ team }: { team: Team }) {
  const { users, reloadDirectory } = useSession();
  const isOwner = useTeamOwner()(team);
  const toast = useToast();
  const list = useQuery(() => items(rpc.directory.listTeamMembers<{ items: TeamMember[] }>({ id: team.id })), [team.id]);
  const set = (userId: string, role?: TeamRole) =>
    rpc.directory.setTeamMember({ teamId: team.id, userId, role }).then(() => { list.reload(); reloadDirectory(); }).catch(toast.error);
  const membersLocked = team.memberGroups.length > 0;
  const ownersLocked = team.ownerGroups.length > 0;
  const outside = users.filter((u) => !u.disabled && !list.data?.some((m) => m.userId === u.id));
  return (
    <Card className="self-start">
      <CardHeader title={<span className="flex items-center gap-2"><Users className="size-4" /> Members</span>} />
      {(membersLocked || ownersLocked) && (
        <div className="flex gap-2 border-b px-4 py-3 text-[12px] text-muted-foreground">
          <Lock className="mt-0.5 size-3.5 shrink-0" />
          <span>Your identity provider manages the {membersLocked && ownersLocked ? "members and owners" : membersLocked ? "members" : "owners"} of this team.</span>
        </div>
      )}
      {!list.data ? <div className="flex justify-center p-6"><Spinner /></div> : (
        <div className="divide-y">
          {list.data.map((m) => (
            <div key={m.userId} className={cn("flex items-center gap-2 px-4 py-2", m.disabled && "opacity-50")}>
              <div className="min-w-0 flex-1"><div className="truncate">{m.name}</div><div className="truncate text-[11px] text-muted-foreground">{m.email}</div></div>
              {isOwner && !ownersLocked ? (
                <NativeSelect value={m.role} onChange={(e) => set(m.userId, e.target.value as TeamRole)} className="w-28" aria-label={`Role of ${m.name}`}>
                  <option value="MEMBER">Member</option><option value="ADMIN">Owner</option>
                </NativeSelect>
              ) : <Badge tone={m.role === "ADMIN" ? "solid" : "neutral"}>{m.role === "ADMIN" ? "Owner" : "Member"}</Badge>}
              {isOwner && !membersLocked && <Button variant="ghost" size="icon" aria-label={`Remove ${m.name}`} onClick={() => set(m.userId)}><Trash2 /></Button>}
            </div>
          ))}
          {list.data.length === 0 && <Empty title="No members" />}
        </div>
      )}
      {isOwner && !membersLocked && (
        <div className="border-t p-4">
          <NativeSelect value="" aria-label="Add member" onChange={(e) => e.target.value && set(e.target.value, "MEMBER")}>
            <option value="">Add a member…</option>
            {outside.map((u) => <option key={u.id} value={u.id}>{u.name} · {u.email}</option>)}
          </NativeSelect>
        </div>
      )}
    </Card>
  );
}
