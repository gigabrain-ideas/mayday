import { useState } from "react";
import { Link } from "react-router";
import { Bot, FileText, GitPullRequest, ListChecks, ScrollText, Search, Wrench } from "lucide-react";
import { ACTION_LABEL, ApiError, items, rpc, type AgentAction, type AgentConfig, type AgentRun, type Page } from "@/lib/api";
import { useLive, useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago } from "@/lib/utils";
import { Button, Card, CardHeader, Dialog, DialogContent, Empty, Field, NativeSelect, Spinner, Textarea } from "@/components/ui";
import { DictateButton, RunStatusBadge, RunTrace } from "@/components/common";
import { Markdown } from "@/lib/markdown";

export interface Subject { kind: "INCIDENT" | "ALERT"; ref: string }

export const ACTION_ICON: Record<AgentAction, typeof Bot> = {
  ANALYZE: Search, REMEDIATE: Wrench, PATCH: GitPullRequest, TIMELINE: ListChecks, POSTMORTEM: FileText, RUNBOOK: ScrollText,
};

/** Starts an agent on behalf of the signed-in person; the agent gets that person's permissions. */
export function useAgentTrigger(subject: Subject, onStarted?: () => void) {
  const toast = useToast();
  const [pending, setPending] = useState<AgentAction | null>(null);
  const [agents, setAgents] = useState<AgentConfig[]>([]);
  const [agentId, setAgentId] = useState("");
  const [prompt, setPrompt] = useState("");
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<{ text: string; warning: boolean } | null>(null);

  const open = async (action: AgentAction) => {
    setPending(action);
    setPrompt("");
    setRefusal(null);
    try {
      const list = await items(rpc.agents.listEligibleAgents<Page<AgentConfig>>({ action }));
      setAgents(list);
      setAgentId(list[0]?.id ?? "");
    } catch (e) {
      toast.error(e);
    }
  };

  const start = async () => {
    if (!pending) return;
    setBusy(true);
    setRefusal(null);
    try {
      await rpc.agentRuns.startRun({ subjectKind: subject.kind, subjectRef: subject.ref, action: pending, agentId: agentId || undefined, prompt });
      toast.show(`${ACTION_LABEL[pending]} started. Progress appears on the timeline.`, "success");
      setPending(null);
      onStarted?.();
    } catch (e) {
      if (e instanceof ApiError && e.status !== 401) setRefusal({ text: e.message, warning: e.code === "unavailable" || e.code === "resource_exhausted" });
      else toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  const dialog = (
    <Dialog open={pending !== null} onOpenChange={(o) => !o && setPending(null)}>
      {pending && (
        <DialogContent title={ACTION_LABEL[pending]} description="The agent acts on your behalf and has your permissions. Every change it makes is recorded with its name and yours.">
          {agents.length === 0 ? (
            <Empty icon={<Bot />} title="No agent can do this yet">
              Configure the LLM provider for the built-in agent, or add an external agent with this action in <Link className="underline" to="/settings/agents">Settings → Agents</Link>.
            </Empty>
          ) : (
            <div className="space-y-4">
              <Field label="Agent">
                <NativeSelect value={agentId} onChange={(e) => setAgentId(e.target.value)}>
                  {agents.map((a) => <option key={a.id} value={a.id}>{a.name}{a.kind === "INTERNAL" ? " (built-in)" : ""}</option>)}
                </NativeSelect>
              </Field>
              <Field label="Instructions (optional)" hint="For example: focus on the database, or open the pull request in repository X.">
                <div className="flex gap-2">
                  <Textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} className="min-h-20" />
                  <DictateButton onText={(t) => setPrompt((p) => (p ? p + " " : "") + t)} />
                </div>
              </Field>
              {refusal && (
                <p role="alert" className={refusal.warning ? "rounded-lg border border-warning/50 bg-warning-soft p-3" : "rounded-lg border border-destructive/40 bg-destructive-soft p-3 text-destructive"}>
                  {refusal.text}
                </p>
              )}
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setPending(null)}>Cancel</Button>
                <Button onClick={start} disabled={busy}>{busy ? <Spinner /> : <Bot />} Start</Button>
              </div>
            </div>
          )}
        </DialogContent>
      )}
    </Dialog>
  );
  return { open, dialog };
}

export function AgentActions({ subject, actions }: { subject: Subject; actions: AgentAction[] }) {
  const { canWrite } = useSession();
  const trigger = useAgentTrigger(subject);
  if (!canWrite) return null;
  return (
    <Card>
      <CardHeader title="Agents" />
      <div className="grid grid-cols-2 gap-2 p-3">
        {actions.map((a) => {
          const Icon = ACTION_ICON[a];
          return (
            <Button key={a} variant="outline" size="sm" className="min-w-0 justify-start px-3 tracking-wide" onClick={() => trigger.open(a)} title={ACTION_LABEL[a]}>
              <Icon /> <span className="truncate">{ACTION_LABEL[a]}</span>
            </Button>
          );
        })}
      </div>
      {trigger.dialog}
    </Card>
  );
}

export function AgentRuns({ subject, subjectId }: { subject: Subject; subjectId: string }) {
  const { data, reload } = useQuery(() => items(rpc.agentRuns.listRuns<Page<AgentRun>>({ subjectKind: subject.kind, subjectRef: subject.ref })), [subject.kind, subject.ref]);
  const { userName, canWrite } = useSession();
  const toast = useToast();
  useLive((t, _id, d) => t === "agent_run.updated" && d.subjectId === subjectId, reload);
  if (!data) return <div className="flex justify-center p-8"><Spinner /></div>;
  if (data.length === 0) return <Empty icon={<Bot />} title="No agent runs yet">Use the agent buttons to start an analysis, a patch or a postmortem draft.</Empty>;
  return (
    <div className="divide-y">
      {data.map((r) => {
        const Icon = ACTION_ICON[r.action];
        return (
          <details key={r.id} className="group px-4 py-3">
            <summary className="flex cursor-pointer list-none flex-wrap items-center gap-3">
              <Icon className="size-4" />
              <span className="font-medium">{ACTION_LABEL[r.action]}</span>
              <span className="text-muted-foreground">by {r.agentName}</span>
              <RunStatusBadge status={r.status} />
              <span className="ml-auto text-[12px] text-muted-foreground">
                {r.trigger === "AUTO" ? "automatic" : `for ${userName(r.onBehalfOf)}`} · {ago(r.createdAt)}
              </span>
            </summary>
            <div className="mt-3 space-y-2 pl-7">
              {r.prompt && <p className="text-muted-foreground">Instructions: {r.prompt}</p>}
              {r.output && <Markdown text={r.output} />}
              {r.error && <p className="text-destructive">{r.error}</p>}
              <RunTrace traceId={r.traceId} />
              {canWrite && !["SUCCEEDED", "FAILED", "CANCELLED"].includes(r.status) && (
                <Button size="sm" variant="ghost" onClick={() => rpc.agentRuns.cancelRun({ id: r.id }).then(reload).catch(toast.error)}>Cancel run</Button>
              )}
            </div>
          </details>
        );
      })}
    </div>
  );
}
