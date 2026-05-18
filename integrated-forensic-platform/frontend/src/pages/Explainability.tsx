import { ChevronLeft, ChevronRight, RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { EvidenceReport, jsonRequest, ML_API, MlMetadata } from "../lib/api";

type ImageAsset = {
  label: string;
  path: string;
};

const methodOrder = ["SHAP", "LIME", "Permutation_Importance", "PDP"];
const reportPageSize = 100;

function formatAssetLabel(folder: string, fileName: string) {
  const baseName = fileName.replace(/\.png$/i, "").replace(/_/g, " ");
  if (baseName === "Feature Importance Plot" || baseName === "RF Feature Importance") {
    return `${folder.replace("_", " ")} ${baseName}`;
  }
  return folder === "Exported_Model_Assets" ? `Model ${baseName}` : baseName;
}

function buildImageAssets(metadata: MlMetadata | null): ImageAsset[] {
  if (!metadata) {
    return [];
  }

  const folders = [...methodOrder, "Exported_Model_Assets"];
  return folders.flatMap((folder) =>
    (metadata.explainability_assets[folder] ?? []).map((fileName) => ({
      label: formatAssetLabel(folder, fileName),
      path: `/api/ml/assets/${folder}/${fileName}`
    }))
  );
}

export function Explainability() {
  const [reports, setReports] = useState<EvidenceReport[]>([]);
  const [metadata, setMetadata] = useState<MlMetadata | null>(null);
  const [method, setMethod] = useState("SHAP");
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");

  function loadReports(nextMethod = method, nextPage = page) {
    setError("");
    const offset = nextPage * reportPageSize;
    jsonRequest<EvidenceReport[]>(`${ML_API}/api/ml/reports/${nextMethod}?limit=${reportPageSize}&offset=${offset}`)
      .then(setReports)
      .catch((err: Error) => setError(err.message));
  }

  function loadMetadata() {
    jsonRequest<MlMetadata>(`${ML_API}/api/ml/metadata`)
      .then(setMetadata)
      .catch((err: Error) => setError(err.message));
  }

  function refresh() {
    loadMetadata();
    loadReports(method, page);
  }

  function selectMethod(nextMethod: string) {
    setMethod(nextMethod);
    setPage(0);
  }

  const imageAssets = buildImageAssets(metadata);
  const reportMethods = metadata ? methodOrder.filter((item) => metadata.report_counts[item] !== undefined) : methodOrder;
  const expectedReportCount = metadata?.report_counts[method];
  const pageCount = expectedReportCount ? Math.max(1, Math.ceil(expectedReportCount / reportPageSize)) : 1;
  const firstReportIndex = reports.length ? page * reportPageSize + 1 : 0;
  const lastReportIndex = page * reportPageSize + reports.length;
  const canGoPrevious = page > 0;
  const canGoNext = expectedReportCount !== undefined ? page + 1 < pageCount : reports.length === reportPageSize;

  useEffect(() => {
    loadMetadata();
  }, []);

  useEffect(() => {
    loadReports(method, page);
  }, [method, page]);

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">Explainable AI evidence</p>
          <h1>解释性取证</h1>
        </div>
        <button className="secondary-action" onClick={refresh}>
          <RefreshCw size={17} /> 刷新
        </button>
      </div>

      <div className="asset-grid">
        {imageAssets.length > 0 ? imageAssets.map(({ label, path }) => (
          <article className="image-panel" key={path}>
            <div className="panel-heading">
              <h2>{label}</h2>
            </div>
            <img src={`${ML_API}${path}`} alt={label} />
          </article>
        )) : (
          <div className="empty-state asset-grid-empty">{metadata ? "暂无解析图" : "正在加载解析图..."}</div>
        )}
      </div>

      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>取证报告 JSON</h2>
            <p className="panel-subtitle">
              当前显示 {firstReportIndex}-{lastReportIndex}
              {expectedReportCount !== undefined ? ` / ${expectedReportCount}` : ""} 条
            </p>
          </div>
          <div className="report-toolbar">
            <div className="segmented">
              {reportMethods.map((item) => (
                <button key={item} className={method === item ? "active" : ""} onClick={() => selectMethod(item)}>
                  {item.replace("_", " ")}
                </button>
              ))}
            </div>
            <div className="report-pager">
              <button className="icon-button" type="button" title="上一页" onClick={() => setPage((value) => Math.max(0, value - 1))} disabled={!canGoPrevious}>
                <ChevronLeft size={17} />
              </button>
              <span>第 {page + 1} / {pageCount} 页</span>
              <button className="icon-button" type="button" title="下一页" onClick={() => setPage((value) => value + 1)} disabled={!canGoNext}>
                <ChevronRight size={17} />
              </button>
            </div>
          </div>
        </div>
        {error && <div className="notice danger">{error}</div>}
        <div className="table-wrap report-table-wrap">
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
