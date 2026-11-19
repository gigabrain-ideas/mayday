export type Severity = "SEV1" | "SEV2" | "SEV3" | "SEV4" | "SEV5";
export const SEVERITIES: Severity[] = ["SEV1", "SEV2", "SEV3", "SEV4", "SEV5"];
export type IncidentStatus = "INVESTIGATING" | "IDENTIFIED" | "MONITORING" | "RESOLVED" | "CLOSED";
export const INCIDENT_STATUSES: IncidentStatus[] = ["INVESTIGATING", "IDENTIFIED", "MONITORING", "RESOLVED", "CLOSED"];
export const OPEN_STATUSES: IncidentStatus[] = ["INVESTIGATING", "IDENTIFIED", "MONITORING"];
export type AlertStatus = "FIRING" | "ACKNOWLEDGED" | "RESOLVED";
export type OrgRole = "VIEWER" | "MEMBER" | "ADMIN";
export type TeamRole = "MEMBER" | "ADMIN";
export type TokenScope = "READ" | "WRITE" | "ADMIN" | "ALERTS_INGEST";
export type AgentAction = "ANALYZE" | "REMEDIATE" | "PATCH" | "TIMELINE" | "POSTMORTEM" | "RUNBOOK";
export const AGENT_ACTIONS: AgentAction[] = ["ANALYZE", "REMEDIATE", "PATCH", "TIMELINE", "POSTMORTEM", "RUNBOOK"];
export const ACTION_LABEL: Record<AgentAction, string> = {
  ANALYZE: "Analyze",
  REMEDIATE: "Remediate",
  PATCH: "Create patch",
  TIMELINE: "Update timeline",
  POSTMORTEM: "Draft postmortem",
  RUNBOOK: "Generate runbook",
};
export type RunStatus = "QUEUED" | "DISPATCHED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type Scope = "ORG" | "PROJECT" | "TEAM";
export type Enforcement = "NONE" | "MINIMUM" | "LOCKED";

export interface Actor { kind: "USER" | "AGENT" | "SYSTEM"; id: string; name: string; onBehalfOf?: string }
export interface Page<T> { items: T[]; nextCursor?: string }
export interface Project { id: string; slug: string; name: string; description: string; createdAt: string }
export interface Team { id: string; slug: string; name: string; projectId?: string; description: string; createdAt: string }
export interface User {
  id: string; email: string; name: string; orgRole: OrgRole; teams: Record<string, TeamRole>;
  projectAdmin: string[]; externalId?: string; disabled: boolean; createdAt: string;
}
export interface Me { user: User; actorKind: string; scopes: TokenScope[]; agentReady: boolean }
export interface ActionItem { text: string; owner: string; done: boolean }
export interface Postmortem {
  status: "DRAFT" | "IN_REVIEW" | "PUBLISHED"; summary: string; impact: string; rootCause: string; detection: string;
  resolution: string; lessons: string; actionItems: ActionItem[]; updatedBy?: Actor; updatedAt?: string;
}
export interface Runbook { title: string; body: string; generatedBy: Actor; createdAt: string }
export interface Incident {
  id: string; number: number; key: string; title: string; description: string; severity: Severity; status: IncidentStatus;
  teamIds: string[]; commanderId?: string; impactStartedAt?: string; impactEndedAt?: string; discoveredAt?: string;
  reportedAt: string; acknowledgedAt?: string; resolvedAt?: string; postmortemRequired: boolean; postmortemDueAt?: string;
  postmortem?: Postmortem; resolutionNotes: string; runbook?: Runbook; alertIds: string[]; labels: Record<string, string>;
  createdBy: Actor; createdAt: string; updatedAt: string; version: number;
}
export interface TimelineEntry { id: string; incidentId: string; at: string; kind: string; actor: Actor; text: string; data: Record<string, string> }
export interface AlertUpdate { at: string; actor: Actor; text: string }
export interface Alert {
  id: string; fingerprint: string; source: string; title: string; description: string; severity: Severity; status: AlertStatus;
  teamId: string; labels: Record<string, string>; externalUrl?: string; incidentId?: string; firstSeenAt: string; lastSeenAt: string;
  count: number; acknowledgedBy?: Actor; resolvedAt?: string; updates: AlertUpdate[]; version: number;
}
export interface AgentTrigger { event: "ALERT_CREATED"; minSeverity: Severity; teamIds: string[]; action: AgentAction }
export interface AgentConfig {
  id: string; name: string; description: string; kind: "INTERNAL" | "EXTERNAL"; delivery: "PUSH" | "PULL"; endpointUrl?: string;
  secretSealed: string; actions: AgentAction[]; triggers: AgentTrigger[]; role: OrgRole; enabled: boolean; instructions: string;
  createdAt: string; updatedAt: string;
}
export interface ApiToken { id: string; name: string; ownerKind: string; ownerId: string; prefix: string; scopes: TokenScope[]; createdAt: string; expiresAt?: string }
export interface IssuedToken { token: ApiToken; secret: string }
export interface AgentCredentials { agent: AgentConfig; token?: IssuedToken; signingSecret?: string }
export interface AgentRun {
  id: string; agentId: string; agentName: string; action: AgentAction; subjectKind: "INCIDENT" | "ALERT"; subjectId: string;
  subjectLabel: string; status: RunStatus; trigger: "AUTO" | "MANUAL"; requestedBy: Actor; onBehalfOf?: string; prompt: string;
  output: string; error: string; createdAt: string; startedAt?: string; finishedAt?: string;
}
export interface LlmView { provider: string; model: string; baseUrl: string; effort: string; enabled: boolean; hasApiKey: boolean; ready: boolean }
export interface McpServerView { id: string; name: string; url: string; headerName: string; hasCredential: boolean; toolAllowlist: string[]; enabled: boolean }
export interface SettingDef {
  key: string; label: string; description: string; type: "BOOL" | "INT" | "SEVERITY" | "STRING"; default: string;
  scopes: Scope[]; strictness: string; min?: number; max?: number;
}
export interface EffectiveSetting { key: string; value: string; source?: Scope; enforcedBy?: Scope; enforcement: Enforcement; enforcedValue?: string }
export interface SettingValue { scope: Scope; scopeId: string; key: string; value: string; enforcement: Enforcement; exception: boolean; updatedBy: Actor; updatedAt: string }
export interface SettingsView { schema: SettingDef[]; effective: EffectiveSetting[]; raw: SettingValue[] }
export interface ChangeRequest {
  id: string; scope: Scope; scopeId: string; key: string; proposedValue: string; reason: string;
  status: "PENDING" | "APPROVED" | "REJECTED"; requestedBy: Actor; createdAt: string; decidedBy?: Actor; decidedAt?: string; decisionNote: string;
}
export interface Report {
  incidents: number; open: number; resolved: number; alerts: number; alertsWithIncident: number; bySeverity: Record<Severity, number>;
  mttaMinutes?: number; mttrMinutes?: number; mttdMinutes?: number; postmortemsRequired: number; postmortemsPublished: number;
  postmortemsOverdue: number;
  byTeam: { teamId: string; teamName: string; incidents: number; open: number; mttrMinutes?: number; alerts: number }[];
  byDay: { day: string; bySeverity: Record<Severity, number>; alerts: number }[];
  longest: { key: string; title: string; severity: Severity; status: string; durationMinutes?: number; teamIds: string[] }[];
}
export interface AuthConfig { sso: boolean; devLogin: boolean; ssoName: string }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "X-Requested-With": "mayday" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(`/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith("/auth")) window.dispatchEvent(new Event("mayday:unauthenticated"));
    throw new ApiError(res.status, data?.error ?? "error", data?.message ?? res.statusText);
  }
  return data as T;
}

export const api = {
  get: <T>(p: string) => request<T>("GET", p),
  post: <T>(p: string, b?: unknown) => request<T>("POST", p, b ?? {}),
  put: <T>(p: string, b: unknown) => request<T>("PUT", p, b),
  patch: <T>(p: string, b: unknown) => request<T>("PATCH", p, b),
  del: <T = void>(p: string) => request<T>("DELETE", p),
};

export function qs(params: Record<string, string | number | undefined | null | string[]>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v == null || v === "" || (Array.isArray(v) && v.length === 0)) continue;
    u.set(k, Array.isArray(v) ? v.join(",") : String(v));
  }
  const s = u.toString();
  return s ? `?${s}` : "";
}
