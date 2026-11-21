import { create, ScalarType, type DescEnum, type DescMessage, type DescService, type Message, type MessageInitShape, type MessageShape } from "@bufbuild/protobuf";
import type { GenService } from "@bufbuild/protobuf/codegenv2";
import { timestampDate, timestampFromDate, type Timestamp } from "@bufbuild/protobuf/wkt";
import { Code, ConnectError, createClient } from "@connectrpc/connect";
import { createGrpcWebTransport } from "@connectrpc/connect-web";
import { AgentRunService, AgentService } from "@/gen/mayday/v1/agents_pb";
import { AlertService } from "@/gen/mayday/v1/alerting_pb";
import { DirectoryService } from "@/gen/mayday/v1/directory_pb";
import { EventSchema, EventService } from "@/gen/mayday/v1/events_pb";
import { AuthService, UserService } from "@/gen/mayday/v1/identity_pb";
import { IncidentService } from "@/gen/mayday/v1/incidents_pb";
import { PolicyService } from "@/gen/mayday/v1/policy_pb";
import { ReportService } from "@/gen/mayday/v1/reporting_pb";

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
  projectAdmin: string[]; externalId?: string; disabled: boolean; createdAt: string; allowAgentAdmin?: boolean;
}
export interface Me { user: User; actorKind: string; scopes: TokenScope[]; agentReady: boolean; traceUrlTemplate?: string; agentAdminOrgAllowed?: boolean }
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
  output: string; error: string; createdAt: string; startedAt?: string; finishedAt?: string; traceId?: string;
}
export type LlmProvider = "anthropic" | "openai";
export type AuthStyle = "API_KEY" | "BEARER";
export type CredentialMode = "NONE" | "STATIC" | "OAUTH2" | "FILE";
export type GrantType = "CLIENT_CREDENTIALS" | "TOKEN_EXCHANGE";
export type ClientAuth = "BASIC" | "POST";
export interface LlmView {
  provider: LlmProvider; model: string; baseUrl: string; effort: string; enabled: boolean; hasApiKey: boolean; ready: boolean;
  authStyle?: AuthStyle; credential?: CredentialMode; tokenUrl: string; issuer: string; grant?: GrantType; clientAuth?: ClientAuth;
  clientId: string; hasClientSecret: boolean; assertionFile: string; tokenFile: string; scope: string; tokenParams: Record<string, string>;
  headers: Record<string, string>; managedFields: string[]; pausedUntil?: string; pauseReason: string;
}
/** Unset fields keep their value. An empty secret removes it. Headers and token parameters change only with their replace flag. */
export type LlmInput = Partial<Omit<LlmView, "hasApiKey" | "hasClientSecret" | "ready" | "managedFields" | "pausedUntil" | "pauseReason">> & {
  apiKey?: string; clientSecret?: string; replaceHeaders?: boolean; replaceTokenParams?: boolean;
};
export interface McpServerView {
  id: string; name: string; url: string; headerName: string; hasCredential: boolean; toolAllowlist: string[]; enabled: boolean;
  useAgentIdentity: boolean;
}
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

const transport = createGrpcWebTransport({
  baseUrl: window.location.origin,
  fetch: (input, init) => globalThis.fetch(input, { ...init, credentials: "same-origin" }),
});

const PASSTHROUGH = new Set(["google.protobuf.Struct", "google.protobuf.Value", "google.protobuf.ListValue"]);
type Elem = { enum?: DescEnum; message?: DescMessage };

function outValue(f: Elem, v: unknown): unknown {
  if (v === undefined || v === null) return undefined;
  if (f.enum) return v === 0 ? undefined : f.enum.value[v as number]?.localName ?? v;
  if (f.message) {
    if (f.message.typeName === "google.protobuf.Timestamp") return timestampDate(v as Timestamp).toISOString();
    if (PASSTHROUGH.has(f.message.typeName)) return v;
    return fromMessage(f.message, v as Message);
  }
  return typeof v === "bigint" ? Number(v) : v;
}

/** Converts a protobuf-es message into the plain JSON shape of the domain model, driven by the message descriptor. */
export function fromMessage<T = unknown>(desc: DescMessage, msg: Message): T {
  const out: Record<string, unknown> = {};
  const m = msg as unknown as Record<string, unknown>;
  for (const f of desc.fields) {
    const v = m[f.localName];
    if (f.fieldKind === "list") out[f.jsonName] = (v as unknown[]).map((x) => outValue(f, x));
    else if (f.fieldKind === "map") out[f.jsonName] = Object.fromEntries(Object.entries(v as object).map(([k, x]) => [k, outValue(f, x)]));
    else {
      const x = outValue(f, v);
      if (x !== undefined) out[f.jsonName] = x;
    }
  }
  return out as T;
}

function inValue(f: Elem & { scalar?: ScalarType }, v: unknown): unknown {
  if (f.enum) return typeof v === "number" ? v : (f.enum.values.find((e) => e.localName === v || e.name === v)?.number ?? 0);
  if (f.message) {
    if (f.message.typeName === "google.protobuf.Timestamp") return timestampFromDate(v instanceof Date ? v : new Date(v as string));
    if (PASSTHROUGH.has(f.message.typeName)) return v;
    return toInit(f.message, v as Record<string, unknown>);
  }
  switch (f.scalar) {
    case ScalarType.INT64: case ScalarType.UINT64: case ScalarType.SINT64: case ScalarType.FIXED64: case ScalarType.SFIXED64:
      return BigInt(Math.trunc(Number(v)));
    case ScalarType.STRING: return String(v);
    case ScalarType.BOOL: return Boolean(v);
    case ScalarType.BYTES: return v;
    default: return Number(v);
  }
}

function toInit(desc: DescMessage, plain: Record<string, unknown>): Record<string, unknown> {
  const init: Record<string, unknown> = {};
  for (const f of desc.fields) {
    const v = plain[f.jsonName];
    if (v === undefined || v === null || (v === "" && f.fieldKind === "message")) continue;
    if (f.fieldKind === "list") init[f.localName] = (v as unknown[]).map((x) => inValue(f, x));
    else if (f.fieldKind === "map") init[f.localName] = Object.fromEntries(Object.entries(v as object).map(([k, x]) => [k, inValue(f, x)]));
    else init[f.localName] = inValue(f, v);
  }
  return init;
}

/** Converts a plain JSON object (enum names, ISO timestamps, numbers) into a protobuf-es message. */
export function toMessage<D extends DescMessage>(desc: D, plain: object = {}): MessageShape<D> {
  return create(desc, toInit(desc, plain as Record<string, unknown>) as MessageInitShape<D>);
}

const STATUS: Partial<Record<Code, [number, string]>> = {
  [Code.Unauthenticated]: [401, "unauthenticated"],
  [Code.PermissionDenied]: [403, "forbidden"],
  [Code.NotFound]: [404, "not_found"],
  [Code.InvalidArgument]: [400, "invalid"],
  [Code.FailedPrecondition]: [400, "invalid"],
  [Code.OutOfRange]: [400, "invalid"],
  [Code.AlreadyExists]: [409, "conflict"],
  [Code.Aborted]: [409, "conflict"],
  [Code.Unavailable]: [503, "unavailable"],
  [Code.ResourceExhausted]: [429, "resource_exhausted"],
};

function toApiError(e: unknown): ApiError {
  const err = ConnectError.from(e);
  const [status, code] = STATUS[err.code] ?? [500, "error"];
  if (err.code === Code.Unauthenticated) window.dispatchEvent(new Event("mayday:unauthenticated"));
  return new ApiError(status, err.metadata.get("mayday-error-code") ?? code, err.rawMessage);
}

type Plain<T> = T extends bigint ? number | bigint
  : T extends Timestamp ? string | Date
  : T extends Message ? PlainInit<T>
  : T extends (infer E)[] ? Plain<E>[]
  : T extends number ? number | string
  : T extends Record<string, infer V> ? Record<string, Plain<V>>
  : T;
type PlainInit<M> = { [K in Exclude<keyof M, "$typeName" | "$unknown">]?: Plain<M[K]> };
type Unary<I extends DescMessage> = <T = unknown>(req?: PlainInit<MessageShape<I>>) => Promise<T>;
type Methods<S> = S extends GenService<infer R>
  ? { [K in keyof R as R[K] extends { methodKind: "unary" } ? K : never]: R[K] extends { input: infer I extends DescMessage } ? Unary<I> : never }
  : never;

function service<S extends DescService>(svc: S): Methods<S> {
  const client = createClient(svc, transport) as unknown as Record<string, (req: Message) => Promise<Message>>;
  const out: Record<string, Unary<DescMessage>> = {};
  for (const m of svc.methods) {
    if (m.methodKind !== "unary") continue;
    out[m.localName] = (req = {}) => client[m.localName](toMessage(m.input, req)).then((r) => fromMessage(m.output, r), (e) => { throw toApiError(e); }) as never;
  }
  return out as Methods<S>;
}

/** Typed gRPC-Web calls with plain request and response objects: rpc.incidents.getIncident<Incident>({ ref }). */
export const rpc = {
  auth: service(AuthService),
  users: service(UserService),
  directory: service(DirectoryService),
  incidents: service(IncidentService),
  alerts: service(AlertService),
  reports: service(ReportService),
  policy: service(PolicyService),
  agents: service(AgentService),
  agentRuns: service(AgentRunService),
};

/** Link to the trace of a run in the tracing UI, or undefined when tracing or the link template is not configured. */
export function traceUrl(template: string | undefined, traceId: string | undefined): string | undefined {
  if (!template || !traceId) return undefined;
  return template.split("{traceId}").join(encodeURIComponent(traceId));
}

export const items = <T>(p: Promise<{ items: T[] }>) => p.then((r) => r.items);

export interface LiveEvent { type: string; subjectId: string; at?: string; teamIds: string[]; data: Record<string, string> }

/** Live domain events. Pings are dropped. The iteration ends when the stream ends or the signal aborts. */
export async function* subscribeEvents(signal: AbortSignal): AsyncGenerator<LiveEvent> {
  const client = createClient(EventService, transport);
  for await (const e of client.subscribe({}, { signal })) {
    if (e.type !== "ping") yield fromMessage<LiveEvent>(EventSchema, e);
  }
}

async function http<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: "POST", credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-Requested-With": "mayday" },
    body: JSON.stringify(body ?? {}),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) throw new ApiError(res.status, data?.error ?? "error", data?.message ?? res.statusText);
  return data as T;
}

export const auth = {
  devLogin: (email: string, name = "") => http<User>("/auth/dev-login", { email, name }),
  logout: () => http<{ logoutUrl: string }>("/auth/logout"),
};
