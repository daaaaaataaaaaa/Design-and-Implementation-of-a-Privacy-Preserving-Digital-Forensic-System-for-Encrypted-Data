import { ethers } from "ethers";
import { DatabaseZap, Play, RotateCcw, ShieldCheck } from "lucide-react";
import { FormEvent, useMemo, useState } from "react";
import { authHeader, DocumentSummary, jsonRequest, ML_API, PredictionResult, SE_API } from "../lib/api";
import type { PageKey } from "../components/AppShell";
import {
  evidenceRegistryAbi,
  getDefaultEvidenceRegistryAddress,
  rememberEvidenceRegistryAddress
} from "../lib/blockchain";

type AuthResponse = {
  token: string;
  username: string;
};

type EthereumWindow = Window & {
  ethereum?: ethers.Eip1193Provider;
};

type PreservationStage = "idle" | "saving" | "notarizing" | "done" | "error";

type DetectionProps = {
  onNavigate: (page: PageKey) => void;
};

const sampleFeatures = {
  dur: 0.121,
  spkts: 6,
  dpkts: 0,
  sbytes: 496,
  dbytes: 0,
  rate: 41.2,
  sttl: 254,
  dttl: 0,
  sload: 32768,
  dload: 0,
  ct_state_ttl: 2,
  ct_dst_src_ltm: 14,
  proto_udp: 1,
  state_INT: 1,
  service_dns: 0
};

export function Detection({ onNavigate }: DetectionProps) {
  const [featuresText, setFeaturesText] = useState(JSON.stringify(sampleFeatures, null, 2));
  const [submittedFeatures, setSubmittedFeatures] = useState<Record<string, unknown> | null>(null);
  const [result, setResult] = useState<PredictionResult | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [contractAddress, setContractAddress] = useState(getDefaultEvidenceRegistryAddress);
  const [preservationStage, setPreservationStage] = useState<PreservationStage>("idle");
  const [preservationStatus, setPreservationStatus] = useState("");
  const [vaultDocument, setVaultDocument] = useState<DocumentSummary | null>(null);
  const [chainTxHash, setChainTxHash] = useState("");

  const featureCount = useMemo(() => {
    try {
      return Object.keys(JSON.parse(featuresText)).length;
    } catch {
      return 0;
    }
  }, [featuresText]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    setLoading(true);
    try {
      const parsed = JSON.parse(featuresText);
      const response = await jsonRequest<PredictionResult>(`${ML_API}/api/ml/predict`, {
        method: "POST",
        body: JSON.stringify({ features: parsed })
      });
      setResult(response);
      setSubmittedFeatures(parsed);
      resetPreservationState();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Prediction failed");
    } finally {
      setLoading(false);
    }
  }

  function resetPreservationState() {
    setPreservationStage("idle");
    setPreservationStatus("");
    setVaultDocument(null);
    setChainTxHash("");
  }

  function updateContractAddress(address: string) {
    setContractAddress(address);
    rememberEvidenceRegistryAddress(address);
  }

  async function ensureVaultToken(): Promise<string> {
    const storedToken = localStorage.getItem("se_token") ?? "";
    if (storedToken) {
      try {
        await jsonRequest<DocumentSummary[]>(`${SE_API}/api/se/documents`, {
          headers: authHeader(storedToken)
        });
        return storedToken;
      } catch {
        localStorage.removeItem("se_token");
      }
    }

    const username = import.meta.env.VITE_SE_DEMO_USERNAME ?? "demo";
    const password = import.meta.env.VITE_SE_DEMO_PASSWORD ?? "demo123";
    let response: AuthResponse;
    try {
      response = await jsonRequest<AuthResponse>(`${SE_API}/api/se/auth/login`, {
        method: "POST",
        body: JSON.stringify({ username, password })
      });
    } catch {
      response = await jsonRequest<AuthResponse>(`${SE_API}/api/se/auth/register`, {
        method: "POST",
        body: JSON.stringify({ username, password })
      });
    }

    localStorage.setItem("se_token", response.token);
    localStorage.setItem("se_user", response.username);
    return response.token;
  }

  async function getEvidenceContract() {
    const ethereum = (window as EthereumWindow).ethereum;
    if (!ethereum) {
      throw new Error("未检测到 MetaMask 或浏览器钱包，无法执行链上存证。");
    }
    if (!contractAddress.trim()) {
      throw new Error("请先填写 EvidenceRegistry 合约地址。");
    }
    if (!ethers.isAddress(contractAddress.trim())) {
      throw new Error("EvidenceRegistry 合约地址格式不正确，应为 0x 开头的以太坊地址。");
    }
    await ethereum.request({ method: "eth_requestAccounts" });
    const provider = new ethers.BrowserProvider(ethereum);
    const signer = await provider.getSigner();
    return new ethers.Contract(contractAddress.trim(), evidenceRegistryAbi, signer);
  }

  function createEvidenceDocId(hash: string) {
    return `DET-${Date.now().toString(36).toUpperCase()}-${hash.slice(0, 8)}`;
  }

  function inferField(features: Record<string, unknown>, candidates: string[], fallback: string) {
    for (const key of candidates) {
      const value = features[key];
      if (value !== undefined && value !== null && String(value).trim()) {
        return String(value);
      }
    }
    return fallback;
  }

  function buildEvidencePayload(docId: string, features: Record<string, unknown>, prediction: PredictionResult) {
    return {
      Evidence_ID: docId,
      Timestamp: new Date().toISOString(),
      Evidence_Type: "Network intrusion detection result",
      Prediction: prediction.prediction,
      Probability: prediction.probability ?? {},
      Forensic_Metrics: {
        filled_feature_count: prediction.filled_feature_count,
        missing_feature_count: prediction.missing_feature_count
      },
      Detection_Features: features,
      Blockchain_SHA256_Hash: prediction.evidence_hash,
      Searchable_Keywords: [
        "detection",
        "intrusion",
        "prediction",
        String(prediction.prediction),
        prediction.evidence_hash.slice(0, 12)
      ]
    };
  }

  async function preserveResult() {
    if (!result || !submittedFeatures) {
      setPreservationStage("error");
      setPreservationStatus("请先运行检测，生成证据哈希后再保存。");
      return;
    }
    if (!contractAddress.trim()) {
      setPreservationStage("error");
      setPreservationStatus("请先填写 EvidenceRegistry 合约地址；只查看证据库可点击上方“加密证据库”。");
      return;
    }
    if (!ethers.isAddress(contractAddress.trim())) {
      setPreservationStage("error");
      setPreservationStatus("EvidenceRegistry 合约地址格式不正确，应为 0x 开头的以太坊地址。");
      return;
    }

    setPreservationStage("saving");
    setPreservationStatus("正在写入加密证据库...");
    setVaultDocument(null);
    setChainTxHash("");

    try {
      const docId = createEvidenceDocId(result.evidence_hash);
      const evidencePayload = buildEvidencePayload(docId, submittedFeatures, result);
      const token = await ensureVaultToken();
      const form = new FormData();
      form.set("docId", docId);
      form.set(
        "description",
        `intrusion detection ${String(result.prediction)} evidence ${result.evidence_hash}`
      );
      form.set("text", JSON.stringify(evidencePayload, null, 2));

      const savedDocument = await jsonRequest<DocumentSummary>(`${SE_API}/api/se/documents/upload`, {
        method: "POST",
        headers: authHeader(token),
        body: form
      });
      setVaultDocument(savedDocument);
      setPreservationStage("notarizing");
      setPreservationStatus(`已加密保存为 ${savedDocument.docId}，正在提交链上交易...`);

      const contract = await getEvidenceContract();
      const tx = await contract.storeJSONEvidence(
        savedDocument.docId,
        result.evidence_hash,
        "IDS Detection Result",
        `Encrypted vault record ${savedDocument.docId}; prediction ${String(result.prediction)}`,
        savedDocument.fileName,
        String(result.prediction),
        inferField(submittedFeatures, ["sourceIp", "source_ip", "src_ip", "saddr"], "N/A"),
        inferField(submittedFeatures, ["targetUrl", "target_url", "dst_ip", "daddr"], "N/A")
      );
      setChainTxHash(tx.hash);
      setPreservationStatus(`交易已提交：${tx.hash}`);
      await tx.wait();
      setPreservationStage("done");
      setPreservationStatus(`完整流程完成：${savedDocument.docId} 已加密保存并完成链上存证。`);
    } catch (err) {
      setPreservationStage("error");
      setPreservationStatus(err instanceof Error ? err.message : "保存与链上存证流程失败");
    }
  }

  const preserving = preservationStage === "saving" || preservationStage === "notarizing";

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">UNSW-NB15 model facade</p>
          <h1>入侵检测</h1>
        </div>
      </div>

      <form className="two-column align-start" onSubmit={submit}>
        <section className="panel">
          <div className="panel-heading">
            <h2>网络流量特征</h2>
            <span className="pill">{featureCount} fields</span>
          </div>
          <textarea
            className="code-input"
            value={featuresText}
            onChange={(event) => setFeaturesText(event.target.value)}
            spellCheck={false}
          />
          <div className="button-row">
            <button className="primary-action" type="submit" disabled={loading}>
              <Play size={17} /> {loading ? "检测中" : "运行检测"}
            </button>
            <button
              className="secondary-action"
              type="button"
              onClick={() => {
                setFeaturesText(JSON.stringify(sampleFeatures, null, 2));
                setSubmittedFeatures(null);
                setResult(null);
                setError("");
                resetPreservationState();
              }}
            >
              <RotateCcw size={17} /> 重置样例
            </button>
          </div>
          {error && <div className="notice danger">{error}</div>}
        </section>

        <section className="panel">
          <div className="panel-heading">
            <h2>检测结果</h2>
          </div>
          {result ? (
            <div className="result-stack">
              <div className="verdict">
                <span>Prediction</span>
                <strong>{String(result.prediction)}</strong>
              </div>
              <div className="kv-grid">
                <span>填充特征</span><strong>{result.filled_feature_count}</strong>
                <span>缺失特征</span><strong>{result.missing_feature_count}</strong>
                <span>证据哈希</span><code>{result.evidence_hash}</code>
              </div>
              {result.probability && (
                <div className="probability-list">
                  {Object.entries(result.probability).map(([label, value]) => (
                    <div key={label}>
                      <span>{label}</span>
                      <div className="bar"><i style={{ width: `${Math.round(value * 100)}%` }} /></div>
                      <strong>{(value * 100).toFixed(1)}%</strong>
                    </div>
                  ))}
                </div>
              )}
              <div className="preservation-flow">
                <div className="pipeline-steps">
                  <button
                    className={vaultDocument ? "pipeline-step done" : preservationStage === "saving" ? "pipeline-step active" : "pipeline-step"}
                    type="button"
                    onClick={() => onNavigate("vault")}
                  >
                    <DatabaseZap size={16} /> 加密证据库
                  </button>
                  <button
                    className={preservationStage === "done" ? "pipeline-step done" : preservationStage === "notarizing" ? "pipeline-step active" : "pipeline-step"}
                    type="button"
                    onClick={() => onNavigate("blockchain")}
                  >
                    <ShieldCheck size={16} /> 链上存证
                  </button>
                </div>
                <label>EvidenceRegistry 合约地址</label>
                <input
                  value={contractAddress}
                  onChange={(event) => updateContractAddress(event.target.value)}
                  placeholder="0x..."
                />
                <div className="button-row">
                  <button className="primary-action" type="button" disabled={preserving} onClick={preserveResult}>
                    <DatabaseZap size={17} /> {preserving ? "流程执行中" : "保存证据库并链上存证"}
                  </button>
                </div>
                {vaultDocument && (
                  <div className="kv-grid compact">
                    <span>证据库 ID</span><strong>{vaultDocument.docId}</strong>
                    <span>链上交易</span><code>{chainTxHash || "等待提交"}</code>
                  </div>
                )}
                {preservationStatus && (
                  <div className={preservationStage === "error" ? "notice danger" : "notice success"}>
                    {preservationStatus}
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className="empty-state">运行一次检测后，这里会显示预测类别、概率和可用于链上存证的哈希。</div>
          )}
        </section>
      </form>
    </section>
  );
}
