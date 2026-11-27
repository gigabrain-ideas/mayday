import { useEffect, useState, useSyncExternalStore } from "react";
import { rpc, type Page, type User } from "./api";

/**
 * Names of people, looked up on demand. An org can have far more people than one page, so the interface never loads them all:
 * a name asks the server in a batch, and pickers search the server.
 */
const known = new Map<string, User | null>();
const pending = new Set<string>();
const subscribers = new Set<() => void>();
let version = 0;
let timer: number | undefined;

function changed() {
  version++;
  subscribers.forEach((s) => s());
}

async function flush() {
  timer = undefined;
  const ids = [...pending];
  pending.clear();
  for (let i = 0; i < ids.length; i += 500) {
    const chunk = ids.slice(i, i + 500);
    try {
      const page = await rpc.users.listUsers<Page<User>>({ ids: chunk });
      chunk.forEach((id) => known.set(id, null));
      remember(page.items);
    } catch { /* asked again at the next render */ }
  }
}

/** The person, null when the server does not know the id, undefined while the lookup runs. */
export function person(id: string): User | null | undefined {
  const u = known.get(id);
  if (u === undefined && !pending.has(id)) {
    pending.add(id);
    timer ??= window.setTimeout(flush, 10);
  }
  return u;
}

export function remember(users: User[]) {
  users.forEach((u) => known.set(u.id, u));
  changed();
}

/** Drops all names, for example after a rename. The next render asks the server again. */
export function forgetPeople() {
  known.clear();
  changed();
}

export function usePeopleVersion() {
  return useSyncExternalStore((s) => { subscribers.add(s); return () => subscribers.delete(s); }, () => version);
}

/** People that match [q] (name or email, at least 2 characters), searched on the server. */
export function usePeopleSearch(q: string, limit = 50): User[] {
  const [found, setFound] = useState<User[]>([]);
  useEffect(() => {
    let live = true;
    if (q.trim().length < 2) { setFound([]); return; }
    const t = window.setTimeout(() => {
      rpc.users.listUsers<Page<User>>({ q: q.trim(), limit })
        .then((p) => { if (live) { remember(p.items); setFound(p.items.filter((u) => !u.disabled)); } })
        .catch(() => {});
    }, 200);
    return () => { live = false; window.clearTimeout(t); };
  }, [q, limit]);
  return found;
}
