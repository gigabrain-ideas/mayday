import { NavLink, Navigate, Route, Routes } from "react-router";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";
import { PageHeader } from "@/components/common";
import { PoliciesPage, ChangeRequestsPage } from "./Policies";
import { DirectoryPage, UsersPage, TokensPage } from "./Directory";
import { AgentsPage, LlmPage, McpPage } from "./Agents";
import { AuditPage } from "./Audit";

export function SettingsPage() {
  const { isAdmin } = useSession();
  const tabs = [
    { to: "policies", label: "Policies" },
    { to: "requests", label: "Change requests" },
    { to: "agents", label: "Agents" },
    { to: "llm", label: "LLM provider", admin: true },
    { to: "mcp", label: "MCP" },
    { to: "directory", label: "Teams & projects" },
    { to: "users", label: "Users" },
    { to: "tokens", label: "API tokens" },
    { to: "audit", label: "Audit", admin: true },
  ].filter((t) => !t.admin || isAdmin);
  return (
    <>
      <PageHeader title="Settings" eyebrow={isAdmin ? "Administrator" : "Configuration"} />
      <nav className="mb-5 flex gap-1 overflow-x-auto border-b">
        {tabs.map((t) => (
          <NavLink key={t.to} to={`/settings/${t.to}`}
            className={({ isActive }) => cn("-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-[11.5px] font-medium uppercase tracking-wider transition-colors duration-150",
              isActive ? "border-foreground text-foreground" : "border-transparent text-muted-foreground hover:text-foreground")}>
            {t.label}
          </NavLink>
        ))}
      </nav>
      <Routes>
        <Route index element={<Navigate to="policies" replace />} />
        <Route path="policies" element={<PoliciesPage />} />
        <Route path="requests" element={<ChangeRequestsPage />} />
        <Route path="agents" element={<AgentsPage />} />
        <Route path="llm" element={<LlmPage />} />
        <Route path="mcp" element={<McpPage />} />
        <Route path="directory" element={<DirectoryPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="tokens" element={<TokensPage />} />
        <Route path="audit" element={<AuditPage />} />
      </Routes>
    </>
  );
}
