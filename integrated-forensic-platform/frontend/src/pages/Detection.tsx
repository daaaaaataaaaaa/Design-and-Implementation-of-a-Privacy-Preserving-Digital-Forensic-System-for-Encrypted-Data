import { Play, RotateCcw } from "lucide-react";
import { FormEvent, useMemo, useState } from "react";
import { jsonRequest, ML_API, PredictionResult } from "../lib/api";

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

export function Detection() {
  const [featuresText, setFeaturesText] = useState(JSON.stringify(sampleFeatures, null, 2));
  const [result, setResult] = useState<PredictionResult | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

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
    } catch (err) {
      setError(err instanceof Error ? err.message : "Prediction failed");
    } finally {
      setLoading(false);
    }
  }

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
                setResult(null);
                setError("");
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
            </div>
          ) : (
            <div className="empty-state">运行一次检测后，这里会显示预测类别、概率和可用于链上存证的哈希。</div>
          )}
        </section>
      </form>
    </section>
  );
}

