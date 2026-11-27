import { useNavigate, useSearchParams } from "react-router";
import { BellOff } from "lucide-react";
import { rpc, SEVERITIES, type Alert, type AlertStatus, type Page, type Severity } from "@/lib/api";
import { RELOAD_SPREAD, useLive, useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { ago } from "@/lib/utils";
import { Card, Empty, MultiSelect, Spinner, Table, Td, Th } from "@/components/ui";
import { AlertStatusBadge, LoadFailed, PageHeader, SeverityBadge } from "@/components/common";

export function AlertsPage() {
  const [params, setParams] = useSearchParams();
  const nav = useNavigate();
  const { teams, teamName } = useSession();
  const status = (params.get("status")?.split(",").filter(Boolean) ?? ["FIRING", "ACKNOWLEDGED"]) as AlertStatus[];
  const severity = (params.get("severity")?.split(",").filter(Boolean) ?? []) as Severity[];
  const team = params.get("team")?.split(",").filter(Boolean) ?? [];
  const { data, error, loading, reload } = useQuery(() => rpc.alerts.listAlerts<Page<Alert>>({ status, severity, team, limit: 100 }), [status.join(), severity.join(), team.join()]);
  useLive((t) => t.startsWith("alert."), reload, RELOAD_SPREAD);
  const set = (k: string, v: string[]) => {
    const n = new URLSearchParams(params);
    n.set(k, v.join(","));
    setParams(n, { replace: true });
  };

  return (
    <>
      <PageHeader title="Alerts" eyebrow={error ? "Not loaded" : data ? `${data.items.length} shown` : "Loading"} />
      <div className="mb-4 flex flex-wrap gap-2">
        <MultiSelect label="Status" value={status} onChange={(v) => set("status", v)}
          options={(["FIRING", "ACKNOWLEDGED", "RESOLVED"] as AlertStatus[]).map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase() }))} />
        <MultiSelect label="Severity" value={severity} onChange={(v) => set("severity", v)} options={SEVERITIES.map((s) => ({ value: s, label: s }))} />
        <MultiSelect label="Team" value={team} onChange={(v) => set("team", v)} options={teams.map((t) => ({ value: t.slug, label: t.name }))} />
      </div>
      <Card>
        {error ? <LoadFailed what="alerts" error={error} onRetry={reload} /> : loading && !data ? <div className="flex justify-center p-10"><Spinner /></div> : !data?.items.length ? (
          <Empty icon={<BellOff />} title="No alerts">Send alerts with the API, the Alertmanager webhook or the MCP tool <code>send_alert</code>.</Empty>
        ) : (
          <Table>
            <thead><tr><Th>Alert</Th><Th>Severity</Th><Th>Status</Th><Th className="hidden md:table-cell">Team</Th><Th className="hidden md:table-cell">Source</Th><Th>Count</Th><Th>Last seen</Th></tr></thead>
            <tbody>
              {data.items.map((a) => (
                <tr key={a.id} onClick={() => nav(`/alerts/${a.id}`)} className="cursor-pointer transition-colors hover:bg-accent/60">
                  <Td className="max-w-[32rem]"><span className="line-clamp-1">{a.title}</span>{a.incidentId && <span className="eyebrow">linked to incident</span>}</Td>
                  <Td><SeverityBadge severity={a.severity} /></Td>
                  <Td><AlertStatusBadge status={a.status} /></Td>
                  <Td className="hidden text-muted-foreground md:table-cell">{teamName(a.teamId)}</Td>
                  <Td className="hidden text-muted-foreground md:table-cell">{a.source}</Td>
                  <Td className="text-muted-foreground">×{a.count}</Td>
                  <Td className="whitespace-nowrap text-muted-foreground">{ago(a.lastSeenAt)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </>
  );
}
