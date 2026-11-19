import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "./api";

type Listener = (type: string, data: { subjectId: string; teamIds: string[]; data: Record<string, string> }) => void;
const listeners = new Set<Listener>();
let source: EventSource | null = null;

const EVENT_TYPES = [
  "incident.created", "incident.updated", "incident.timeline", "alert.created", "alert.updated",
  "agent_run.updated", "setting.changed", "change_request.created", "change_request.decided", "agent.changed", "mcp_server.changed",
];

function ensureSource() {
  if (source || typeof EventSource === "undefined") return;
  source = new EventSource("/api/v1/events");
  for (const t of EVENT_TYPES) {
    source.addEventListener(t, (e) => {
      const data = JSON.parse((e as MessageEvent).data);
      listeners.forEach((l) => l(t, data));
    });
  }
}

export function closeLive() {
  source?.close();
  source = null;
}

/** Subscribe to live domain events. The filter decides which events are relevant. */
export function useLive(filter: (type: string, subjectId: string, data: Record<string, string>) => boolean, onEvent: () => void) {
  const ref = useRef({ filter, onEvent });
  ref.current = { filter, onEvent };
  useEffect(() => {
    ensureSource();
    let timer: number | undefined;
    const l: Listener = (type, d) => {
      if (!ref.current.filter(type, d.subjectId, d.data)) return;
      window.clearTimeout(timer);
      timer = window.setTimeout(() => ref.current.onEvent(), 150);
    };
    listeners.add(l);
    return () => {
      listeners.delete(l);
      window.clearTimeout(timer);
    };
  }, []);
}

export function useQuery<T>(path: string | null, deps: unknown[] = []) {
  const [data, setData] = useState<T | undefined>();
  const [error, setError] = useState<ApiError | undefined>();
  const [loading, setLoading] = useState(true);
  const seq = useRef(0);
  const reload = useCallback(async () => {
    if (!path) return;
    const n = ++seq.current;
    try {
      const d = await api.get<T>(path);
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
  }, [path, ...deps]);
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
