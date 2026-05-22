import { FormEvent, useMemo, useState } from "react";
import { Fingerprint, KeyRound, LogIn, ShieldCheck, UserPlus } from "lucide-react";
import {
  AuthResponse,
  changePlatformPassword,
  loginPlatform,
  registerPlatform,
  resetPlatformPassword
} from "../lib/api";

type LoginMode = "login" | "register" | "change" | "forgot";

type LoginProps = {
  onLogin: (session: AuthResponse) => void;
};

const modeCopy: Record<LoginMode, { eyebrow: string; title: string; action: string }> = {
  login: {
    eyebrow: "System Access",
    title: "Digital Forensic System Login",
    action: "Sign In"
  },
  register: {
    eyebrow: "Account Enrollment",
    title: "Create Investigator Account",
    action: "Create Account"
  },
  change: {
    eyebrow: "Credential Management",
    title: "Change Account Password",
    action: "Update Password"
  },
  forgot: {
    eyebrow: "Account Recovery",
    title: "Reset Account Password",
    action: "Reset Password"
  }
};

export function Login({ onLogin }: LoginProps) {
  const [mode, setMode] = useState<LoginMode>("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(false);

  const copy = modeCopy[mode];
  const submitIcon = useMemo(() => {
    if (mode === "register") return <UserPlus size={18} />;
    if (mode === "change" || mode === "forgot") return <KeyRound size={18} />;
    return <LogIn size={18} />;
  }, [mode]);

  function switchMode(nextMode: LoginMode) {
    setMode(nextMode);
    setPassword("");
    setNewPassword("");
    setConfirmPassword("");
    setError("");
    setNotice("");
  }

  async function submitLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setNotice("");

    const trimmedUsername = username.trim();
    if (!trimmedUsername) {
      setError("Username is required.");
      return;
    }
    if (!password) {
      setError(mode === "forgot" ? "Recovery code is required." : mode === "change" ? "Current password is required." : "Password is required.");
      return;
    }
    if ((mode === "register" || mode === "change" || mode === "forgot") && !confirmPassword) {
      setError("Confirm the password before continuing.");
      return;
    }
    if (mode === "register" && password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }
    if (mode === "change" || mode === "forgot") {
      if (!newPassword) {
        setError("New password is required.");
        return;
      }
      if (newPassword !== confirmPassword) {
        setError("New passwords do not match.");
        return;
      }
      if (mode === "change" && password === newPassword) {
        setError("New password must be different from the current password.");
        return;
      }
    }

    setLoading(true);
    try {
      if (mode === "login") {
        onLogin(await loginPlatform(trimmedUsername, password));
        return;
      }
      if (mode === "register") {
        onLogin(await registerPlatform(trimmedUsername, password));
        return;
      }
      if (mode === "forgot") {
        onLogin(await resetPlatformPassword(trimmedUsername, password, newPassword));
        return;
      }

      const loginResponse = await loginPlatform(trimmedUsername, password);
      const updatedSession = await changePlatformPassword(loginResponse.token, password, newPassword);
      setNotice("Password updated successfully.");
      onLogin(updatedSession);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Account operation failed.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-brand">
          <div className="brand-mark">
            <Fingerprint size={24} />
          </div>
          <div>
            <strong>ForensicOS</strong>
            <span>Privacy-Preserving Digital Forensics</span>
          </div>
        </div>

        <div className="login-copy">
          <p className="eyebrow">{copy.eyebrow}</p>
          <h1 id="login-title">{copy.title}</h1>
        </div>

        <form className="login-form" onSubmit={submitLogin}>
          <label>
            Username
            <input
              autoComplete="username"
              required
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
          </label>
          <label>
            {mode === "forgot" ? "Recovery Code" : mode === "change" ? "Current Password" : "Password"}
            <input
              autoComplete={mode === "forgot" ? "one-time-code" : mode === "change" ? "current-password" : mode === "register" ? "new-password" : "current-password"}
              required
              type={mode === "forgot" ? "text" : "password"}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          {mode === "login" && (
            <div className="login-forgot-row">
              <button type="button" onClick={() => switchMode("forgot")}>
                Forgot password?
              </button>
            </div>
          )}
          {(mode === "change" || mode === "forgot") && (
            <label>
              New Password
              <input
                autoComplete="new-password"
                required
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
              />
            </label>
          )}
          {(mode === "register" || mode === "change" || mode === "forgot") && (
            <label>
              Confirm {mode === "change" || mode === "forgot" ? "New Password" : "Password"}
              <input
                autoComplete="new-password"
                required
                type="password"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
              />
            </label>
          )}
          {error && <div className="login-error">{error}</div>}
          {notice && <div className="login-success">{notice}</div>}
          <button className="login-submit" type="submit" disabled={loading}>
            {submitIcon}
            {loading ? "Working..." : copy.action}
          </button>
        </form>

        <div className="login-secondary-actions" aria-label="Account options">
          {mode === "login" ? (
            <p>
              Don&apos;t have an account?
              <button type="button" onClick={() => switchMode("register")}>
                Create one
              </button>
            </p>
          ) : (
            <button type="button" onClick={() => switchMode("login")}>
              Back to sign in
            </button>
          )}
        </div>

        <div className="login-footnote">
          <ShieldCheck size={17} />
          <span>Authorized forensic personnel only</span>
        </div>
      </section>
    </main>
  );
}
