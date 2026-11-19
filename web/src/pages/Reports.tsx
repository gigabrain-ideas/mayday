import { useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { rpc, SEVERITIES, type Report, type Severity } from "@/lib/api";
import { useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { cn, duration } from "@/lib/utils";
import { Card, CardHeader, Input, MultiSelect, Spinner, Table, Td, Th } from "@/components/ui";
import { PageHeader, SeverityBadge } from "@/components/common";

const PERIODS = [
  { id: "7", label: "7 days" },
  { id: "30", label: "30 days" },
  { id: "90", label: "90 days" },
  { id: "custom", label: "Custom" },
];

/** Severity is ordinal: one gray ramp, darkest = most severe. Red stays reserved for status. */
const SEV_FILL: Record<Severity, string> = { SEV1: "var(--chart-1)", SEV2: "var(--chart-2)", SEV3: "var(--chart-3)", SEV4: "var(--chart-4)", SEV5: "var(--chart-5)" };

export function ReportsPage() {
  const [params, setParams] = useSearchParams();
  const { teams } = useSession();
  const period = params.get("period") ?? "30";
  const team = params.get("team")?.split(",").filter(Boolean) ?? [];
  const severity = (params.get("severity")?.split(",").filter(Boolean) ?? []) as Severity[];
  const [from, setFrom] = useState(params.get("from") ?? "");
  const [to, setTo] = useState(params.get("to") ?? "");
  const range = useMemo(() => {
    if (period === "custom" && from && to) return { from: new Date(from).toISOString(), to: new Date(to + "T23:59:59").toISOString() };
    const days = Number(period === "custom" ? 30 : period);
    const end = new Date();
    return { from: new Date(end.getTime() - days * 86400000).toISOString(), to: end.toISOString() };
  }, [period, from, to]);
  const { data: r, loading } = useQuery(() => rpc.reports.getReport<Report>({ team, severity, from: range.from, to: range.to }), [team.join(), severity.join(), range.from, range.to]);

  const set = (k: string, v: string | string[]) => {
    const n = new URLSearchParams(params);
    const val = Array.isArray(v) ? v.join(",") : v;
    if (val) n.set(k, val); else n.delete(k);
    setParams(n, { replace: true });
  };

  return (
    <>
      <PageHeader title="Reports" eyebrow={team.length ? `${team.length} team${team.length > 1 ? "s" : ""}` : "All teams"} />
      <div className="mb-5 flex flex-wrap items-center gap-2">
        <div className="inline-flex rounded-full border bg-card p-0.5">
          {PERIODS.map((p) => (
            <button key={p.id} onClick={() => set("period", p.id === "30" ? "" : p.id)}
              className={cn("h-8 rounded-full px-3 text-[11.5px] font-medium uppercase tracking-wider", period === p.id ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground")}>
              {p.label}
            </button>
          ))}
        </div>
        {period === "custom" && (
          <>
            <Input type="date" value={from} onChange={(e) => { setFrom(e.target.value); set("from", e.target.value); }} className="w-40" aria-label="From" />
            <Input type="date" value={to} onChange={(e) => { setTo(e.target.value); set("to", e.target.value); }} className="w-40" aria-label="To" />
          </>
        )}
        <MultiSelect label="Teams" value={team} onChange={(v) => set("team", v)} options={teams.map((t) => ({ value: t.slug, label: t.name }))} />
        <MultiSelect label="Severity" value={severity} onChange={(v) => set("severity", v)} options={SEVERITIES.map((s) => ({ value: s, label: s }))} />
      </div>
      {!r ? <div className="flex justify-center p-16"><Spinner /></div> : (
        <div className={cn("space-y-5 transition-opacity duration-150", loading && "opacity-60")}>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
            <Stat label="Incidents" value={r.incidents} sub={`${r.open} open`} />
            <Stat label="Alerts" value={r.alerts} sub={`${r.alertsWithIncident} became incidents`} />
            <Stat label="Mean time to acknowledge" value={duration(r.mttaMinutes)} />
            <Stat label="Mean time to resolve" value={duration(r.mttrMinutes)} />
            <Stat label="Mean time to detect" value={duration(r.mttdMinutes)} />
            <Stat label="Postmortems published" value={r.postmortemsRequired ? `${Math.round((r.postmortemsPublished / r.postmortemsRequired) * 100)}%` : "—"}
              sub={`${r.postmortemsPublished} of ${r.postmortemsRequired} required`} alert={r.postmortemsOverdue > 0 ? `${r.postmortemsOverdue} overdue` : undefined} />
          </div>
          <Card>
            <CardHeader title="Incidents per day by severity" action={<Legend />} />
            <DayChart r={r} />
          </Card>
          <div className="grid gap-5 xl:grid-cols-2">
            <Card>
              <CardHeader title="By team" />
              <Table>
                <thead><tr><Th>Team</Th><Th className="text-right">Incidents</Th><Th className="text-right">Open</Th><Th className="text-right">Alerts</Th><Th className="text-right">MTTR</Th></tr></thead>
                <tbody>
                  {r.byTeam.map((t) => (
                    <tr key={t.teamId}><Td>{t.teamName}</Td><Td className="text-right">{t.incidents}</Td><Td className="text-right">{t.open}</Td><Td className="text-right">{t.alerts}</Td><Td className="text-right">{duration(t.mttrMinutes)}</Td></tr>
                  ))}
                  {r.byTeam.length === 0 && <tr><Td colSpan={5} className="text-center text-muted-foreground">No data in this period.</Td></tr>}
                </tbody>
              </Table>
            </Card>
            <Card>
              <CardHeader title="Longest incidents" />
              <Table>
                <thead><tr><Th>Key</Th><Th>Title</Th><Th>Severity</Th><Th className="text-right">Duration</Th></tr></thead>
                <tbody>
                  {r.longest.map((i) => (
                    <tr key={i.key}>
                      <Td className="whitespace-nowrap"><Link to={`/incidents/${i.key}`} className="hover:underline">{i.key}</Link></Td>
                      <Td className="max-w-64"><span className="line-clamp-1">{i.title}</span></Td>
                      <Td><SeverityBadge severity={i.severity} /></Td>
                      <Td className="text-right whitespace-nowrap">{duration(i.durationMinutes)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </Card>
          </div>
        </div>
      )}
    </>
  );
}

function Stat({ label, value, sub, alert }: { label: string; value: React.ReactNode; sub?: string; alert?: string }) {
  return (
    <Card className="p-4">
      <div className="eyebrow">{label}</div>
      <div className="mt-2 text-[26px] font-light leading-none">{value}</div>
      {sub && <div className="mt-2 text-[12px] text-muted-foreground">{sub}</div>}
      {alert && <div className="mt-1 text-[12px] font-medium text-destructive">{alert}</div>}
    </Card>
  );
}

function Legend() {
  return (
    <div className="flex flex-wrap gap-3">
      {SEVERITIES.map((s) => (
        <span key={s} className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
          <span className="size-2.5 rounded-sm" style={{ background: SEV_FILL[s] }} />{s}
        </span>
      ))}
    </div>
  );
}

function DayChart({ r }: { r: Report }) {
  const [hover, setHover] = useState<number | null>(null);
  const days = r.byDay;
  const totals = days.map((d) => SEVERITIES.reduce((a, s) => a + (d.bySeverity[s] ?? 0), 0));
  const max = Math.max(1, ...totals);
  const ticks = Array.from(new Set([0, Math.ceil(max / 2), max]));
  const W = 1000, H = 220, pad = { l: 28, r: 8, t: 10, b: 24 };
  const bw = (W - pad.l - pad.r) / Math.max(1, days.length);
  const y = (v: number) => pad.t + (H - pad.t - pad.b) * (1 - v / max);
  const hd = hover != null ? days[hover] : null;

  return (
    <div className="relative px-4 pb-4 pt-2">
      <svg viewBox={`0 0 ${W} ${H}`} className="h-56 w-full" role="img" aria-label="Incidents per day, stacked by severity" onMouseLeave={() => setHover(null)}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={pad.l} x2={W - pad.r} y1={y(t)} y2={y(t)} stroke="var(--border)" strokeWidth={1} />
            <text x={pad.l - 6} y={y(t) + 4} textAnchor="end" fontSize={10} fill="var(--muted-foreground)">{t}</text>
          </g>
        ))}
        {days.map((d, i) => {
          let acc = 0;
          const x = pad.l + i * bw;
          const w = Math.max(1, bw - 2);
          return (
            <g key={d.day} onMouseEnter={() => setHover(i)}>
              <rect x={x} y={pad.t} width={bw} height={H - pad.t - pad.b} fill={hover === i ? "var(--accent)" : "transparent"} />
              {[...SEVERITIES].reverse().map((s) => {
                const v = d.bySeverity[s] ?? 0;
                if (!v) return null;
                const y0 = y(acc), y1 = y(acc + v);
                acc += v;
                return <rect key={s} x={x + 1} y={y1 + 1} width={w} height={Math.max(0, y0 - y1 - 2)} rx={2} fill={SEV_FILL[s]} />;
              })}
              {(i % Math.ceil(days.length / 10) === 0) && (
                <text x={x + bw / 2} y={H - 6} textAnchor="middle" fontSize={10} fill="var(--muted-foreground)">{d.day.slice(5)}</text>
              )}
            </g>
          );
        })}
      </svg>
      {hd && hover != null && (
        <div className="pointer-events-none absolute top-2 z-10 rounded-md border bg-card/95 px-3 py-2 text-[12px] shadow-lg backdrop-blur"
          style={{ left: `clamp(8px, calc(${((pad.l + hover * bw + bw / 2) / W) * 100}% - 80px), calc(100% - 180px))` }}>
          <div className="font-medium">{hd.day} · {totals[hover]} incidents</div>
          {SEVERITIES.filter((s) => hd.bySeverity[s]).map((s) => (
            <div key={s} className="flex items-center gap-2 text-muted-foreground"><span className="size-2 rounded-sm" style={{ background: SEV_FILL[s] }} />{s}: {hd.bySeverity[s]}</div>
          ))}
          <div className="text-muted-foreground">Alerts: {hd.alerts}</div>
        </div>
      )}
    </div>
  );
}
