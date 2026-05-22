import { ArrowRight, Blocks, LockKeyhole, Network, Play, RefreshCw, ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { getMlServiceStatus, jsonRequest, ML_API, MlMetadata, MlServiceStatus, startMlService } from "../lib/api";
import type { PageKey } from "../components/AppShell";
import type { ReactNode } from "react";

type DashboardProps = {
  authToken: string;
  onNavigate: (page: PageKey) => void;
};

type WorkflowStep = {
  icon: ReactNode;
  title: string;
  text: string;
  target: PageKey;
};

type MlCheckFeedback = {
  kind: "success" | "danger";
  message: string;
} | null;

const steps: WorkflowStep[] = [
  { icon: <Network size={19} />, title: "Detect", text: "Score network traffic and produce evidence records.", target: "detection" },
  { icon: <ShieldCheck size={19} />, title: "Explain", text: "Attach SHAP, LIME, permutation, and PDP context.", target: "explainability" },
  { icon: <LockKeyhole size={19} />, title: "Encrypt", text: "Store evidence through searchable encryption.", target: "vault" },
  { icon: <Blocks size={19} />, title: "Notarize", text: "Commit SHA-256 fingerprints on chain.", target: "blockchain" }
];

function formatCheckTime(date: Date) {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(date);
}

export function Dashboard({ authToken, onNavigate }: DashboardProps) {
  const [metadata, setMetadata] = useState<MlMetadata | null>(null);
  const [mlStatus, setMlStatus] = useState<MlServiceStatus | null>(null);
  const [error, setError] = useState("");
  const [startingMl, setStartingMl] = useState(false);
  const [checkingMl, setCheckingMl] = useState(false);
  const [mlCheckFeedback, setMlCheckFeedback] = useState<MlCheckFeedback>(null);

  const mlRunning = mlStatus?.running ?? false;

  function loadMetadata() {
    return jsonRequest<MlMetadata>(`${ML_API}/api/ml/metadata`)
      .then((nextMetadata) => {
        setMetadata(nextMetadata);
        return nextMetadata;
      });
  }

  async function refreshMlStatus(showFeedback = true) {
    setError("");
    if (showFeedback) {
      setMlCheckFeedback(null);
    }
    setCheckingMl(true);

    try {
      const status = await getMlServiceStatus();
      let nextMetadata: MlMetadata | null = null;

      setMlStatus(status);
      if (status.running) {
        nextMetadata = await loadMetadata();
      } else {
        setMetadata(null);
      }

      if (showFeedback) {
        const checkedAt = formatCheckTime(new Date());
        const modelStatus = nextMetadata?.model_available ? "model ready" : "fallback mode";
        const featureCount = nextMetadata?.feature_count ?? 0;
        setMlCheckFeedback({
          kind: status.running ? "success" : "danger",
          message: status.running
            ? `ML checked at ${checkedAt}: ${modelStatus}, ${featureCount} features loaded.`
            : `ML checked at ${checkedAt}: ${status.message}`
        });
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : "Failed to check ML service";
      setMlStatus({ running: false, status: "error", apiUrl: ML_API, message });
      setMetadata(null);
      if (showFeedback) {
        setMlCheckFeedback({ kind: "danger", message: `ML check failed: ${message}` });
      } else {
        setError(message);
      }
    } finally {
      setCheckingMl(false);
    }
  }

  async function startMl() {
    setError("");
    setStartingMl(true);
    try {
      const status = await startMlService(authToken);
      setMlStatus(status);
      if (status.running) {
        await loadMetadata();
      } else {
        setMetadata(null);
        setError(status.message);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to start ML service");
    } finally {
      setStartingMl(false);
    }
  }

  useEffect(() => {
    refreshMlStatus(false);
  }, []);

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">Integrated digital forensic workflow</p>
          <h1>Privacy-Preserving Digital Forensic Workbench</h1>
        </div>
        <div className="page-header-actions">
          <button className="secondary-action" type="button" onClick={() => refreshMlStatus()} disabled={checkingMl}>
            <RefreshCw className={checkingMl ? "spin" : undefined} size={17} /> {checkingMl ? "Checking ML" : "Check ML"}
          </button>
          {!mlRunning && (
            <button className="primary-action" type="button" onClick={startMl} disabled={startingMl}>
              <Play size={17} /> {startingMl ? "Starting ML" : "Start ML"}
            </button>
          )}
          <button className="primary-action" onClick={() => onNavigate("detection")}>
            Start Detection <ArrowRight size={17} />
          </button>
        </div>
      </div>

      <div className="metric-grid">
        <article className="metric">
          <span>Model Status</span>
          <strong>{mlRunning ? metadata ? metadata.model_available ? "Ready" : "Fallback" : "Loading" : "Offline"}</strong>
        </article>
        <article className="metric">
          <span>Feature Count</span>
          <strong>{metadata?.feature_count ?? "--"}</strong>
        </article>
        <article className="metric">
          <span>XAI Reports</span>
          <strong>
            {metadata ? Object.values(metadata.report_counts).reduce((sum, count) => sum + count, 0) : "--"}
          </strong>
        </article>
        <article className="metric">
          <span>On-Chain Contract</span>
          <strong>EvidenceRegistry</strong>
        </article>
      </div>

      {!mlRunning && (
        <div className="ml-gate">
          <div>
            <strong>ML functions are off</strong>
            <span>Start the ML service after login to enable detection, reports, and explanation assets.</span>
          </div>
          <button className="primary-action" type="button" onClick={startMl} disabled={startingMl}>
            <Play size={17} /> {startingMl ? "Starting ML" : "Start ML"}
          </button>
        </div>
      )}

      {mlCheckFeedback && <div className={`notice ${mlCheckFeedback.kind}`} aria-live="polite">{mlCheckFeedback.message}</div>}
      {error && <div className="notice danger">ML service status: {error}</div>}

      <div className="workflow-band">
        {steps.map((step, index) => (
          <button className="workflow-step" type="button" key={step.title} onClick={() => onNavigate(step.target)}>
            <div className="workflow-icon">{step.icon}</div>
            <div>
              <strong>{step.title}</strong>
              <p>{step.text}</p>
            </div>
            {index < steps.length - 1 && <ArrowRight className="step-arrow" size={18} />}
          </button>
        ))}
      </div>

      <div className="two-column">
        <section className="panel">
          <div className="panel-heading">
            <h2>Evidence Assets</h2>
            <button className="text-button" onClick={() => onNavigate("explainability")} disabled={!mlRunning}>
              View Explanation Images
            </button>
          </div>
          {mlRunning ? (
            <img
              className="asset-preview"
              alt="SHAP summary preview"
              src={`${ML_API}/api/ml/assets/SHAP/SHAP_Summary_Plot.png`}
            />
          ) : (
            <div className="empty-state asset-preview-placeholder">ML service is stopped.</div>
          )}
        </section>

        <section className="panel">
          <div className="panel-heading">
            <h2>Module Connections</h2>
          </div>
          <div className="status-list">
            <div><span className={mlRunning ? "status-dot green" : "status-dot amber"} /> FastAPI ML service at 8001</div>
            <div><span className="status-dot blue" /> Spring Boot encrypted vault at 8082</div>
            <div><span className="status-dot amber" /> ethers.js wallet bridge for Ganache</div>
            <div><span className="status-dot red" /> MySQL backs the encrypted index</div>
          </div>
        </section>
      </div>
    </section>
  );
}
