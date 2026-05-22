import { ChevronLeft, ChevronRight, FileText, Maximize2, Play, RefreshCw, X } from "lucide-react";
import { useEffect, useState } from "react";
import { EvidenceReport, getMlServiceStatus, jsonRequest, ML_API, MlMetadata, MlServiceStatus, startMlService } from "../lib/api";

type ImageAsset = {
  label: string;
  path: string;
  id: string;
};

const methodOrder = ["SHAP", "LIME", "Permutation_Importance", "PDP"];
const reportPageSize = 100;
const redundantAssetReplacements: Record<string, string[]> = {
  "SHAP/Feature_Importance_Plot.png": ["SHAP/SHAP_Global_Importance.png"],
  "Permutation_Importance/RF_Feature_Importance.png": ["SHAP/SHAP_Global_Importance.png"],
  "Exported_Model_Assets/SHAP_Bar_Plot.png": ["SHAP/SHAP_Global_Importance.png"],
  "Exported_Model_Assets/SHAP_Beeswarm_Plot.png": ["SHAP/SHAP_Summary_Plot.png"]
};

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
  const availableAssetIds = new Set(
    folders.flatMap((folder) =>
      (metadata.explainability_assets[folder] ?? []).map((fileName) => `${folder}/${fileName}`)
    )
  );

  return folders.flatMap((folder) =>
    (metadata.explainability_assets[folder] ?? [])
      .map((fileName) => ({
        id: `${folder}/${fileName}`,
        label: formatAssetLabel(folder, fileName),
        path: `/api/ml/assets/${folder}/${fileName}`
      }))
      .filter(({ id }) => {
        const replacements = redundantAssetReplacements[id] ?? [];
        return !replacements.some((replacementId) => availableAssetIds.has(replacementId));
      })
  );
}

type ExplainabilityProps = {
  authToken: string;
};

export function Explainability({ authToken }: ExplainabilityProps) {
  const [reports, setReports] = useState<EvidenceReport[]>([]);
  const [metadata, setMetadata] = useState<MlMetadata | null>(null);
  const [method, setMethod] = useState("SHAP");
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");
  const [mlStatus, setMlStatus] = useState<MlServiceStatus | null>(null);
  const [startingMl, setStartingMl] = useState(false);
  const [previewAsset, setPreviewAsset] = useState<ImageAsset | null>(null);
  const [isReportWindowOpen, setReportWindowOpen] = useState(false);

  const mlRunning = mlStatus?.running ?? false;

  function loadReports(nextMethod = method, nextPage = page) {
    if (!mlRunning) {
      setReports([]);
      return;
    }
    setError("");
    const offset = nextPage * reportPageSize;
    jsonRequest<EvidenceReport[]>(`${ML_API}/api/ml/reports/${nextMethod}?limit=${reportPageSize}&offset=${offset}`)
      .then(setReports)
      .catch((err: Error) => setError(err.message));
  }

  function loadMetadata() {
    if (!mlRunning) {
      setMetadata(null);
      return;
    }
    jsonRequest<MlMetadata>(`${ML_API}/api/ml/metadata`)
      .then(setMetadata)
      .catch((err: Error) => setError(err.message));
  }

  function refresh() {
    setError("");
    getMlServiceStatus()
      .then((status) => {
        setMlStatus(status);
        if (!status.running) {
          setMetadata(null);
          setReports([]);
          return;
        }
        jsonRequest<MlMetadata>(`${ML_API}/api/ml/metadata`)
          .then(setMetadata)
          .catch((err: Error) => setError(err.message));
        const offset = page * reportPageSize;
        jsonRequest<EvidenceReport[]>(`${ML_API}/api/ml/reports/${method}?limit=${reportPageSize}&offset=${offset}`)
          .then(setReports)
          .catch((err: Error) => setError(err.message));
      })
      .catch((err: Error) => setError(err.message));
  }

  async function startMl() {
    setError("");
    setStartingMl(true);
    try {
      const status = await startMlService(authToken);
      setMlStatus(status);
      if (status.running) {
        jsonRequest<MlMetadata>(`${ML_API}/api/ml/metadata`)
          .then(setMetadata)
          .catch((err: Error) => setError(err.message));
        jsonRequest<EvidenceReport[]>(`${ML_API}/api/ml/reports/${method}?limit=${reportPageSize}&offset=0`)
          .then(setReports)
          .catch((err: Error) => setError(err.message));
      } else {
        setError(status.message);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to start ML service");
    } finally {
      setStartingMl(false);
    }
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
    refresh();
  }, []);

  useEffect(() => {
    if (mlRunning) {
      loadReports(method, page);
    }
  }, [method, page, mlRunning]);

  useEffect(() => {
    if (!previewAsset && !isReportWindowOpen) {
      return;
    }

    function handleWindowKeydown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        if (previewAsset) {
          setPreviewAsset(null);
          return;
        }
        setReportWindowOpen(false);
      }
    }

    document.addEventListener("keydown", handleWindowKeydown);
    return () => document.removeEventListener("keydown", handleWindowKeydown);
  }, [isReportWindowOpen, previewAsset]);

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">Explainable AI evidence</p>
          <h1>Explainable Forensics</h1>
        </div>
        <div className="page-header-actions">
          {!mlRunning && (
            <button className="primary-action" type="button" onClick={startMl} disabled={startingMl}>
              <Play size={17} /> {startingMl ? "Starting ML" : "Start ML"}
            </button>
          )}
          <button className="secondary-action" type="button" onClick={() => setReportWindowOpen(true)} disabled={!mlRunning}>
            <FileText size={17} /> Report JSON
          </button>
          <button className="secondary-action" type="button" onClick={refresh}>
            <RefreshCw size={17} /> Refresh
          </button>
        </div>
      </div>

      {!mlRunning && (
        <div className="ml-gate">
          <div>
            <strong>ML functions are off</strong>
            <span>Start ML to load explanation images and forensic report JSON.</span>
          </div>
          <button className="primary-action" type="button" onClick={startMl} disabled={startingMl}>
            <Play size={17} /> {startingMl ? "Starting ML" : "Start ML"}
          </button>
        </div>
      )}

      {mlRunning && <div className="asset-grid">
        {imageAssets.length > 0 ? imageAssets.map((asset) => (
          <article className="image-panel" key={asset.path}>
            <div className="panel-heading">
              <h2>{asset.label}</h2>
            </div>
            <button
              className="image-preview-trigger"
              type="button"
              title="Preview image"
              aria-label={`Preview ${asset.label}`}
              onClick={() => setPreviewAsset(asset)}
            >
              <img src={`${ML_API}${asset.path}`} alt={asset.label} />
              <span className="image-preview-icon" aria-hidden="true">
                <Maximize2 size={18} />
              </span>
            </button>
          </article>
        )) : (
          <div className="empty-state asset-grid-empty">{metadata ? "No explanation images available" : "Loading explanation images..."}</div>
        )}
      </div>}

      {error && !isReportWindowOpen && <div className="notice danger">{error}</div>}

      {previewAsset && (
        <div className="xai-preview-overlay" role="dialog" aria-modal="true" onClick={() => setPreviewAsset(null)}>
          <section className="xai-preview-panel" onClick={(event) => event.stopPropagation()}>
            <div className="xai-preview-header">
              <h2>{previewAsset.label}</h2>
              <button
                className="icon-button"
                type="button"
                title="Close preview"
                aria-label="Close preview"
                onClick={() => setPreviewAsset(null)}
              >
                <X size={18} />
              </button>
            </div>
            <img className="xai-preview-image" src={`${ML_API}${previewAsset.path}`} alt={previewAsset.label} />
          </section>
        </div>
      )}

      {isReportWindowOpen && (
        <div className="report-window-overlay" role="dialog" aria-modal="true" onClick={() => setReportWindowOpen(false)}>
          <section className="report-window-panel" onClick={(event) => event.stopPropagation()}>
            <div className="report-window-header">
              <div>
                <h2>Forensic Report JSON</h2>
                <p className="panel-subtitle">
                  Showing {firstReportIndex}-{lastReportIndex}
                  {expectedReportCount !== undefined ? ` / ${expectedReportCount}` : ""} items
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
                  <button className="icon-button" type="button" title="Previous page" onClick={() => setPage((value) => Math.max(0, value - 1))} disabled={!canGoPrevious}>
                    <ChevronLeft size={17} />
                  </button>
                  <span>Page {page + 1} / {pageCount}</span>
                  <button className="icon-button" type="button" title="Next page" onClick={() => setPage((value) => value + 1)} disabled={!canGoNext}>
                    <ChevronRight size={17} />
                  </button>
                </div>
                <button
                  className="icon-button"
                  type="button"
                  title="Close report window"
                  aria-label="Close report window"
                  onClick={() => setReportWindowOpen(false)}
                >
                  <X size={18} />
                </button>
              </div>
            </div>
            {error && <div className="notice danger">{error}</div>}
            <div className="table-wrap report-table-wrap report-window-table">
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
        </div>
      )}
    </section>
  );
}
