import { RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { EvidenceReport, jsonRequest, ML_API } from "../lib/api";

const imageAssets = [
  ["SHAP Summary", "/api/ml/assets/SHAP/SHAP_Summary_Plot.png"],
  ["SHAP Global", "/api/ml/assets/SHAP/SHAP_Global_Importance.png"],
  ["LIME Global", "/api/ml/assets/LIME/LIME_Global_Importance.png"],
  ["Permutation Importance", "/api/ml/assets/Permutation_Importance/Permutation_Importance.png"],
  ["PDP sttl", "/api/ml/assets/PDP/PDP_sttl.png"],
  ["PDP dload", "/api/ml/assets/PDP/PDP_dload.png"]
];

export function Explainability() {
  const [reports, setReports] = useState<EvidenceReport[]>([]);
  const [method, setMethod] = useState("SHAP");
  const [error, setError] = useState("");

  function loadReports(nextMethod = method) {
    setError("");
    jsonRequest<EvidenceReport[]>(`${ML_API}/api/ml/reports/${nextMethod}?limit=8`)
      .then(setReports)
      .catch((err: Error) => setError(err.message));
  }

  useEffect(() => {
    loadReports(method);
  }, [method]);

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">Explainable AI evidence</p>
          <h1>解释性取证</h1>
        </div>
        <button className="secondary-action" onClick={() => loadReports()}>
          <RefreshCw size={17} /> 刷新
        </button>
      </div>

      <div className="asset-grid">
        {imageAssets.map(([label, path]) => (
          <article className="image-panel" key={path}>
            <div className="panel-heading">
              <h2>{label}</h2>
            </div>
            <img src={`${ML_API}${path}`} alt={label} />
          </article>
        ))}
      </div>

      <section className="panel">
        <div className="panel-heading">
          <h2>取证报告 JSON</h2>
          <div className="segmented">
            {["SHAP", "LIME", "Permutation_Importance", "PDP"].map((item) => (
              <button key={item} className={method === item ? "active" : ""} onClick={() => setMethod(item)}>
                {item.replace("_", " ")}
              </button>
            ))}
          </div>
        </div>
        {error && <div className="notice danger">{error}</div>}
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Evidence ID</th>
                <th>Timestamp</th>
                <th>Keywords</th>
                <th>Blockchain Hash</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((report, index) => (
                <tr key={`${report.Evidence_ID}-${index}`}>
                  <td>{report.Evidence_ID ?? `EVID-${index}`}</td>
                  <td>{report.Timestamp ?? "--"}</td>
                  <td>{report.Searchable_Keywords?.slice(0, 3).join(", ") ?? "--"}</td>
                  <td><code>{report.Blockchain_SHA256_Hash ?? "--"}</code></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </section>
  );
}

