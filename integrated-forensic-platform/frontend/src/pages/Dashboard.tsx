import { ArrowRight, Blocks, LockKeyhole, Network, ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { jsonRequest, ML_API, MlMetadata } from "../lib/api";
import type { PageKey } from "../components/AppShell";
import type { ReactNode } from "react";

type DashboardProps = {
  onNavigate: (page: PageKey) => void;
};

type WorkflowStep = {
  icon: ReactNode;
  title: string;
  text: string;
  target: PageKey;
};

const steps: WorkflowStep[] = [
  { icon: <Network size={19} />, title: "Detect", text: "Score network traffic and produce evidence records.", target: "detection" },
  { icon: <ShieldCheck size={19} />, title: "Explain", text: "Attach SHAP, LIME, permutation, and PDP context.", target: "explainability" },
  { icon: <LockKeyhole size={19} />, title: "Encrypt", text: "Store evidence through searchable encryption.", target: "vault" },
  { icon: <Blocks size={19} />, title: "Notarize", text: "Commit SHA-256 fingerprints on chain.", target: "blockchain" }
];

export function Dashboard({ onNavigate }: DashboardProps) {
  const [metadata, setMetadata] = useState<MlMetadata | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    jsonRequest<MlMetadata>(`${ML_API}/api/ml/metadata`)
      .then(setMetadata)
      .catch((err: Error) => setError(err.message));
  }, []);

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">Integrated digital forensic workflow</p>
          <h1>隐私保护数字取证工作台</h1>
        </div>
        <button className="primary-action" onClick={() => onNavigate("detection")}>
          开始检测 <ArrowRight size={17} />
        </button>
      </div>

      <div className="metric-grid">
        <article className="metric">
          <span>模型状态</span>
          <strong>{metadata?.model_available ? "Ready" : "Fallback"}</strong>
        </article>
        <article className="metric">
          <span>特征数量</span>
          <strong>{metadata?.feature_count ?? "--"}</strong>
        </article>
        <article className="metric">
          <span>XAI 报告</span>
          <strong>
            {metadata ? Object.values(metadata.report_counts).reduce((sum, count) => sum + count, 0) : "--"}
          </strong>
        </article>
        <article className="metric">
          <span>链上合约</span>
          <strong>EvidenceRegistry</strong>
        </article>
      </div>

      {error && <div className="notice danger">ML 服务暂时不可达：{error}</div>}

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
            <h2>证据资产</h2>
            <button className="text-button" onClick={() => onNavigate("explainability")}>
              查看解释图
            </button>
          </div>
          <img
            className="asset-preview"
            alt="SHAP summary preview"
            src={`${ML_API}/api/ml/assets/SHAP/SHAP_Summary_Plot.png`}
          />
        </section>

        <section className="panel">
          <div className="panel-heading">
            <h2>模块连接</h2>
          </div>
          <div className="status-list">
            <div><span className="status-dot green" /> FastAPI ML service at 8001</div>
            <div><span className="status-dot blue" /> Spring Boot encrypted vault at 8082</div>
            <div><span className="status-dot amber" /> ethers.js wallet bridge for Ganache</div>
            <div><span className="status-dot red" /> MySQL backs the encrypted index</div>
          </div>
        </section>
      </div>
    </section>
  );
}
