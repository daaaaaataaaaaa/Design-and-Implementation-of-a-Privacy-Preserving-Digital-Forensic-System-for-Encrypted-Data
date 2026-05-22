import {
  BarChart3,
  Blocks,
  DatabaseZap,
  Fingerprint,
  Gauge,
  KeyRound,
  LogOut,
  LockKeyhole,
  Network
} from "lucide-react";
import type { ReactNode } from "react";

export type PageKey = "dashboard" | "detection" | "explainability" | "vault" | "blockchain";

type NavItem = {
  key: PageKey;
  label: string;
  icon: ReactNode;
};

const navItems: NavItem[] = [
  { key: "dashboard", label: "Workbench", icon: <Gauge size={18} /> },
  { key: "detection", label: "Intrusion Detection", icon: <Network size={18} /> },
  { key: "explainability", label: "Explainable Forensics", icon: <BarChart3 size={18} /> },
  { key: "vault", label: "Encrypted Evidence Vault", icon: <LockKeyhole size={18} /> },
  { key: "blockchain", label: "On-Chain Evidence", icon: <Blocks size={18} /> }
];

type AppShellProps = {
  activePage: PageKey;
  currentUser: string;
  onChangePassword: () => void;
  onLogout: () => void;
  onPageChange: (page: PageKey) => void;
  children: ReactNode;
};

export function AppShell({ activePage, currentUser, onChangePassword, onLogout, onPageChange, children }: AppShellProps) {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Fingerprint size={22} />
          </div>
          <div>
            <strong>ForensicOS</strong>
            <span>Encrypted Evidence</span>
          </div>
        </div>

        <nav className="nav-list" aria-label="Main navigation">
          {navItems.map((item) => (
            <button
              key={item.key}
              className={activePage === item.key ? "nav-item active" : "nav-item"}
              onClick={() => onPageChange(item.key)}
              title={item.label}
            >
              {item.icon}
              <span>{item.label}</span>
            </button>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="sidebar-user">
            <DatabaseZap size={17} />
            <div>
              <strong>{currentUser}</strong>
              <button className="sidebar-account-link" type="button" onClick={onChangePassword}>
                <KeyRound size={13} />
                Change password
              </button>
            </div>
          </div>
          <button className="sidebar-logout" type="button" title="Sign out" aria-label="Sign out" onClick={onLogout}>
            <LogOut size={17} />
          </button>
        </div>
      </aside>

      <main className="workspace">{children}</main>
    </div>
  );
}
