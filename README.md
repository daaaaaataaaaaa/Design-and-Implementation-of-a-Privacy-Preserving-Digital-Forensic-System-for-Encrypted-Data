# Privacy-Preserving Digital Forensic System for Encrypted Data

## 适用于加密数据的隐私保护数字取证系统

这是一个面向加密数据取证的集成演示系统，包含入侵检测、可解释性证据、可搜索加密证据库和链上存证。前端使用 React/Vite，机器学习服务使用 FastAPI，可搜索加密证据库使用 Spring Boot，并复用 `demo/searchable encryption` 中的 Java 加密、索引和数据库代码。

## 功能概览

- 入侵检测：提交网络流量特征，返回预测类别、概率和证据哈希。
- 可解释性取证：浏览 SHAP、LIME、Permutation Importance、PDP 等解释性资源和报告。
- 加密证据库：上传文本、JSON、PDF、图片、Excel 等证据文件，服务端加密存储。
- 可搜索加密：通过关键词检索加密证据，不直接暴露明文内容。
- 文件预览：支持图片、PDF、Excel、JSON、纯文本等预览，并支持预览内关键词查找和高亮。
- 链上存证：将证据哈希提交到 `EvidenceRegistry.sol` 合约，后续可验证完整性。
- 平台登录：前端提供基础登录页，默认账号密码可通过环境变量配置。

## 目录结构

```text
.
+-- demo/
|   +-- ML_Dataset/                         # 机器学习模型、XAI 图片和报告资源
|   +-- searchable encryption/              # 原 Java 可搜索加密实现
+-- integrated-forensic-platform/
|   +-- backend/
|   |   +-- ml-service/                     # FastAPI ML 服务，端口 8001
|   |   +-- searchable-encryption-api/      # Spring Boot 加密证据库 API，端口 8082
|   +-- contracts/
|   |   +-- EvidenceRegistry.sol            # 链上存证智能合约
|   +-- frontend/                           # React/Vite 前端，端口 5173
|   +-- docker-compose.yml                  # MySQL、ML 服务、SE API 的容器编排
+-- README.md
```

## 环境要求

本地启动推荐安装：

- Java 17
- Maven 3.9+
- Node.js 18+ 和 npm
- Python 3.11
- MySQL 8.x，或 Docker Desktop
- 可选：Ganache、MetaMask、Remix，用于链上存证演示

默认端口：

| 服务 | 地址 |
| --- | --- |
| 前端 | `http://localhost:5173` |
| ML 服务 | `http://localhost:8001` |
| Searchable Encryption API | `http://localhost:8082` |
| MySQL | `localhost:3306` |

## 快速启动：本地开发模式

下面命令以 Windows PowerShell 为例。建议每个服务单独打开一个终端窗口。

### 1. 启动 MySQL

如果本机已经有 MySQL，可以直接使用默认连接参数：

```text
host: localhost
port: 3306
database: searchable_encryption
user: root
password: 123456ysy
```

如果没有 MySQL，可以用 Docker 只启动数据库：

```powershell
cd integrated-forensic-platform
docker compose up -d mysql
```

Spring Boot 服务启动时会自动创建 `searchable_encryption` 数据库和所需表结构。

### 2. 启动 ML 服务

```powershell
cd integrated-forensic-platform/backend/ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt

$env:FORENSIC_ML_ASSET_DIR = (Resolve-Path "../../../demo/ML_Dataset/ML_Dataset")
uvicorn app.main:app --reload --host 0.0.0.0 --port 8001
```

检查服务是否正常：

```powershell
Invoke-WebRequest http://localhost:8001/health
Invoke-WebRequest http://localhost:8001/api/ml/metadata
```

如果模型资产不存在，服务仍可启动，并会使用代码中的 fallback prediction 逻辑，但 XAI 图片和报告数量会减少。

### 3. 启动可搜索加密 API

```powershell
cd integrated-forensic-platform/backend/searchable-encryption-api

$env:SE_DB_HOST = "localhost"
$env:SE_DB_PORT = "3306"
$env:SE_DB_NAME = "searchable_encryption"
$env:SE_DB_USER = "root"
$env:SE_DB_PASSWORD = "123456ysy"

mvn spring-boot:run
```

检查服务是否正常：

```powershell
Invoke-WebRequest http://localhost:8082/api/se/health
```

也可以先打包再运行：

```powershell
mvn -DskipTests package
java -jar target/searchable-encryption-api-0.1.0.jar
```

### 4. 启动前端

```powershell
cd integrated-forensic-platform/frontend
npm install
npm run dev
```

打开：

```text
http://localhost:5173
```

前端默认读取下面这些接口和登录配置：

```text
VITE_ML_API_URL=http://localhost:8001
VITE_SE_API_URL=http://localhost:8082
VITE_DEFAULT_CONTRACT_ADDRESS=
VITE_PLATFORM_USERNAME=12345
VITE_PLATFORM_PASSWORD=12345
```

如需覆盖，创建 `integrated-forensic-platform/frontend/.env.local`：

```env
VITE_ML_API_URL=http://localhost:8001
VITE_SE_API_URL=http://localhost:8082
VITE_DEFAULT_CONTRACT_ADDRESS=
VITE_PLATFORM_USERNAME=your-user
VITE_PLATFORM_PASSWORD=your-password
```

## Docker 启动

如果只想用容器启动后端依赖，可在集成平台目录运行：

```powershell
cd integrated-forensic-platform
docker compose up --build
```

之后前端仍可单独以开发模式运行，或根据需要补充容器化前端部署。

## 测试与验证

可搜索加密 demo：

```powershell
cd "demo/searchable encryption"
mvn test
```

Spring Boot API：

```powershell
cd integrated-forensic-platform/backend/searchable-encryption-api
mvn test
```

前端构建：

```powershell
cd integrated-forensic-platform/frontend
npm run build
```

## 链上存证演示

1. 使用 Remix 或 Hardhat 部署 `integrated-forensic-platform/contracts/EvidenceRegistry.sol`。
2. 将部署后的合约地址写入前端环境变量 `VITE_DEFAULT_CONTRACT_ADDRESS`。
3. 在浏览器中连接 MetaMask 和本地链或测试链。
4. 在链上存证页面提交证据哈希并验证记录。

## 说明

- `demo/searchable encryption` 保留了原 Java 桌面/服务端演示项目。
- `integrated-forensic-platform/backend/searchable-encryption-api` 通过 Maven 引入 legacy Java 源码，作为 Web API 复用加密、索引和数据库能力。
- `integrated-forensic-platform/backend/ml-service` 会优先加载 `demo/ML_Dataset/ML_Dataset` 下的模型与 XAI 资源。
- `integrated-forensic-platform/frontend` 是集成入口，默认需要登录后使用各业务页面。
