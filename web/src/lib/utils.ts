import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function ago(iso?: string | null, now = Date.now()): string {
  if (!iso) return "—";
  const s = Math.round((now - new Date(iso).getTime()) / 1000);
  if (s < 0) return "in the future";
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export function duration(minutes?: number | null): string {
  if (minutes == null) return "—";
  if (minutes < 1) return `${Math.round(minutes * 60)}s`;
  if (minutes < 60) return `${Math.round(minutes)}m`;
  const h = Math.floor(minutes / 60);
  const m = Math.round(minutes % 60);
  if (h < 48) return m ? `${h}h ${m}m` : `${h}h`;
  return `${Math.round(h / 24)}d`;
}

export function between(a?: string | null, b?: string | null): string {
  if (!a || !b) return "—";
  return duration((new Date(b).getTime() - new Date(a).getTime()) / 60000);
}

export function dateTime(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleString(undefined, { year: "numeric", month: "short", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

export function time(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

/** Value for <input type="datetime-local"> in local time. */
export function toLocalInput(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fromLocalInput(v: string): string | undefined {
  return v ? new Date(v).toISOString() : undefined;
}

export function titleCase(s: string) {
  return s.charAt(0) + s.slice(1).toLowerCase().replaceAll("_", " ");
}
