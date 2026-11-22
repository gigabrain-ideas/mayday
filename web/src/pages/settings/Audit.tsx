import { ShieldCheck } from "lucide-react";
import { rpc, type AuditSinkStatus, type AuditStatus } from "@/lib/api";
import { useQuery } from "@/lib/hooks";
import { Badge, Card, CardHeader, Empty, Spinner, Table, Td, Th } from "@/components/ui";

const SINK_LABEL: Record<string, string> = {
  stdout: "Standard output", file: "File", http: "HTTP collector", loki: "Grafana Loki", sentinel: "Microsoft Sentinel",
};

const RECORDED: [string, string][] = [
  ["authorization", "Every API, MCP and tool call: allowed or denied, with the actor and the person an agent acts for"],
  ["authentication", "Sign-in and sign-out"],
  ["token", "API tokens issued and revoked, delegated tokens, token exchange"],
  ["identity", "Users, roles, team members, identity provider group sync, agent admin opt-ins"],
  ["policy", "Settings, enforcement and change requests"],
  ["agent", "Agent configuration, credentials and runs"],
  ["configuration", "LLM provider and MCP servers"],
  ["system", "Stream start, stop and lost records"],
];

function health(s: AuditSinkStatus) {
  if (s.dropped > 0) return <Badge tone="critical">Records lost</Badge>;
  if (s.lastError) return <Badge tone="warning">Retrying</Badge>;
  return <Badge tone="success">Delivering</Badge>;
}

const n = (v: number) => Number(v).toLocaleString();

export function AuditPage() {
  const { data } = useQuery(() => rpc.audit.getAuditStatus<AuditStatus>(), []);
  if (!data) return <div className="flex justify-center p-10"><Spinner /></div>;
  return (
    <div className="grid gap-5 xl:grid-cols-[2fr_1fr]">
      <div className="space-y-5">
        <Card className="p-5">
          <div className="mb-3 flex items-center gap-2">
            <ShieldCheck className="size-4" />
            <span className="font-medium">Audit stream</span>
            {data.dropped > 0 ? <Badge tone="critical">{n(data.dropped)} lost</Badge> : <Badge tone="success">Complete</Badge>}
          </div>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
            <div><dt className="eyebrow">Instance</dt><dd className="truncate font-mono text-[12px]" title={data.instance}>{data.instance}</dd></div>
            <div><dt className="eyebrow">Schema</dt><dd className="font-mono text-[12px]">{data.schema}</dd></div>
            <div><dt className="eyebrow">Records</dt><dd>{n(data.accepted)}</dd></div>
            <div><dt className="eyebrow">Allowed reads</dt><dd>{data.reads ? "Recorded" : "Not recorded"}</dd></div>
          </dl>
          <p className="mt-4 text-[12px] text-muted-foreground">
            Each record carries a sequence number and an HMAC-SHA256 chain per instance, so changed, removed or reordered records are detected.
            The sinks are set by the environment (<code>MAYDAY_AUDIT_SINKS</code>). This page shows the instance that answered.
          </p>
        </Card>
        <Card>
          <CardHeader title="Sinks" />
          {data.sinks.length === 0 ? <Empty title="No sinks">Set MAYDAY_AUDIT_SINKS to send the stream to your SIEM.</Empty> : (
            <Table>
              <thead><tr><Th>Sink</Th><Th>Status</Th><Th className="text-right">Delivered</Th><Th className="text-right">Pending</Th><Th className="text-right">Failures</Th><Th className="text-right">Lost</Th></tr></thead>
              <tbody>
                {data.sinks.map((s) => (
                  <tr key={s.name}>
                    <Td>
                      <div>{SINK_LABEL[s.name] ?? s.name}</div>
                      {s.lastError && <div className="max-w-md truncate text-[12px] text-muted-foreground" title={s.lastError}>{s.lastError}</div>}
                    </Td>
                    <Td>{health(s)}</Td>
                    <Td className="text-right tabular-nums">{n(s.delivered)}</Td>
                    <Td className="text-right tabular-nums">{n(s.pending)}</Td>
                    <Td className="text-right tabular-nums">{n(s.failures)}</Td>
                    <Td className="text-right tabular-nums">{n(s.dropped)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
      </div>
      <Card className="p-5">
        <div className="eyebrow mb-3">What is recorded</div>
        <ul className="space-y-2.5">
          {RECORDED.map(([k, v]) => (
            <li key={k}><span className="font-mono text-[12px]">{k}</span><div className="text-[12px] text-muted-foreground">{v}</div></li>
          ))}
        </ul>
        <div className="eyebrow mb-2 mt-5">Verify an export</div>
        <pre className="overflow-x-auto rounded-lg bg-muted p-3 text-[12px]">java -jar mayday.jar audit-verify audit.jsonl</pre>
      </Card>
    </div>
  );
}
