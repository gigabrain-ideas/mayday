import { useEffect, useState } from "react";
import { KeyRound, LifeBuoy } from "lucide-react";
import { api, type AuthConfig } from "@/lib/api";
import { Button, Card, Field, Input } from "@/components/ui";
import { useToast } from "@/lib/toast";

export function LoginPage({ onLogin }: { onLogin: () => void }) {
  const [cfg, setCfg] = useState<AuthConfig>();
  const [email, setEmail] = useState("admin@example.com");
  const toast = useToast();
  useEffect(() => { api.get<AuthConfig>("/auth/config").then(setCfg).catch(() => setCfg({ sso: false, devLogin: false, ssoName: "" })); }, []);

  const dev = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api.post("/auth/dev-login", { email });
      onLogin();
    } catch (err) {
      toast.error(err);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm p-8 animate-in">
        <div className="mb-8 flex flex-col items-center gap-3 text-center">
          <LifeBuoy className="size-8" strokeWidth={1.75} />
          <div className="text-[13px] font-semibold uppercase tracking-[0.22em]">Mayday! Mayday!</div>
          <p className="text-muted-foreground">Incident management with agents on your side.</p>
        </div>
        {cfg?.sso && (
          <Button className="w-full" size="lg" onClick={() => (window.location.href = `/auth/login?redirect=${encodeURIComponent(window.location.pathname)}`)}>
            <KeyRound /> Sign in with {cfg.ssoName}
          </Button>
        )}
        {cfg?.devLogin && (
          <form onSubmit={dev} className={cfg.sso ? "mt-6 space-y-3 border-t pt-6" : "space-y-3"}>
            <Field label="Email (development sign-in)" hint="Demo users: admin@, ana@, sam@ and vic@example.com.">
              <Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
            </Field>
            <Button type="submit" variant={cfg.sso ? "outline" : "default"} className="w-full">Continue</Button>
          </form>
        )}
        {cfg && !cfg.sso && !cfg.devLogin && (
          <p className="text-center text-muted-foreground">No sign-in method is configured. Ask your administrator to set up single sign-on.</p>
        )}
      </Card>
    </div>
  );
}
