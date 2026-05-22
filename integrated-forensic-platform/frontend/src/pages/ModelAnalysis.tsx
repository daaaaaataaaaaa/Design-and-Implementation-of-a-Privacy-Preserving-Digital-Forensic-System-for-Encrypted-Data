const rfKpis = [
  { label: "Accuracy", value: "86.93%", detail: "Random Forest core engine" },
  { label: "Recall / Attack Detection Rate", value: "98.72%", detail: "Attack traffic correctly identified" },
  { label: "Precision", value: "81.47%", detail: "Positive findings confirmed by class label" },
  { label: "False Positive Rate", value: "27.51%", detail: "Normal traffic misclassified as attack" }
];

const chartAssets = [
  {
    title: "Confusion Matrix",
    fileName: "Confusion_Matrix_Plot.png",
    src: "/model-analysis/Confusion_Matrix_Plot.png",
    alt: "Random Forest confusion matrix"
  },
  {
    title: "Top 10 Global Feature Importance",
    fileName: "Feature_Importance_Plot.png",
    src: "/model-analysis/Feature_Importance_Plot.png",
    alt: "Top 10 global feature importance chart"
  }
];

const modelRows = [
  {
    model: "Logistic Regression (Linear Baseline)",
    accuracy: "83.68%",
    precision: "79.12%",
    recall: "94.30%",
    f1: "86.04%"
  },
  {
    model: "Multi-Layer Perceptron (Neural Baseline)",
    accuracy: "86.18%",
    precision: "80.95%",
    recall: "97.20%",
    f1: "88.33%"
  },
  {
    model: "Random Forest Classifier (Our Core Engine)",
    accuracy: "86.93%",
    precision: "81.47%",
    recall: "98.72%",
    f1: "89.27%"
  }
];

export function ModelAnalysis() {
  return (
    <section className="page model-analysis-page">
      <div className="page-header">
        <div>
          <p className="eyebrow">Forensic engine performance</p>
          <h1>ML Model for Analysis</h1>
        </div>
      </div>

      <div className="metric-grid model-kpi-grid">
        {rfKpis.map((kpi) => (
          <article className="metric model-kpi" key={kpi.label}>
            <span>{kpi.label}</span>
            <strong>{kpi.value}</strong>
            <small>{kpi.detail}</small>
          </article>
        ))}
      </div>

      <div className="two-column analysis-chart-grid">
        {chartAssets.map((chart) => (
          <section className="image-panel model-chart-panel" key={chart.fileName}>
            <div className="panel-heading">
              <div>
                <h2>{chart.title}</h2>
                <p className="panel-subtitle">{chart.fileName}</p>
              </div>
            </div>
            <img className="model-chart-image" src={chart.src} alt={chart.alt} />
          </section>
        ))}
      </div>

      <section className="panel">
        <div className="panel-heading">
          <h2>Model Comparison</h2>
        </div>
        <div className="table-wrap">
          <table className="model-comparison-table">
            <thead>
              <tr>
                <th>Model Engine</th>
                <th>Accuracy</th>
                <th>Precision</th>
                <th>Recall (Attack Det.)</th>
                <th>F1-Score</th>
              </tr>
            </thead>
            <tbody>
              {modelRows.map((row) => (
                <tr key={row.model} className={row.model.includes("Core Engine") ? "core-engine-row" : undefined}>
                  <td>{row.model}</td>
                  <td>{row.accuracy}</td>
                  <td>{row.precision}</td>
                  <td>{row.recall}</td>
                  <td>{row.f1}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </section>
  );
}
