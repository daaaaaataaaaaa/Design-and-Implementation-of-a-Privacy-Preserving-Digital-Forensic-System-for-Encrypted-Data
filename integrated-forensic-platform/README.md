# Privacy-Preserving Digital Forensic Platform

This folder turns the four demo components into one integrated system:

- Intrusion detection and XAI evidence generation through a FastAPI ML service.
- Privacy-preserving encrypted evidence storage through a Spring Boot facade over the existing Java searchable-encryption code.
- Blockchain evidence notarization through the existing `EvidenceRegistry.sol` contract and an ethers.js page.
- A React/Vite workbench that presents the workflow as one digital forensic platform.

## Architecture

```text
React workbench
  |-- ML API: prediction, report browsing, XAI image assets
  |-- Searchable encryption API: auth, encrypted upload, search, preview, delete
  |-- Wallet/provider: Ganache or MetaMask contract calls

Existing demo assets remain in ../demo and are reused rather than copied.
```

## Run Locally

### 1. ML service

```powershell
cd integrated-forensic-platform/backend/ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8001
```

### 2. Searchable encryption API

This service compiles the existing Java source folder as an additional Maven source root.

```powershell
cd integrated-forensic-platform/backend/searchable-encryption-api
mvn spring-boot:run
```

Set MySQL credentials with environment variables if needed:

```powershell
$env:SE_DB_HOST="localhost"
$env:SE_DB_PORT="3306"
$env:SE_DB_NAME="searchable_encryption"
$env:SE_DB_USER="root"
$env:SE_DB_PASSWORD="your-password"
```

### 3. Frontend

```powershell
cd integrated-forensic-platform/frontend
npm install
npm run dev
```

Open `http://localhost:5173`.

## Default Ports

- Frontend: `5173`
- ML service: `8001`
- Searchable encryption API: `8082`
- Ganache: usually `7545`

## Suggested Demo Story

1. Use the ML page to score a network-flow sample and inspect explanation assets.
2. Save the generated or selected JSON evidence into the encrypted evidence vault.
3. Search the encrypted vault by keyword.
4. Compute or paste the evidence SHA-256 hash.
5. Submit the hash to `EvidenceRegistry` through the blockchain page.
6. Verify the same hash later to prove evidence integrity.

