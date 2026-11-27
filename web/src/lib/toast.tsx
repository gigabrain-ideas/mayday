import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";
import { CircleAlert, CircleCheck, Undo2 } from "lucide-react";

interface Toast { id: number; text: string; tone: "info" | "error" | "success"; undo?: () => void }
interface ToastApi {
  show(text: string, tone?: Toast["tone"]): void;
  error(e: unknown): void;
  /**
   * Run [action] after 5 s unless the person presses Undo. Replaces "are you sure?" dialogs. While an action waits, the browser asks
   * before it closes or leaves the tab, because the action runs in this tab.
   */
  undoable(text: string, action: () => Promise<unknown> | unknown): void;
}

const Ctx = createContext<ToastApi | null>(null);

let waiting = 0;
const askBeforeLeave = (e: BeforeUnloadEvent) => {
  e.preventDefault();
  e.returnValue = true;
};
function hold() {
  if (waiting++ === 0) window.addEventListener("beforeunload", askBeforeLeave);
}
function release() {
  if (--waiting === 0) window.removeEventListener("beforeunload", askBeforeLeave);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const seq = useRef(0);
  const remove = useCallback((id: number) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const push = useCallback((t: Omit<Toast, "id">, ms = 5000) => {
    const id = ++seq.current;
    setToasts((all) => [...all.slice(-3), { ...t, id }]);
    window.setTimeout(() => remove(id), ms);
    return id;
  }, [remove]);
  const apiValue: ToastApi = {
    show: (text, tone = "info") => push({ text, tone }),
    error: (e) => push({ text: e instanceof Error ? e.message : String(e), tone: "error" }, 7000),
    undoable: (text, action) => {
      let cancelled = false;
      hold();
      const id = push({ text, tone: "info", undo: () => { if (!cancelled) release(); cancelled = true; remove(id); } }, 5000);
      window.setTimeout(async () => {
        if (cancelled) return;
        cancelled = true;
        try { await action(); } catch (e) { push({ text: e instanceof Error ? e.message : String(e), tone: "error" }, 7000); } finally { release(); }
      }, 5000);
    },
  };
  return (
    <Ctx.Provider value={apiValue}>
      {children}
      <div className="pointer-events-none fixed bottom-6 right-6 z-[60] flex w-[min(420px,calc(100%-3rem))] flex-col gap-2" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className="pointer-events-auto flex items-center gap-3 rounded-xl border bg-card/95 px-4 py-3 shadow-lg backdrop-blur animate-in">
            {t.tone === "error" ? <CircleAlert className="size-4 shrink-0 text-destructive" /> : t.tone === "success" ? <CircleCheck className="size-4 shrink-0 text-success" /> : null}
            <span className="flex-1">{t.text}</span>
            {t.undo && (
              <button onClick={t.undo} className="inline-flex items-center gap-1 rounded-full px-2 py-1 text-[11px] font-semibold uppercase tracking-wider hover:bg-accent">
                <Undo2 className="size-3.5" /> Undo
              </button>
            )}
          </div>
        ))}
      </div>
    </Ctx.Provider>
  );
}

export function useToast() {
  const c = useContext(Ctx);
  if (!c) throw new Error("ToastProvider missing");
  return c;
}
