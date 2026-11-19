import { StrictMode, useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import "./index.css";
import { rpc, type Me } from "@/lib/api";
import { SessionProvider } from "@/lib/session";
import { ToastProvider } from "@/lib/toast";
import { TooltipProvider, Spinner } from "@/components/ui";
import { Layout } from "@/components/Layout";
import { LoginPage } from "@/pages/Login";
import { IncidentsPage } from "@/pages/Incidents";
import { IncidentPage } from "@/pages/Incident";
import { AlertsPage } from "@/pages/Alerts";
import { AlertPage } from "@/pages/Alert";
import { ReportsPage } from "@/pages/Reports";
import { SettingsPage } from "@/pages/settings/Settings";

try {
  const t = localStorage.getItem("mayday.theme");
  if (t === "dark" || (!t && window.matchMedia("(prefers-color-scheme: dark)").matches)) document.documentElement.classList.add("dark");
} catch { /* storage blocked */ }

function Root() {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const loadMe = useCallback(() => rpc.auth.getMe<Me>().then(setMe).catch(() => setMe(null)), []);
  useEffect(() => {
    loadMe();
    const off = () => setMe(null);
    window.addEventListener("mayday:unauthenticated", off);
    return () => window.removeEventListener("mayday:unauthenticated", off);
  }, [loadMe]);

  if (me === undefined) return <div className="flex h-screen items-center justify-center"><Spinner /></div>;
  if (me === null) return <LoginPage onLogin={loadMe} />;
  return (
    <SessionProvider me={me} onLogout={() => setMe(null)} onReloadMe={loadMe}>
      <Layout>
        <Routes>
          <Route path="/" element={<Navigate to="/incidents" replace />} />
          <Route path="/incidents" element={<IncidentsPage />} />
          <Route path="/incidents/:key" element={<IncidentPage />} />
          <Route path="/alerts" element={<AlertsPage />} />
          <Route path="/alerts/:id" element={<AlertPage />} />
          <Route path="/reports" element={<ReportsPage />} />
          <Route path="/settings/*" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/incidents" replace />} />
        </Routes>
      </Layout>
    </SessionProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <TooltipProvider>
          <Root />
        </TooltipProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>,
);
