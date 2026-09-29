import { useState, type FormEvent } from "react";
import { api, ApiError } from "../api/client";
import { Logo } from "../components/Header";

export function Login({ onSignedIn }: { onSignedIn: () => void }) {
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState<"email" | "code">("email");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [devCode, setDevCode] = useState<string>();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      if (step === "email") {
        const r = await api.requestCode(email);
        setDevCode(r.devCode ?? undefined);
        if (r.devCode) setCode(r.devCode);
        setStep("code");
      } else {
        await api.verifyCode(email, code);
        onSignedIn();
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-wrap">
      <form className="card auth-card" onSubmit={submit}>
        <div className="brand"><Logo /> stonks</div>
        <h1>{step === "email" ? "Sign in or create an account" : "Check your inbox"}</h1>
        <p className="hint">
          {step === "email"
            ? "We'll email you a 6-digit code. No password needed."
            : <>We sent a code to <strong>{email}</strong>. It expires in 10 minutes.</>}
        </p>
        {step === "email" ? (
          <div className="field">
            <label htmlFor="email">Email</label>
            <input id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
          </div>
        ) : (
          <div className="field">
            <label htmlFor="code">6-digit code</label>
            <input id="code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required
              value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} autoFocus />
            {devCode && <span className="hint">Development mode: the code was filled in for you.</span>}
          </div>
        )}
        <button className="btn" disabled={busy}>{busy ? "Please wait…" : step === "email" ? "Send code" : "Sign in"}</button>
        {step === "code" && (
          <p className="hint" style={{ marginTop: 12 }}>
            <button type="button" className="btn-link" onClick={() => { setStep("email"); setCode(""); setError(undefined); }}>
              Use a different email
            </button>
          </p>
        )}
        {error && <p className="form-error" role="alert">{error}</p>}
      </form>
    </div>
  );
}
