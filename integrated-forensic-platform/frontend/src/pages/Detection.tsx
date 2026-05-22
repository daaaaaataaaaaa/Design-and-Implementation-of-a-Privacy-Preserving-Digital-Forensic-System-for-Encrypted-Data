import { ethers } from "ethers";
import { DatabaseZap, Play, RotateCcw, ShieldCheck } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  authHeader,
  DocumentSummary,
  getMlServiceStatus,
  jsonRequest,
  ML_API,
  MlServiceStatus,
  PredictionResult,
  SE_API,
  startMlService
} from "../lib/api";
import type { PageKey } from "../components/AppShell";
import {
  evidenceRegistryAbi,
  getDefaultEvidenceRegistryAddress,
  rememberEvidenceRegistryAddress
} from "../lib/blockchain";

type EthereumWindow = Window & {
  ethereum?: ethers.Eip1193Provider;
};

type PreservationStage = "idle" | "saving" | "notarizing" | "done" | "error";

type DetectionProps = {
  authToken: string;
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

export function Detection({ authToken, onNavigate }: DetectionProps) {
  const [featuresText, setFeaturesText] = useState(JSON.stringify(sampleFeatures, null, 2));
  const [submittedFeatures, setSubmittedFeatures] = useState<Record<string, unknown> | null>(null);
  const [result, setResult] = useState<PredictionResult | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [mlStatus, setMlStatus] = useState<MlServiceStatus | null>(null);
  const [startingMl, setStartingMl] = useState(false);
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

  const mlRunning = mlStatus?.running ?? false;

  function refreshMlStatus() {
    getMlServiceStatus()
      .then(setMlStatus)
      .catch((err: Error) => setError(err.message));
  }

  async function startMl() {
    setError("");
    setStartingMl(true);
    try {
      const status = await startMlService(authToken);
      setMlStatus(status);
      if (!status.running) {
        setError(status.message);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to start ML service");
    } finally {
      setStartingMl(false);
    }
  }

  useEffect(() => {
    refreshMlStatus();
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!mlRunning) {
      setError("Start ML before running detection.");
      return;
    }
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
    try {
      await jsonRequest<DocumentSummary[]>(`${SE_API}/api/se/documents`, {
        headers: authHeader(authToken)
      });
      return authToken;
    } catch {
      throw new Error("Your encrypted vault session expired. Sign out and sign in again before saving evidence.");
    }
  }

  async function getEvidenceContract() {
    const ethereum = (window as EthereumWindow).ethereum;
    if (!ethereum) {
      throw new Error("MetaMask or a browser wallet was not detected, so on-chain evidence anchoring cannot run.");
    }
    if (!contractAddress.trim()) {
      throw new Error("Enter the EvidenceRegistry contract address first.");
    }
    if (!ethers.isAddress(contractAddress.trim())) {
      throw new Error("The EvidenceRegistry contract address is invalid. It must be an Ethereum address starting with 0x.");
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
      setPreservationStatus("Run detection first to generate an evidence hash before saving.");
      return;
    }
    if (!contractAddress.trim()) {
      setPreservationStage("error");
      setPreservationStatus("Enter the EvidenceRegistry contract address first. To only view the vault, click Encrypted Evidence Vault above.");
      return;
    }
    if (!ethers.isAddress(contractAddress.trim())) {
      setPreservationStage("error");
      setPreservationStatus("The EvidenceRegistry contract address is invalid. It must be an Ethereum address starting with 0x.");
      return;
    }

    setPreservationStage("saving");
    setPreservationStatus("Writing to the encrypted evidence vault...");
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
      setPreservationStatus(`Encrypted and saved as ${savedDocument.docId}. Submitting the on-chain transaction...`);

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
      setPreservationStatus(`Transaction submitted: ${tx.hash}`);
      await tx.wait();
      setPreservationStage("done");
      setPreservationStatus(`Workflow completed: ${savedDocument.docId} was encrypted, saved, and anchored on-chain.`);
    } catch (err) {
      setPreservationStage("error");
      setPreservationStatus(err instanceof Error ? err.message : "Save and on-chain evidence anchoring workflow failed");
    }
  }

  const preserving = preservationStage === "saving" || preservationStage === "notarizing";

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">UNSW-NB15 model facade</p>
          <h1>Intrusion Detection</h1>
        </div>
        {!mlRunning && (
          <button className="primary-action" type="button" onClick={startMl} disabled={startingMl}>
            <Play size={17} /> {startingMl ? "Starting ML" : "Start ML"}
          </button>
        )}
      </div>

      {!mlRunning && (
        <div className="ml-gate">
          <div>
            <strong>ML functions are off</strong>
            <span>Start ML to enable network traffic prediction.</span>
          </div>
          <button className="primary-action" type="button" onClick={startMl} disabled={startingMl}>
            <Play size={17} /> {startingMl ? "Starting ML" : "Start ML"}
          </button>
        </div>
      )}

      <form className="two-column align-start" onSubmit={submit}>
        <section className="panel">
          <div className="panel-heading">
            <h2>Network Traffic Features</h2>
            <span className="pill">{featureCount} fields</span>
          </div>
          <textarea
            className="code-input"
            value={featuresText}
            onChange={(event) => setFeaturesText(event.target.value)}
            spellCheck={false}
          />
          <div className="button-row">
            <button className="primary-action" type="submit" disabled={loading || !mlRunning}>
              <Play size={17} /> {loading ? "Detecting" : "Run Detection"}
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
              <RotateCcw size={17} /> Reset Sample
            </button>
          </div>
          {error && <div className="notice danger">{error}</div>}
        </section>

        <section className="panel">
          <div className="panel-heading">
            <h2>Detection Results</h2>
          </div>
          {result ? (
            <div className="result-stack">
              <div className="verdict">
                <span>Prediction</span>
                <strong>{String(result.prediction)}</strong>
              </div>
              <div className="kv-grid">
                <span>Filled Features</span><strong>{result.filled_feature_count}</strong>
                <span>Missing Features</span><strong>{result.missing_feature_count}</strong>
                <span>Evidence Hash</span><code>{result.evidence_hash}</code>
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
                    <DatabaseZap size={16} /> Encrypted Evidence Vault
                  </button>
                  <button
                    className={preservationStage === "done" ? "pipeline-step done" : preservationStage === "notarizing" ? "pipeline-step active" : "pipeline-step"}
                    type="button"
                    onClick={() => onNavigate("blockchain")}
                  >
                    <ShieldCheck size={16} /> On-Chain Evidence
                  </button>
                </div>
                <label>EvidenceRegistry Contract Address</label>
                <input
                  value={contractAddress}
                  onChange={(event) => updateContractAddress(event.target.value)}
                  placeholder="0x..."
                />
                <div className="button-row">
                  <button className="primary-action" type="button" disabled={preserving} onClick={preserveResult}>
                  <DatabaseZap size={17} /> {preserving ? "Workflow Running" : "Save to Vault and Anchor On-Chain"}
                  </button>
                </div>
                {vaultDocument && (
                  <div className="kv-grid compact">
                  <span>Vault ID</span><strong>{vaultDocument.docId}</strong>
                  <span>On-Chain Transaction</span><code>{chainTxHash || "Pending submission"}</code>
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
          <div className="empty-state">Run detection once to show the predicted class, probability, and hash for on-chain evidence anchoring.</div>
          )}
        </section>
      </form>
    </section>
  );
}
