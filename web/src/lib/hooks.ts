import { useCallback, useEffect, useRef, useState } from "react";
import { subscribeEvents, type ApiError, type LiveEvent } from "./api";

type Listener = (e: LiveEvent) => void;
/** Random delay, in ms, for reloads that every open tab makes after one event (lists, counts). */
export const RELOAD_SPREAD = 3000;
const listeners = new Set<Listener>();
let live: AbortController | null = null;

function ensureLive() {
  if (live) return;
  const ctl = (live = new AbortController());
  (async () => {
    while (!ctl.signal.aborted) {
      try {
        for await (const e of subscribeEvents(ctl.signal)) listeners.forEach((l) => l(e));
      } catch { /* reconnect below */ }
      if (ctl.signal.aborted) break;
      const down: LiveEvent = { type: "live.disconnected", subjectId: "", teamIds: [], data: {} };
      listeners.forEach((l) => l(down));
      await new Promise((r) => setTimeout(r, 3000 + Math.random() * 7000));
    }
  })();
}

export function closeLive() {
  live?.abort();
  live = null;
}

/** Signals that events were lost (a reconnect, or the server says so): every listener reloads. */
const RESYNC = new Set(["live.connected", "events.resync"]);

/**
 * Subscribe to live domain events. The filter decides which events are relevant; "live.connected" and "events.resync" always pass,
 * with at least [RELOAD_SPREAD], because each page must reload after events were lost. Events that arrive while a call is due join that
 * call, which gets the last of them. [spreadMs] adds a random delay up to that value: one event reaches every open tab at the same
 * moment, and the spread keeps their reloads from reaching the server at the same moment.
 */
export function useLive(filter: (type: string, subjectId: string, data: Record<string, string>) => boolean, onEvent: (e: LiveEvent) => void, spreadMs = 0) {
  const ref = useRef({ filter, onEvent, spreadMs });
  ref.current = { filter, onEvent, spreadMs };
  useEffect(() => {
    ensureLive();
    let timer: number | undefined;
    let last: LiveEvent | undefined;
    const l: Listener = (e) => {
      const resync = RESYNC.has(e.type);
      if (!resync && !ref.current.filter(e.type, e.subjectId, e.data)) return;
      last = e;
      timer ??= window.setTimeout(() => {
        timer = undefined;
        ref.current.onEvent(last!);
      }, 150 + Math.random() * (resync ? Math.max(ref.current.spreadMs, RELOAD_SPREAD) : ref.current.spreadMs));
    };
    listeners.add(l);
    return () => {
      listeners.delete(l);
      window.clearTimeout(timer);
    };
  }, []);
}

/** Loads data with the loader. It reloads when a dependency changes. A null loader loads nothing. */
export function useQuery<T>(loader: (() => Promise<T>) | null, deps: unknown[] = []) {
  const [data, setData] = useState<T | undefined>();
  const [error, setError] = useState<ApiError | undefined>();
  const [loading, setLoading] = useState(true);
  const seq = useRef(0);
  const fn = useRef(loader);
  fn.current = loader;
  const enabled = loader !== null;
  const reload = useCallback(async () => {
    const load = fn.current;
    if (!load) return;
    const n = ++seq.current;
    try {
      const d = await load();
      if (n === seq.current) {
        setData(d);
        setError(undefined);
      }
    } catch (e) {
      if (n === seq.current) setError(e as ApiError);
    } finally {
      if (n === seq.current) setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, ...deps]);
  useEffect(() => {
    setLoading(true);
    reload();
  }, [reload]);
  return { data, error, loading, reload, setData };
}

type SpeechRecognitionLike = {
  continuous: boolean; interimResults: boolean; lang: string;
  onresult: ((e: { resultIndex: number; results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> }) => void) | null;
  onend: (() => void) | null; onerror: (() => void) | null; start(): void; stop(): void;
};

/** Voice dictation with the browser's Web Speech API. Returns null support when the browser has none. */
export function useDictation(onText: (text: string) => void) {
  const Ctor = (window as unknown as { SpeechRecognition?: new () => SpeechRecognitionLike; webkitSpeechRecognition?: new () => SpeechRecognitionLike });
  const Impl = Ctor.SpeechRecognition ?? Ctor.webkitSpeechRecognition;
  const [listening, setListening] = useState(false);
  const rec = useRef<SpeechRecognitionLike | null>(null);
  const cb = useRef(onText);
  cb.current = onText;
  const toggle = useCallback(() => {
    if (!Impl) return;
    if (listening) {
      rec.current?.stop();
      return;
    }
    const r = new Impl();
    r.continuous = true;
    r.interimResults = false;
    r.lang = navigator.language || "en-US";
    r.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) if (e.results[i].isFinal) cb.current(e.results[i][0].transcript.trim());
    };
    r.onend = () => setListening(false);
    r.onerror = () => setListening(false);
    rec.current = r;
    r.start();
    setListening(true);
  }, [Impl, listening]);
  useEffect(() => () => rec.current?.stop(), []);
  return { supported: !!Impl, listening, toggle };
}
