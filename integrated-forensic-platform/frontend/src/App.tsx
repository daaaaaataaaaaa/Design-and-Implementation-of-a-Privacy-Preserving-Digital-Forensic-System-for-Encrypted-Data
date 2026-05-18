import { useState } from "react";
import { AppShell, PageKey } from "./components/AppShell";
import { BlockchainEvidence } from "./pages/BlockchainEvidence";
import { Dashboard } from "./pages/Dashboard";
import { Detection } from "./pages/Detection";
import { EncryptedVault } from "./pages/EncryptedVault";
import { Explainability } from "./pages/Explainability";

export default function App() {
  const [page, setPage] = useState<PageKey>("dashboard");

  return (
    <AppShell activePage={page} onPageChange={setPage}>
      {page === "dashboard" && <Dashboard onNavigate={setPage} />}
      {page === "detection" && <Detection onNavigate={setPage} />}
      {page === "explainability" && <Explainability />}
      {page === "vault" && <EncryptedVault />}
      {page === "blockchain" && <BlockchainEvidence />}
    </AppShell>
  );
}
