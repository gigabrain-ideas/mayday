import { useState } from "react";
import { Lock, LockOpen, MessageSquarePlus, ShieldCheck } from "lucide-react";
import { api, qs, SEVERITIES, type ChangeRequest, type EffectiveSetting, type Enforcement, type Scope, type SettingDef, type SettingsView } from "@/lib/api";
import { useLive, useQuery } from "@/lib/hooks";
import { useSession } from "@/lib/session";
import { useToast } from "@/lib/toast";
import { ago, titleCase } from "@/lib/utils";
import { Badge, Button, Card, Dialog, DialogContent, Empty, Field, Input, NativeSelect, Spinner, Switch, Textarea, Tip } from "@/components/ui";

function ValueInput({ def, value, onChange, disabled }: { def: SettingDef; value: string; onChange: (v: string) => void; disabled?: boolean }) {
  if (def.type === "BOOL") return <Switch checked={value === "true"} onCheckedChange={(c) => onChange(String(c))} disabled={disabled} aria-label={def.label} />;
  if (def.type === "SEVERITY") {
    return (
      <NativeSelect value={value} onChange={(e) => onChange(e.target.value)} disabled={disabled} className="w-32" aria-label={def.label}>
        {[...SEVERITIES, "NONE"].map((s) => <option key={s}>{s}</option>)}
      </NativeSelect>
    );
  }
  return <Input value={value} onChange={(e) => onChange(e.target.value)} disabled={disabled} className="w-32" type={def.type === "INT" ? "number" : "text"} min={def.min} max={def.max} aria-label={def.label} />;
}

export function PoliciesPage() {
  const { teams, projects, isAdmin, me } = useSession();
  const toast = useToast();
  const [scope, setScope] = useState<Scope>("ORG");
  const [scopeId, setScopeId] = useState("");
  const [request, setRequest] = useState<{ def: SettingDef; value: string } | null>(null);
  const path = scope === "ORG" ? "/settings?scope=ORG" : scopeId ? `/settings${qs({ scope, scopeId })}` : null;
  const { data, reload } = useQuery<SettingsView>(path);
  useLive((t) => t === "setting.changed", reload);

  const canManage = (() => {
    if (isAdmin) return true;
    if (scope === "PROJECT") return me.user.projectAdmin.includes(scopeId);
    if (scope === "TEAM") {
      const t = teams.find((x) => x.id === scopeId);
      return me.user.teams[scopeId] === "ADMIN" || (!!t?.projectId && me.user.projectAdmin.includes(t.projectId));
    }
    return false;
  })();

  const save = async (key: string, value: string, enforcement: Enforcement) => {
    try {
      await api.put("/settings", { scope, scopeId: scope === "ORG" ? "org" : scopeId, key, value, enforcement });
      reload();
    } catch (e) {
      const def = data?.schema.find((d) => d.key === key);
      if ((e as { code?: string }).code === "locked" && def) setRequest({ def, value });
      else toast.error(e);
    }
  };
  const clear = async (key: string) => {
    try { await api.del(`/settings${qs({ scope, scopeId: scope === "ORG" ? "org" : scopeId, key })}`); reload(); } catch (e) { toast.error(e); }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex rounded-full border bg-card p-0.5">
          {(["ORG", "PROJECT", "TEAM"] as Scope[]).map((s) => (
            <button key={s} onClick={() => { setScope(s); setScopeId(""); }}
              className={`h-8 rounded-full px-4 text-[11.5px] font-medium uppercase tracking-wider ${scope === s ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"}`}>
              {s === "ORG" ? "Organization" : titleCase(s)}
            </button>
          ))}
        </div>
        {scope !== "ORG" && (
          <NativeSelect value={scopeId} onChange={(e) => setScopeId(e.target.value)} className="w-64">
            <option value="">Select a {scope.toLowerCase()}</option>
            {(scope === "TEAM" ? teams : projects).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </NativeSelect>
        )}
        <p className="w-full text-[12px] text-muted-foreground">
          Teams inherit from their project, and projects from the organization. Administrators can lock a setting or set a minimum that lower levels cannot weaken.
        </p>
      </div>
      {!path ? <Card><Empty title={`Select a ${scope.toLowerCase()}`} /></Card> : !data ? <div className="flex justify-center p-10"><Spinner /></div> : (
        <Card className="divide-y">
          {data.schema.filter((d) => d.scopes.includes(scope)).map((def) => {
            const eff = data.effective.find((e) => e.key === def.key)!;
            const own = data.raw.find((r) => r.key === def.key);
            const lockedHere = eff.enforcement !== "NONE" && eff.enforcedBy !== scope;
            return (
              <SettingRow key={def.key} def={def} eff={eff} own={own?.value} ownEnforcement={own?.enforcement ?? "NONE"} exception={own?.exception}
                canManage={canManage} isAdmin={isAdmin} lockedHere={lockedHere} scope={scope}
                onSave={(v, enf) => save(def.key, v, enf)} onClear={() => clear(def.key)} onRequest={(v) => setRequest({ def, value: v })} />
            );
          })}
        </Card>
      )}
      <RequestChange request={request} scope={scope} scopeId={scope === "ORG" ? "org" : scopeId} onClose={() => setRequest(null)} />
    </div>
  );
}

function SettingRow(props: {
  def: SettingDef; eff: EffectiveSetting; own?: string; ownEnforcement: Enforcement; exception?: boolean; canManage: boolean; isAdmin: boolean;
  lockedHere: boolean; scope: Scope; onSave: (v: string, e: Enforcement) => void; onClear: () => void; onRequest: (v: string) => void;
}) {
  const { def, eff, own, ownEnforcement, canManage, isAdmin, lockedHere } = props;
  const [value, setValue] = useState(own ?? eff.value);
  const [enf, setEnf] = useState<Enforcement>(ownEnforcement);
  const dirty = value !== (own ?? eff.value) || enf !== ownEnforcement;
  const inherited = own === undefined;
  return (
    <div className="grid gap-3 px-5 py-4 md:grid-cols-[minmax(0,1fr)_auto] md:items-center">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium">{def.label}</span>
          {lockedHere && (
            <Tip content={`Enforced at ${eff.enforcedBy?.toLowerCase()} level: ${eff.enforcement === "LOCKED" ? "locked" : `minimum ${eff.enforcedValue}`}`}>
              <Badge tone="warning"><Lock />{eff.enforcement === "LOCKED" ? "Locked" : `Min ${eff.enforcedValue}`}</Badge>
            </Tip>
          )}
          {ownEnforcement !== "NONE" && <Badge tone="solid"><ShieldCheck />{ownEnforcement === "LOCKED" ? "Locks lower levels" : "Minimum for lower levels"}</Badge>}
          {props.exception && <Badge tone="outline">Approved exception</Badge>}
          {inherited && <span className="eyebrow">inherited{eff.source ? ` from ${eff.source.toLowerCase()}` : " default"}</span>}
        </div>
        <p className="mt-1 text-[12px] text-muted-foreground">{def.description}</p>
        <code className="text-[11px] text-muted-foreground">{def.key}</code>
      </div>
      <div className="flex flex-wrap items-center gap-2 md:justify-end">
        <ValueInput def={def} value={value} onChange={setValue} disabled={!canManage && !lockedHere} />
        {isAdmin && (
          <NativeSelect value={enf} onChange={(e) => setEnf(e.target.value as Enforcement)} className="w-36" aria-label="Enforcement">
            <option value="NONE">No enforcement</option>
            {def.strictness !== "NONE" && <option value="MINIMUM">Minimum</option>}
            <option value="LOCKED">Locked</option>
          </NativeSelect>
        )}
        {canManage && !lockedHere && <Button size="sm" onClick={() => props.onSave(value, enf)} disabled={!dirty}>Save</Button>}
        {canManage && !inherited && <Tip content="Remove the value at this level and inherit again"><Button size="sm" variant="ghost" onClick={props.onClear}><LockOpen /> Reset</Button></Tip>}
        {(lockedHere && !isAdmin) || (!canManage) ? (
          <Button size="sm" variant="outline" onClick={() => props.onRequest(value)}><MessageSquarePlus /> Request change</Button>
        ) : null}
      </div>
    </div>
  );
}

function RequestChange({ request, scope, scopeId, onClose }: { request: { def: SettingDef; value: string } | null; scope: Scope; scopeId: string; onClose: () => void }) {
  const toast = useToast();
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");
  const open = request !== null;
  const def = request?.def;
  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); else { setValue(request?.value ?? ""); } }}>
      {def && (
        <DialogContent title="Request a change" description={`An administrator reviews requests for “${def.label}”. If approved, the value applies as an exception at this level.`}>
          <form className="space-y-4" onSubmit={async (e) => {
            e.preventDefault();
            try {
              await api.post("/change-requests", { scope, scopeId, key: def.key, value: value || request!.value, reason });
              toast.show("Request sent to the administrators.", "success");
              setReason(""); onClose();
            } catch (err) { toast.error(err); }
          }}>
            <Field label="Proposed value"><ValueInput def={def} value={value || request!.value} onChange={setValue} /></Field>
            <Field label="Reason"><Textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why does this team need a different value?" required /></Field>
            <div className="flex justify-end gap-2"><Button type="button" variant="ghost" onClick={onClose}>Cancel</Button><Button type="submit">Send request</Button></div>
          </form>
        </DialogContent>
      )}
    </Dialog>
  );
}

export function ChangeRequestsPage() {
  const { isAdmin, teamName, projects } = useSession();
  const toast = useToast();
  const { data, reload } = useQuery<ChangeRequest[]>("/change-requests");
  useLive((t) => t.startsWith("change_request."), reload);
  const scopeName = (c: ChangeRequest) => c.scope === "ORG" ? "Organization" : c.scope === "TEAM" ? teamName(c.scopeId) : projects.find((p) => p.id === c.scopeId)?.name ?? c.scopeId;
  const decide = async (id: string, approve: boolean) => {
    try { await api.post(`/change-requests/${id}/decision`, { approve, note: "" }); reload(); } catch (e) { toast.error(e); }
  };
  if (!data) return <div className="flex justify-center p-10"><Spinner /></div>;
  if (data.length === 0) return <Card><Empty icon={<MessageSquarePlus />} title="No change requests">When a setting is locked, people can request a change. Requests appear here.</Empty></Card>;
  return (
    <Card className="divide-y">
      {data.map((c) => (
        <div key={c.id} className="flex flex-wrap items-center gap-3 px-5 py-4">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <code>{c.key}</code> → <strong>{c.proposedValue}</strong>
              <span className="text-muted-foreground">for {scopeName(c)}</span>
              <Badge tone={c.status === "APPROVED" ? "success" : c.status === "REJECTED" ? "critical" : "warning"}>{titleCase(c.status)}</Badge>
            </div>
            <p className="mt-1 text-muted-foreground">“{c.reason}” — {c.requestedBy.name}, {ago(c.createdAt)}{c.decidedBy ? ` · decided by ${c.decidedBy.name}` : ""}</p>
          </div>
          {isAdmin && c.status === "PENDING" && (
            <div className="flex gap-2"><Button size="sm" variant="ghost" onClick={() => decide(c.id, false)}>Reject</Button><Button size="sm" onClick={() => decide(c.id, true)}>Approve</Button></div>
          )}
        </div>
      ))}
    </Card>
  );
}
