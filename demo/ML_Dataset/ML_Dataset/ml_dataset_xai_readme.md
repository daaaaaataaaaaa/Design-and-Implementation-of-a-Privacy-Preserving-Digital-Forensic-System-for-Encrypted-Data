# Network Intrusion Detection with Explainable AI (XAI)

## Project Overview

This project focuses on building an interpretable Network Intrusion Detection System (NIDS) using the **UNSW-NB15** dataset and machine learning techniques.

The main goal is not only to detect malicious network traffic accurately, but also to explain how the model makes decisions through multiple Explainable AI (XAI) methods.

The project includes:

- Random Forest based intrusion detection
- Feature scaling and preprocessing
- Model export and forensic evidence generation
- Explainability analysis using:
  - SHAP (SHapley Additive exPlanations)
  - LIME (Local Interpretable Model-agnostic Explanations)
  - Permutation Importance
  - PDP (Partial Dependence Plot)

---

# Project Structure

```text
ML_Dataset/
│
├── 1.ipynb                                # Main experiment notebook
│
├── Exported_Model_Assets/
│   ├── Feature_Names.json
│   ├── Forensic_RandomForest_Engine.joblib
│   ├── Forensic_StandardScaler.joblib
│   ├── SHAP_Bar_Plot.png
│   └── SHAP_Beeswarm_Plot.png
│
├── SHAP/
│   ├── Feature_Importance_Plot.png
│   ├── SHAP_Attack_Sample_Explanation.png
│   ├── SHAP_Global_Importance.png
│   ├── SHAP_Summary_Plot.png
│   └── Forensic_Evidence_Report.json
│
├── LIME/
│   ├── Feature_Importance_Plot.png
│   ├── LIME_Attack_Sample_Explanation.png
│   ├── LIME_Global_Importance.png
│   └── Forensic_Evidence_Report.json
│
├── Permutation_Importance/
│   ├── Permutation_Importance.png
│   ├── RF_Feature_Importance.png
│   └── Forensic_Evidence_Report.json
│
├── PDP/
│   ├── PDP_ct_state_ttl.png
│   ├── PDP_dload.png
│   ├── PDP_sttl.png
│   ├── RF_Feature_Importance.png
│   └── Forensic_Evidence_Report.json
│
└── UNSW_NB15/
    ├── Training and Testing Sets/
    ├── UNSW-NB15_1.csv
    ├── UNSW-NB15_2.csv
    ├── UNSW-NB15_3.csv
    ├── UNSW-NB15_4.csv
    ├── NUSW-NB15_features.csv
    ├── NUSW-NB15_GT.csv
    └── The UNSW-NB15 description.pdf
```

---

# Dataset

## UNSW-NB15 Dataset

The project uses the **UNSW-NB15** cybersecurity dataset, which contains both normal and malicious network traffic.

The dataset includes multiple attack categories such as:

- Fuzzers
- Analysis
- Backdoors
- DoS
- Exploits
- Generic
- Reconnaissance
- Shellcode
- Worms

### Dataset Features

The dataset contains:

- Network flow statistics
- Protocol information
- Packet-level attributes
- Traffic behavior indicators

---

# Technologies Used

## Programming Language

- Python 3.x

## Libraries

- pandas
- numpy
- matplotlib
- seaborn
- scikit-learn
- shap
- lime
- joblib

---

# Machine Learning Workflow

## 1. Data Preprocessing

The preprocessing stage includes:

- Loading training and testing datasets
- Feature selection
- Handling categorical features
- Standardization using `StandardScaler`
- Splitting features and labels

---

## 2. Model Training

A **Random Forest Classifier** is used for intrusion detection.

### Advantages of Random Forest

- High classification performance
- Robust against overfitting
- Handles high-dimensional data effectively
- Provides feature importance information

The trained model is exported as:

```text
Forensic_RandomForest_Engine.joblib
```

The scaler is exported as:

```text
Forensic_StandardScaler.joblib
```

---

# Explainable AI (XAI)

This project integrates multiple XAI techniques to improve model transparency and interpretability.

---

## SHAP Analysis

SHAP explains how each feature contributes to the model prediction.

### Outputs

- Global feature importance
- Summary plots
- Attack sample explanations
- Beeswarm visualization

### Generated Files

```text
SHAP/
```

### Key Benefits

- Strong theoretical foundation
- Local and global interpretability
- Feature contribution visualization

---

## LIME Analysis

LIME provides local explanations for individual predictions.

### Outputs

- Local attack sample explanation
- Feature contribution plots
- Global approximation visualizations

### Generated Files

```text
LIME/
```

### Key Benefits

- Human-readable explanations
- Easy interpretation for security analysts
- Instance-level transparency

---

## Permutation Importance

Permutation Importance measures the impact of feature shuffling on model performance.

### Outputs

- Permutation importance ranking
- Random Forest feature importance comparison

### Generated Files

```text
Permutation_Importance/
```

### Key Benefits

- Model-agnostic feature evaluation
- Reliable importance estimation
- Easy comparison between features

---

## Partial Dependence Plot (PDP)

PDP visualizes the relationship between selected features and model predictions.

### Example Features

- `sttl`
- `dload`
- `ct_state_ttl`

### Generated Files

```text
PDP/
```

### Key Benefits

- Understands feature influence trends
- Reveals nonlinear relationships
- Helps interpret model behavior globally

---

# Forensic Evidence Reports

Each explainability module generates a forensic evidence report in JSON format.

The reports contain:

- Timestamp information
- Model metadata
- Feature contribution results
- Analysis evidence
- Reproducibility information

Example:

```json
{
  "model": "RandomForest",
  "timestamp": "2026-05-14",
  "analysis": "SHAP",
  "status": "Completed"
}
```

---

# How to Run

## 1. Install Dependencies

```bash
pip install pandas numpy matplotlib seaborn scikit-learn shap lime joblib
```

---

## 2. Open the Notebook

```bash
jupyter notebook
```

Then open:

```text
1.ipynb
```

---

## 3. Execute All Cells

The notebook will:

1. Load the dataset
2. Preprocess the data
3. Train the model
4. Generate predictions
5. Run XAI analysis
6. Export plots and forensic reports

---

# Example Outputs

The project generates:

- Feature importance visualizations
- SHAP summary plots
- LIME explanation plots
- PDP visualizations
- JSON forensic evidence reports
- Exported machine learning models

---

# Research Significance

This project demonstrates how Explainable AI can improve cybersecurity systems by:

- Increasing model transparency
- Supporting forensic investigations
- Helping analysts understand attack detection decisions
- Improving trust in AI-based security systems

---

# Future Improvements

Possible future enhancements include:

- Deep learning based intrusion detection
- Real-time traffic analysis
- More advanced XAI techniques
- Web dashboard visualization
- Federated learning integration
- Multi-class attack classification optimization

---

# References

## Dataset

UNSW-NB15 Dataset:

- https://research.unsw.edu.au/projects/unsw-nb15-dataset

## Explainable AI

- SHAP Documentation: https://shap.readthedocs.io/
- LIME Documentation: https://lime-ml.readthedocs.io/
- Scikit-learn Documentation: https://scikit-learn.org/

---

# Author

This project was developed for machine learning and cybersecurity research purposes.

---

# License

This project is intended for educational and research use.

