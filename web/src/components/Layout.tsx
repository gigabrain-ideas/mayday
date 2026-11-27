import { useEffect, useState, type ReactNode } from "react";
import { NavLink, useNavigate } from "react-router";
import { BarChart3, Bell, CalendarClock, LifeBuoy, LogOut, Menu, Moon, Settings, Siren, Sun, X } from "lucide-react";
import { useSession } from "@/lib/session";
import { useLive } from "@/lib/hooks";
import { rpc } from "@/lib/api";
import { cn } from "@/lib/utils";

function useTheme() {
  const [dark, setDark] = useState(() => {
    try {
      const s = localStorage.getItem("mayday.theme");
      if (s) return s === "dark";
    } catch { /* storage blocked */ }
    return window.matchMedia("(prefers-color-scheme: dark)").matches;
  });
  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
    try { localStorage.setItem("mayday.theme", dark ? "dark" : "light"); } catch { /* storage blocked */ }
  }, [dark]);
  return { dark, toggle: () => setDark((d) => !d) };
}

export function Layout({ children }: { children: ReactNode }) {
  const { me, logout } = useSession();
  const theme = useTheme();
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  /** Exact number of firing alerts. Null when unknown (the server did not answer): never show a number that may be stale. */
  const [firing, setFiring] = useState<number | null>(null);
  const loadFiring = () => rpc.alerts.countAlerts<{ count: number | string }>({ status: ["FIRING"] }).then((r) => setFiring(Number(r.count)), () => setFiring(null));
  useEffect(() => { loadFiring(); }, []);
  useLive((t, _, d) => t.startsWith("alert.") && d.firing !== undefined, (e) => (e.data.firing !== undefined ? setFiring(Number(e.data.firing)) : loadFiring()));
  useLive((t) => t === "live.disconnected", (e) => e.type === "live.disconnected" && setFiring(null));

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement || e.target instanceof HTMLSelectElement) return;
      if (e.key === "g") {
        const next = (ev: KeyboardEvent) => {
          const map: Record<string, string> = { i: "/incidents", a: "/alerts", o: "/oncall", r: "/reports", s: "/settings" };
          if (map[ev.key]) nav(map[ev.key]);
          window.removeEventListener("keydown", next);
        };
        window.addEventListener("keydown", next, { once: true });
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [nav]);

  const items = [
    { to: "/incidents", label: "Incidents", icon: Siren },
    { to: "/alerts", label: "Alerts", icon: Bell, count: firing },
    { to: "/oncall", label: "On-call", icon: CalendarClock },
    { to: "/reports", label: "Reports", icon: BarChart3 },
    { to: "/settings", label: "Settings", icon: Settings },
  ];

  const sidebar = (
    <nav className="flex h-full flex-col gap-1 p-3">
      <div className="mb-4 flex items-center gap-2 px-2 pt-1">
        <LifeBuoy className="size-5" strokeWidth={2} />
        <span className="text-[13px] font-semibold uppercase tracking-[0.18em]">Mayday</span>
      </div>
      {items.map((i) => (
        <NavLink
          key={i.to}
          to={i.to}
          onClick={() => setOpen(false)}
          className={({ isActive }) =>
            cn(
              "flex h-10 items-center gap-3 rounded-full px-3 text-[12px] font-medium uppercase tracking-wider transition-colors duration-150",
              isActive ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:bg-accent hover:text-foreground",
            )
          }
        >
          <i.icon className="size-5" strokeWidth={2} />
          <span className="flex-1">{i.label}</span>
          {!!i.count && <span className="rounded-full bg-destructive px-1.5 text-[10px] font-semibold text-white" aria-label={`${i.count} firing`}>{i.count.toLocaleString()}</span>}
        </NavLink>
      ))}
      <div className="mt-auto space-y-1 border-t pt-3">
        <div className="truncate px-3 text-[12px]">{me.user.name}</div>
        <div className="truncate px-3 text-[11px] text-muted-foreground">{me.user.email} · {me.user.orgRole.toLowerCase()}</div>
        <div className="flex gap-1 pt-1">
          <button onClick={theme.toggle} className="flex h-9 flex-1 items-center justify-center rounded-full hover:bg-accent" aria-label="Toggle theme">
            {theme.dark ? <Sun className="size-4" /> : <Moon className="size-4" />}
          </button>
          <button onClick={logout} className="flex h-9 flex-1 items-center justify-center gap-2 rounded-full text-[11px] uppercase tracking-wider hover:bg-accent">
            <LogOut className="size-4" /> Sign out
          </button>
        </div>
      </div>
    </nav>
  );

  return (
    <div className="min-h-screen">
      <aside className="fixed inset-y-4 left-4 z-30 hidden w-56 rounded-2xl border bg-card/80 shadow-lg backdrop-blur md:block">{sidebar}</aside>
      <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b bg-card/80 px-4 backdrop-blur md:hidden">
        <button onClick={() => setOpen(true)} aria-label="Open menu" className="rounded-full p-2 hover:bg-accent"><Menu className="size-5" /></button>
        <span className="text-[13px] font-semibold uppercase tracking-[0.18em]">Mayday</span>
      </header>
      {open && (
        <div className="fixed inset-0 z-40 bg-background/60 backdrop-blur-sm md:hidden" onClick={() => setOpen(false)}>
          <aside className="absolute inset-y-0 left-0 w-64 border-r bg-card shadow-xl animate-in" onClick={(e) => e.stopPropagation()}>
            <button onClick={() => setOpen(false)} aria-label="Close menu" className="absolute right-3 top-3 rounded-full p-2 hover:bg-accent"><X className="size-4" /></button>
            {sidebar}
          </aside>
        </div>
      )}
      <main className="px-4 py-6 md:ml-64 md:px-8 md:py-8">
        <div className="mx-auto max-w-[1400px]">{children}</div>
      </main>
    </div>
  );
}
