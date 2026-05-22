import { FormEvent, useEffect, useState } from "react";
import { X } from "lucide-react";
import { AppShell, PageKey } from "./components/AppShell";
import { BlockchainEvidence } from "./pages/BlockchainEvidence";
import { Dashboard } from "./pages/Dashboard";
import { Detection } from "./pages/Detection";
import { EncryptedVault } from "./pages/EncryptedVault";
import { Explainability } from "./pages/Explainability";
import { Login } from "./pages/Login";
import { ModelAnalysis } from "./pages/ModelAnalysis";
import { AuthResponse, changePlatformPassword, clearAuthSession, loadAuthSession, persistAuthSession } from "./lib/api";

const ACTIVE_PAGE_KEY = "forensic_active_page";
const pageKeys: PageKey[] = ["dashboard", "detection", "model-analysis", "explainability", "vault", "blockchain"];

function loadActivePage(): PageKey {
  const savedPage = sessionStorage.getItem(ACTIVE_PAGE_KEY);
  return pageKeys.includes(savedPage as PageKey) ? (savedPage as PageKey) : "dashboard";
}

function persistActivePage(page: PageKey) {
  sessionStorage.setItem(ACTIVE_PAGE_KEY, page);
}

export default function App() {
  const [page, setPage] = useState<PageKey>(loadActivePage);
  const [authSession, setAuthSession] = useState<AuthResponse | null>(loadAuthSession);
  const [passwordPanelOpen, setPasswordPanelOpen] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordMessage, setPasswordMessage] = useState("");
  const [passwordError, setPasswordError] = useState("");
  const [passwordSaving, setPasswordSaving] = useState(false);

  useEffect(() => {
    persistActivePage(page);
  }, [page]);

  function login(session: AuthResponse) {
    persistAuthSession(session);
    setAuthSession(session);
  }

  function changePage(nextPage: PageKey) {
    persistActivePage(nextPage);
    setPage(nextPage);
  }

  function logout() {
    clearAuthSession();
    sessionStorage.removeItem(ACTIVE_PAGE_KEY);
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
        onPageChange={changePage}
      >
        {page === "dashboard" && <Dashboard authToken={authSession.token} onNavigate={changePage} />}
        {page === "detection" && <Detection authToken={authSession.token} onNavigate={changePage} />}
        {page === "model-analysis" && <ModelAnalysis />}
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
