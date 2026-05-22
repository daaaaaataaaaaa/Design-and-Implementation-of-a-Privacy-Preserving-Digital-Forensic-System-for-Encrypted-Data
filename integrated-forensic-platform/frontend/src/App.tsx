import { FormEvent, useState } from "react";
import { X } from "lucide-react";
import { AppShell, PageKey } from "./components/AppShell";
import { BlockchainEvidence } from "./pages/BlockchainEvidence";
import { Dashboard } from "./pages/Dashboard";
import { Detection } from "./pages/Detection";
import { EncryptedVault } from "./pages/EncryptedVault";
import { Explainability } from "./pages/Explainability";
import { Login } from "./pages/Login";
import { AuthResponse, changePlatformPassword, clearAuthSession, persistAuthSession } from "./lib/api";

export default function App() {
  const [page, setPage] = useState<PageKey>("dashboard");
  const [authSession, setAuthSession] = useState<AuthResponse | null>(null);
  const [passwordPanelOpen, setPasswordPanelOpen] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordMessage, setPasswordMessage] = useState("");
  const [passwordError, setPasswordError] = useState("");
  const [passwordSaving, setPasswordSaving] = useState(false);

  function login(session: AuthResponse) {
    persistAuthSession(session);
    setAuthSession(session);
  }

  function logout() {
    clearAuthSession();
    setPage("dashboard");
    setAuthSession(null);
  }

  function closePasswordPanel() {
    setPasswordPanelOpen(false);
    setCurrentPassword("");
    setNewPassword("");
    setConfirmPassword("");
    setPasswordMessage("");
    setPasswordError("");
  }

  async function submitPasswordChange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!authSession) return;
    setPasswordError("");
    setPasswordMessage("");
    if (!currentPassword || !newPassword || !confirmPassword) {
      setPasswordError("Complete all password fields.");
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError("New passwords do not match.");
      return;
    }
    if (currentPassword === newPassword) {
      setPasswordError("New password must be different from the current password.");
      return;
    }

    setPasswordSaving(true);
    try {
      const nextSession = await changePlatformPassword(authSession.token, currentPassword, newPassword);
      persistAuthSession(nextSession);
      setAuthSession(nextSession);
      setPasswordMessage("Password updated successfully.");
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
    } catch (error) {
      setPasswordError(error instanceof Error ? error.message : "Password update failed.");
    } finally {
      setPasswordSaving(false);
    }
  }

  if (!authSession) {
    return <Login onLogin={login} />;
  }

  return (
    <>
      <AppShell
        activePage={page}
        currentUser={authSession.username}
        onChangePassword={() => setPasswordPanelOpen(true)}
        onLogout={logout}
        onPageChange={setPage}
      >
        {page === "dashboard" && <Dashboard authToken={authSession.token} onNavigate={setPage} />}
        {page === "detection" && <Detection authToken={authSession.token} onNavigate={setPage} />}
        {page === "explainability" && <Explainability authToken={authSession.token} />}
        {page === "vault" && (
          <EncryptedVault
            authToken={authSession.token}
            currentUser={authSession.username}
            onSessionExpired={logout}
          />
        )}
        {page === "blockchain" && <BlockchainEvidence />}
      </AppShell>

      {passwordPanelOpen && (
        <div className="account-dialog-overlay" role="dialog" aria-modal="true" onClick={closePasswordPanel}>
          <section className="account-dialog" onClick={(event) => event.stopPropagation()}>
            <div className="account-dialog-header">
              <div>
                <p className="eyebrow">Account Security</p>
                <h2>Change Password</h2>
              </div>
              <button className="icon-button" type="button" aria-label="Close" onClick={closePasswordPanel}>
                <X size={18} />
              </button>
            </div>
            <form className="account-form" onSubmit={submitPasswordChange}>
              <label>
                Current Password
                <input
                  autoComplete="current-password"
                  type="password"
                  value={currentPassword}
                  onChange={(event) => setCurrentPassword(event.target.value)}
                />
              </label>
              <label>
                New Password
                <input
                  autoComplete="new-password"
                  type="password"
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                />
              </label>
              <label>
                Confirm New Password
                <input
                  autoComplete="new-password"
                  type="password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                />
              </label>
              {passwordError && <div className="login-error">{passwordError}</div>}
              {passwordMessage && <div className="login-success">{passwordMessage}</div>}
              <div className="account-dialog-actions">
                <button className="secondary-action" type="button" onClick={closePasswordPanel}>
                  Cancel
                </button>
                <button className="primary-action" type="submit" disabled={passwordSaving}>
                  {passwordSaving ? "Updating" : "Update Password"}
                </button>
              </div>
            </form>
          </section>
        </div>
      )}
    </>
  );
}
