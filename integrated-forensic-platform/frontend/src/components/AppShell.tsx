import {
  BarChart3,
  Blocks,
  DatabaseZap,
  Fingerprint,
  Gauge,
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
  { key: "dashboard", label: "工作台", icon: <Gauge size={18} /> },
  { key: "detection", label: "入侵检测", icon: <Network size={18} /> },
  { key: "explainability", label: "解释性取证", icon: <BarChart3 size={18} /> },
  { key: "vault", label: "加密证据库", icon: <LockKeyhole size={18} /> },
  { key: "blockchain", label: "链上存证", icon: <Blocks size={18} /> }
];

type AppShellProps = {
  activePage: PageKey;
  onPageChange: (page: PageKey) => void;
  children: ReactNode;
};

export function AppShell({ activePage, onPageChange, children }: AppShellProps) {
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
          <DatabaseZap size={17} />
          <span>ML + SE + Chain</span>
        </div>
      </aside>

      <main className="workspace">{children}</main>
    </div>
  );
}

