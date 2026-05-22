# Privacy-Preserving Digital Forensic System for Encrypted Data

This is an integrated demonstration system for encrypted-data digital forensics. It combines intrusion detection, explainable forensic evidence, a searchable encrypted evidence vault, and on-chain evidence anchoring. The frontend uses React/Vite, the machine-learning service uses FastAPI, the searchable encrypted evidence vault uses Spring Boot, and the platform reuses the Java encryption, indexing, and database code in `demo/searchable encryption`.

## Feature Overview

- Intrusion detection: submit network-traffic features and receive the predicted class, probability, and evidence hash.
- Explainable forensics: browse SHAP, LIME, Permutation Importance, PDP, and other explanation assets and reports.
- Encrypted evidence vault: upload text, JSON, PDF, image, Excel, and other evidence files for encrypted server-side storage.
- Searchable encryption: search encrypted evidence by keyword without directly exposing plaintext content.
- File preview: preview images, PDFs, Excel tables, JSON, plain text, and more, with in-preview keyword search and highlighting.
- On-chain evidence anchoring: submit evidence hashes to the `EvidenceRegistry.sol` contract so integrity can be verified later.

## Directory Layout

```text
.
+-- demo/
|   +-- ML_Dataset/                         # ML models, XAI images, and report assets
|   +-- searchable encryption/              # Original Java searchable-encryption implementation
+-- integrated-forensic-platform/
|   +-- backend/
|   |   +-- ml-service/                     # FastAPI ML service, port 8001
|   |   +-- searchable-encryption-api/      # Spring Boot encrypted-vault API, port 8082
|   +-- contracts/
|   |   +-- EvidenceRegistry.sol            # On-chain evidence smart contract
|   +-- frontend/                           # React/Vite frontend, port 5173
|   +-- docker-compose.yml                  # Container orchestration for MySQL, ML service, and SE API
+-- README.md
```

## Requirements

Recommended local tools:

- Java 17
- Maven 3.9+
- Node.js 18+ and npm
- Python 3.11
- MySQL 8.x, or Docker Desktop
- Optional: Ganache, MetaMask, and Remix for the on-chain evidence demo

Default ports:

| Service | Address |
| --- | --- |
| Frontend | `http://localhost:5173` |
| ML service | `http://localhost:8001` |
| Searchable Encryption API | `http://localhost:8082` |
| MySQL | `localhost:3306` |

## Quick Start: Local Development

The commands below use Windows PowerShell. Running each service in a separate terminal window is recommended.

### 1. Start MySQL

If MySQL is already installed locally, use the default connection settings:

```text
host: localhost
port: 3306
database: searchable_encryption
user: root
password: 123456ysy
```

If MySQL is not installed, start only the database with Docker:

```powershell
cd integrated-forensic-platform
docker compose up -d mysql
```

The Spring Boot service automatically creates the `searchable_encryption` database and required tables on startup.

### 2. Start the ML Service

```powershell
cd integrated-forensic-platform/backend/ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt

$env:FORENSIC_ML_ASSET_DIR = (Resolve-Path "../../../demo/ML_Dataset/ML_Dataset")
uvicorn app.main:app --reload --host 0.0.0.0 --port 8001
```

Check that the service is healthy:

```powershell
Invoke-WebRequest http://localhost:8001/health
Invoke-WebRequest http://localhost:8001/api/ml/metadata
```

If model assets are missing, the service can still start and will use the fallback prediction logic in the code, but fewer XAI images and reports will be available.

### 3. Start the Searchable Encryption API

```powershell
cd integrated-forensic-platform/backend/searchable-encryption-api

$env:SE_DB_HOST = "localhost"
$env:SE_DB_PORT = "3306"
$env:SE_DB_NAME = "searchable_encryption"
$env:SE_DB_USER = "root"
$env:SE_DB_PASSWORD = "123456ysy"

mvn spring-boot:run
```

Check that the service is healthy:

```powershell
Invoke-WebRequest http://localhost:8082/api/se/health
```

You can also package and run the service:

```powershell
mvn -DskipTests package
java -jar target/searchable-encryption-api-0.1.0.jar
```

### 4. Start the Frontend

```powershell
cd integrated-forensic-platform/frontend
npm install
npm run dev
```

Open:

```text
http://localhost:5173
```

The frontend reads these API addresses by default:

```text
VITE_ML_API_URL=http://localhost:8001
VITE_SE_API_URL=http://localhost:8082
VITE_DEFAULT_CONTRACT_ADDRESS=
VITE_PLATFORM_USERNAME=12345
VITE_PLATFORM_PASSWORD=12345
```

To override them, create `integrated-forensic-platform/frontend/.env.local`:

```env
VITE_ML_API_URL=http://localhost:8001
VITE_SE_API_URL=http://localhost:8082
VITE_DEFAULT_CONTRACT_ADDRESS=0xYourContractAddress
VITE_PLATFORM_USERNAME=12345
VITE_PLATFORM_PASSWORD=12345
```

## Start Backend Services with Docker

You can also start MySQL, the ML service, and the Searchable Encryption API with Docker Compose:

```powershell
cd integrated-forensic-platform
docker compose up --build
```

Then start the frontend in another terminal:

```powershell
cd integrated-forensic-platform/frontend
npm install
npm run dev
```

In container mode:

- The MySQL root password is `123456ysy`.
- The API container connects to the database through the service name `mysql`.
- The ML container mounts the repository `demo` directory at `/workspace/demo`.

Stop the containers:

```powershell
cd integrated-forensic-platform
docker compose down
```

To clear the MySQL data volume:

```powershell
docker compose down -v
```

## Workflow

### Intrusion Detection

1. Open the frontend at `http://localhost:5173`.
2. Go to the `Intrusion Detection` page.
3. Use the sample features or paste JSON features.
4. Run detection to view the prediction, probability, and evidence hash.
5. If the contract address and wallet are configured, save the detection result to the encrypted evidence vault and anchor it on-chain.

### Encrypted Evidence Vault

1. Go to the `Encrypted Evidence Vault` page.
2. Register or sign in.
3. Upload text, JSON, PDF, image, Excel, and other evidence files.
4. Refresh the list in `Documents`, then click a file name or `Open Preview`.
5. Enter keywords in `Search` to search.
6. If keyword extraction rules changed or old data was imported, select files in `Documents` and click `Rebuild Index`.

Search notes:

- File names participate in search.
- Extractable keywords from text, PDF, Word, Excel, and JSON files are indexed.
- The JSON fields `Searchable_Keywords`, `keywords`, and `keyword` receive special handling.
- Values such as `PROTOCOL:tcp` generate `protocol:tcp`, `protocol`, and `tcp`, so both field names and values can be searched.

### Preview and Find

The evidence-vault preview window supports:

- Original image preview
- Embedded PDF preview
- Excel/CSV table preview
- Automatically formatted JSON display
- Full text preview
- File open and download actions
- Word/WPS-style in-preview find, with match counts, previous/next navigation, and current-match highlighting

### On-Chain Evidence Anchoring

The smart contract is located at:

```text
integrated-forensic-platform/contracts/EvidenceRegistry.sol
```

Recommended demo path: Ganache + MetaMask + Remix. The blockchain feature only stores the evidence hash and metadata on-chain; the original evidence file stays in the encrypted evidence vault or on your local machine.

#### 1. Start Ganache

1. Open Ganache.
2. Click `QuickStart`.
3. Confirm that `RPC Server` is `HTTP://127.0.0.1:7545`.
4. Keep the Ganache window open while using the frontend.

#### 2. Configure MetaMask

1. Install the MetaMask Chrome extension.
2. Create a local test wallet. Do not use a real wallet or real private key for this demo.
3. Add the Ganache network in MetaMask:

```text
Network name: Ganache
RPC URL: http://127.0.0.1:7545
Chain ID: 1337
Currency symbol: ETH
```

If MetaMask reports that the chain ID is different, use the chain ID shown by your Ganache workspace settings.

4. Import a Ganache account:
   - In Ganache, click the key icon beside the first account.
   - Copy the private key.
   - In MetaMask, click the account avatar, choose `Import account`, paste the private key, and import it.

#### 3. Deploy `EvidenceRegistry.sol`

Option A: Remix, recommended for the fastest demo:

1. Open `https://remix.ethereum.org`.
2. Create `contracts/EvidenceRegistry.sol` in Remix and paste the code from `integrated-forensic-platform/contracts/EvidenceRegistry.sol`.
3. Open `Solidity Compiler`, compile the contract, and set `Advanced Configurations > EVM Version` to `Paris` if Ganache fails to execute newer opcodes.
4. Open `Deploy & Run Transactions`.
5. Select `Dev - Ganache Provider` if it is available. Otherwise choose `Web3 Provider` and enter `http://127.0.0.1:7545`.
6. Click `Deploy`.
7. Copy the deployed contract address.

Option B: Hardhat, optional:

The repository does not require Hardhat for normal use. If you prefer local deployment, create a Hardhat project under `integrated-forensic-platform`, copy `contracts/EvidenceRegistry.sol` into Hardhat's `contracts/` directory, configure the `ganache` network with `url: "http://127.0.0.1:7545"`, then run:

```powershell
npx hardhat run scripts/deploy.js --network ganache
```

#### 4. Put the contract address into the frontend

Use one of these two methods:

1. Open the frontend, go to `On-Chain Evidence`, and paste the address into `Contract Address`. The page stores it in browser `localStorage`.
2. Or create `integrated-forensic-platform/frontend/.env.local` and set:

```env
VITE_DEFAULT_CONTRACT_ADDRESS=0xYourDeployedContractAddress
```

You do not need to edit `BlockchainEvidence.tsx`. The address is read through `src/lib/blockchain.ts`.

#### 5. Confirm the ABI

If you deploy the unchanged `integrated-forensic-platform/contracts/EvidenceRegistry.sol`, the ABI in `integrated-forensic-platform/frontend/src/lib/blockchain.ts` already matches the contract.

Only update `evidenceRegistryAbi` if you changed the Solidity contract. In that case, copy the ABI from `Remix > Solidity Compiler > ABI` and replace the `evidenceRegistryAbi` array in `src/lib/blockchain.ts`.

#### 6. Start the frontend and use the page

```powershell
cd integrated-forensic-platform/frontend
npm install
npm run dev
```

Open `http://localhost:5173`, then:

1. Go to `On-Chain Evidence`.
2. Click `Connect Wallet` and select the imported Ganache account in MetaMask.
3. Confirm that MetaMask is using the `Ganache` network.
4. Paste or confirm the deployed `EvidenceRegistry` contract address.
5. Choose an evidence file, or paste a 64-character SHA-256 hash. Choosing a file calculates the hash automatically.
6. Fill `Case ID`, `Evidence Name`, `File Name`, `Attack Type`, `Source IP`, `Target URL`, and `Description`.
7. Click `Submit Evidence`, confirm the MetaMask transaction, and wait for the completion message.
8. To check an existing hash, keep the same contract address and hash, then click `Verify`.

You can also use the integrated workflow from `Intrusion Detection`:

1. Start the ML service and Searchable Encryption API first.
2. Run a detection.
3. Paste the contract address in the detection result panel.
4. Click `Save to Vault and Anchor On-Chain`.

This saves the detection result into the encrypted evidence vault, then sends `storeJSONEvidence(...)` to the smart contract through MetaMask.

## Common APIs

ML service:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/health` | Health check |
| `GET` | `/api/ml/metadata` | Model, XAI asset, and report metadata |
| `POST` | `/api/ml/predict` | Intrusion-detection prediction |
| `GET` | `/api/ml/reports/{method}` | Get SHAP/LIME/PDP and related reports |
| `GET` | `/api/ml/assets/...` | Access XAI image assets |

Searchable Encryption API:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/se/health` | Health check |
| `POST` | `/api/se/auth/register` | Register a user and generate keys |
| `POST` | `/api/se/auth/login` | Sign in |
| `GET` | `/api/se/documents` | Get the document list |
| `POST` | `/api/se/documents/upload` | Upload evidence |
| `GET` | `/api/se/documents/search?keyword=...` | Keyword search |
| `GET` | `/api/se/documents/{docId}` | Get preview details |
| `GET` | `/api/se/documents/{docId}/download` | Download the decrypted original file |
| `POST` | `/api/se/documents/{docId}/rebuild-index` | Rebuild one document index |
| `DELETE` | `/api/se/documents/{docId}` | Delete a document |

## Build Checks

Frontend:

```powershell
cd integrated-forensic-platform/frontend
npm run build
```

Backend:

```powershell
cd integrated-forensic-platform/backend/searchable-encryption-api
mvn -DskipTests package
```

## FAQ

### Login failed: unable to connect to service: http://localhost:8082

The Searchable Encryption API is not running, or it is not using port `8082`. Check:

```powershell
Invoke-WebRequest http://localhost:8082/api/se/health
```

### ML service temporarily unavailable: http://localhost:8001/api/ml/metadata

The ML service is not running, or `FORENSIC_ML_ASSET_DIR` points to the wrong directory. Check:

```powershell
Invoke-WebRequest http://localhost:8001/health
```

### Database connection failed

Confirm that MySQL is running and that the account and password match. The default password is `123456ysy`. If your local MySQL password is different, set it before starting Spring Boot:

```powershell
$env:SE_DB_PASSWORD = "your-password"
```

### Recently changed keywords cannot be found

Old file indexes do not update automatically. Go to `Documents`, select the file, and click `Rebuild Index`. To rebuild all files, click `Select All` and then `Rebuild Index`.

### Sign-in is required again after restart

The sign-in token is stored in backend memory. After the Searchable Encryption API restarts, the frontend's existing token becomes invalid and you need to sign in again.

### Maven package fails: Unable to rename jar

An old `java -jar` process is usually holding the jar file. Stop the 8082 backend process first, then run:

```powershell
mvn -DskipTests package
```

### Frontend still shows the old UI

The browser may have cached old assets. Use `Ctrl + F5` to force a refresh.

## Generated Files

The following directories or files are local runtime artifacts and do not need to be committed:

- `frontend/node_modules/`
- `frontend/dist/`
- `backend/searchable-encryption-api/target/`
- `backend/ml-service/.venv/`
- `*.log`
- `.env.local`

Encrypted evidence-vault user keys are stored under the current system user directory, for example:

```text
%USERPROFILE%\.integrated-forensics\client-keys
%USERPROFILE%\.searchable-encryption\client-keys
```

These files are local runtime data and should not be committed to the repository.
