import { useState } from "react";
import { Link } from "react-router";
import { BadgeCheck, Ban, ChevronDown, ChevronRight, Pencil, Plus, ScrollText, TriangleAlert } from "lucide-react";
import { items, rpc, type AgentRunbook, type EffectiveSetting, type RunbookStatus, type SettingsView, type Team } from "@/lib/api";
import { RELOAD_SPREAD, useLive, useQuery } from "@/lib/hooks";
import { useSession, useTeamOwner } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { dateTime } from "@/lib/utils";
import { Markdown } from "@/lib/markdown";
import { Badge, Button, Card, CardHeader, Dialog, DialogContent, Empty, Field, Input, Spinner, Textarea } from "@/components/ui";

const STATUS: Record<RunbookStatus, { label: string; tone: "outline" | "success" | "neutral" }> = {
  DRAFT: { label: "Draft", tone: "outline" }, APPROVED: { label: "Approved for agents", tone: "success" }, REVOKED: { label: "Revoked", tone: "neutral" },
};

const matchText = (rb: AgentRunbook) => [
  rb.match.source && `source ${rb.match.source}`,
  ...Object.entries(rb.match.labels).map(([k, v]) => `${k}=${v}`),
  rb.match.fingerprint && `fingerprint ${rb.match.fingerprint}`,
].filter(Boolean).join(" · ");

/** Runbooks that a team owner approves for agentic use on one type of alert. See docs/domains/runbooks.md. */
export function TeamRunbooks({ team }: { team: Team }) {
  const isOwner = useTeamOwner()(team);
  const { canWrite, userName } = useSession();
  const toast = useToast();
  const list = useQuery(() => items(rpc.runbooks.listRunbooks<{ items: AgentRunbook[] }>({ teamId: team.id })), [team.id]);
  useLive((t) => t.startsWith("runbook."), list.reload, RELOAD_SPREAD);
  const [editing, setEditing] = useState<AgentRunbook | "new" | null>(null);
  const settings = useQuery(() => rpc.policy.getSettings<SettingsView>({ scope: "TEAM", scopeId: team.id }), [team.id]);
  useLive((t) => t === "setting.changed", settings.reload, RELOAD_SPREAD);
  const autonomy = settings.data?.effective.find((s) => s.key === AUTO_REMEDIATION);
  const showBlocked = !!autonomy && autonomy.value !== "true" && !!list.data?.some((rb) => rb.status === "APPROVED" || isOwner);
  const [open, setOpen] = useState<string | null>(null);
  const act = async (call: Promise<unknown>) => {
    try { await call; list.reload(); } catch (e) { toast.error(e); }
  };
  return (
    <Card>
      <CardHeader title="Runbooks for agents" action={canWrite && <Button size="sm" variant="outline" onClick={() => setEditing("new")}><Plus /> New runbook</Button>} />
      {showBlocked && autonomy && <AutonomyBlocked setting={autonomy} />}
      {!list.data ? <div className="flex justify-center p-6"><Spinner /></div> : list.data.length === 0 ? (
        <Empty icon={<ScrollText />} title="No runbooks">
          Write the procedure for a type of alert. When a team owner approves it, an agent follows it and closes these alerts alone.
        </Empty>
      ) : (
        <div className="divide-y">
          {list.data.map((rb) => (
            <div key={rb.id} className="space-y-2 px-4 py-3">
              <div className="flex flex-wrap items-center gap-2">
                <button className="flex items-center gap-1 font-medium" aria-expanded={open === rb.id} onClick={() => setOpen(open === rb.id ? null : rb.id)}>
                  {open === rb.id ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}{rb.title}
                </button>
                <Badge tone={STATUS[rb.status].tone}>{STATUS[rb.status].label}</Badge>
                <span className="flex-1" />
                {isOwner && rb.status !== "APPROVED" && <Button size="sm" onClick={() => act(rpc.runbooks.approveRunbook({ id: rb.id, version: rb.version }))}><BadgeCheck /> Approve</Button>}
                {isOwner && rb.status === "APPROVED" && <Button size="sm" variant="ghost" onClick={() => act(rpc.runbooks.revokeRunbook({ id: rb.id }))}><Ban /> Revoke</Button>}
                {canWrite && (rb.status !== "APPROVED" || isOwner) && <Button size="sm" variant="ghost" onClick={() => setEditing(rb)}><Pencil /> Edit</Button>}
              </div>
              <div className="text-[12px] text-muted-foreground">Alerts with {matchText(rb)}</div>
              <div className="flex flex-wrap gap-1">{rb.allowedTools.map((t) => <Badge key={t} tone="outline">{t}</Badge>)}</div>
              {rb.status === "APPROVED" && rb.approvedBy && (
                <div className="text-[11.5px] text-muted-foreground">Approved by {userName(rb.approvedBy.id)} · {dateTime(rb.approvedAt)}</div>
              )}
              {open === rb.id && <div className="rounded-xl border p-3"><Markdown text={rb.body} /></div>}
            </div>
          ))}
        </div>
      )}
      <Dialog open={!!editing} onOpenChange={(o) => !o && setEditing(null)}>
        {editing && <RunbookForm team={team} runbook={editing === "new" ? undefined : editing} onDone={() => { setEditing(null); list.reload(); }} />}
      </Dialog>
    </Card>
  );
}

function RunbookForm({ team, runbook, onDone }: { team: Team; runbook?: AgentRunbook; onDone: () => void }) {
  const toast = useToast();
  const [f, setF] = useState(() => ({
    title: runbook?.title ?? "",
    body: runbook?.body ?? "",
    source: runbook?.match.source ?? "",
    labels: Object.entries(runbook?.match.labels ?? {}).map(([k, v]) => `${k}=${v}`).join(", "),
    fingerprint: runbook?.match.fingerprint ?? "",
    tools: (runbook?.allowedTools ?? ["resolve_alert"]).join(", "),
  }));
  const save = async () => {
    const labels = Object.fromEntries(f.labels.split(",").map((s) => s.trim()).filter(Boolean).map((s) => [s.split("=")[0].trim(), s.slice(s.indexOf("=") + 1).trim()]));
    const body = {
      teamId: team.id, title: f.title, body: f.body, match: { source: f.source, labels, fingerprint: f.fingerprint },
      allowedTools: f.tools.split(",").map((s) => s.trim()).filter(Boolean),
    };
    try {
      await rpc.runbooks.saveRunbook(runbook ? { ...body, id: runbook.id, version: runbook.version } : body);
      onDone();
    } catch (e) { toast.error(e); }
  };
  return (
    <DialogContent title={runbook ? `Edit ${runbook.title}` : `New runbook for ${team.name}`}
      description="A team owner approves the runbook. Then an agent follows it on these alerts and can close them alone. A change needs a new approval.">
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); save(); }}>
        <Field label="Title"><Input value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} placeholder="Disk full on a database host" required /></Field>
        <Field label="Procedure" hint="Markdown, ASD-STE100: Symptoms, Checks, Procedure, Verification, Escalation.">
          <Textarea value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} className="min-h-40" required />
        </Field>
        <Field label="Alert source"><Input value={f.source} onChange={(e) => setF({ ...f, source: e.target.value })} placeholder="prometheus" /></Field>
        <Field label="Alert labels" hint="name=value, separated by commas. All must be equal."><Input value={f.labels} onChange={(e) => setF({ ...f, labels: e.target.value })} placeholder="service=db" /></Field>
        <Field label="Fingerprint pattern" hint="* matches any text."><Input value={f.fingerprint} onChange={(e) => setF({ ...f, fingerprint: e.target.value })} placeholder="disk-*" /></Field>
        <Field label="Allowed tools" hint="Write tools the agent can use, separated by commas. resolve_alert lets it close the alert.">
          <Input value={f.tools} onChange={(e) => setF({ ...f, tools: e.target.value })} />
        </Field>
        <div className="flex justify-end"><Button type="submit">Save draft</Button></div>
      </form>
    </DialogContent>
  );
}

const AUTO_REMEDIATION = "agents.auto_remediation";

/** Approved runbooks act only when the team allows automatic agent remediation. */
function AutonomyBlocked({ setting }: { setting: EffectiveSetting }) {
  const locked = setting.enforcement === "LOCKED" && (setting.enforcedBy === "ORG" || setting.enforcedBy === "PROJECT");
  const who = setting.enforcedBy === "PROJECT" ? "Project administrators" : "Organization administrators";
  return (
    <div role="status" className="mx-4 mb-3 flex gap-3 rounded-lg border border-warning/50 bg-warning-soft p-3 text-[13px]">
      <TriangleAlert className="mt-0.5 size-4 shrink-0" />
      <div className="space-y-2">
        <p className="font-medium">
          {locked
            ? `${who} turned off automatic agent remediation (${AUTO_REMEDIATION}) and locked it. Agents cannot follow the approved runbooks of this team alone.`
            : `Automatic agent remediation (${AUTO_REMEDIATION}) is off for this team. Agents cannot follow the approved runbooks of this team alone.`}
        </p>
        <ul className="list-disc space-y-1 pl-4">
          <li>When an alert fires that a runbook covers, Mayday starts no runbook run. Agents can only analyze the alert.</li>
          <li>When an agent resolves the alert, the alert stays in the escalation and pages until a person acknowledges it.</li>
          <li>When the built-in agent follows a runbook, Mayday refuses its next write step, and the agent raises the alert to a person.</li>
          <li>The approvals stay valid. Agents follow the runbooks again as soon as automatic remediation is on.</li>
        </ul>
        <p className="text-muted-foreground">
          {locked ? "To turn it on, ask an administrator to change it in " : "A team owner can turn it on for this team in "}
          <Link className="underline" to="/settings/policies">Settings · Policies</Link>.
        </p>
      </div>
    </div>
  );
}
