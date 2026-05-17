# Privacy-Preserving Digital Forensic System for Encrypted Data

这是一个面向加密数据取证的集成演示系统，包含入侵检测、可解释性证据、可搜索加密证据库和链上存证。前端使用 React/Vite，机器学习服务使用 FastAPI，搜索加密证据库使用 Spring Boot，并复用 `demo/searchable encryption` 中的 Java 加密、索引和数据库代码。

## 功能概览

- 入侵检测：提交网络流量特征，返回预测结果、概率和证据哈希。
- 可解释性取证：浏览 SHAP、LIME、Permutation Importance、PDP 等解释性资产和报告。
- 加密证据库：上传文本、JSON、PDF、图片、Excel 等证据文件，服务端加密存储。
- 可搜索加密：通过关键词搜索加密证据，不直接暴露明文内容。
- 文件预览：支持图片、PDF、Excel 表格、JSON、纯文本等预览，并支持预览内关键词查找和高亮。
- 链上存证：将证据哈希提交到 `EvidenceRegistry.sol` 合约，后续可验证完整性。

## 目录结构

```text
.
├── demo/
│   ├── ML_Dataset/                         # 机器学习模型、XAI 图片和报告资产
│   └── searchable encryption/              # 原 Java 可搜索加密实现
├── integrated-forensic-platform/
│   ├── backend/
│   │   ├── ml-service/                     # FastAPI ML 服务，端口 8001
│   │   └── searchable-encryption-api/      # Spring Boot 加密证据库 API，端口 8082
│   ├── contracts/
│   │   └── EvidenceRegistry.sol            # 链上存证智能合约
│   ├── frontend/                           # React/Vite 前端，端口 5173
│   └── docker-compose.yml                  # MySQL、ML 服务、SE API 的容器编排
└── README.md
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

下面命令以 Windows PowerShell 为例。每个服务建议单独开一个终端窗口。

### 1. 启动 MySQL

如果本机已经有 MySQL，可以直接使用。默认连接参数如下：

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

前端默认读取下面这些接口地址：

```text
VITE_ML_API_URL=http://localhost:8001
VITE_SE_API_URL=http://localhost:8082
VITE_DEFAULT_CONTRACT_ADDRESS=
VITE_SE_DEMO_USERNAME=demo
VITE_SE_DEMO_PASSWORD=demo123
```

如需覆盖，创建 `integrated-forensic-platform/frontend/.env.local`：

```env
VITE_ML_API_URL=http://localhost:8001
VITE_SE_API_URL=http://localhost:8082
VITE_DEFAULT_CONTRACT_ADDRESS=0xYourContractAddress
VITE_SE_DEMO_USERNAME=demo
VITE_SE_DEMO_PASSWORD=demo123
```

## Docker 启动后端服务

也可以用 Docker Compose 启动 MySQL、ML 服务和 Searchable Encryption API：

```powershell
cd integrated-forensic-platform
docker compose up --build
```

然后另开终端启动前端：

```powershell
cd integrated-forensic-platform/frontend
npm install
npm run dev
```

容器模式下：

- MySQL root 密码是 `123456ysy`
- API 容器通过服务名 `mysql` 连接数据库
- ML 容器会把仓库的 `demo` 目录挂载到 `/workspace/demo`

停止容器：

```powershell
cd integrated-forensic-platform
docker compose down
```

如果要清空 MySQL 数据卷：

```powershell
docker compose down -v
```

## 使用流程

### 入侵检测

1. 打开前端 `http://localhost:5173`。
2. 进入“入侵检测”页面。
3. 使用样例特征或粘贴 JSON 特征。
4. 点击运行检测，查看预测结果、概率和证据哈希。
5. 如已配置合约地址和钱包，可将检测结果保存到加密证据库并进行链上存证。

### 加密证据库

1. 进入“加密证据库”页面。
2. 注册或登录用户。
3. 上传文本、JSON、PDF、图片、Excel 等证据。
4. 在 `Documents` 中刷新列表，点击文件名或 `Open Preview` 预览。
5. 在 `Search` 中输入关键词搜索。
6. 如果修改了关键词提取规则或导入旧数据，可以在 `Documents` 中选择文件并点击 `Rebuild Index`。

搜索说明：

- 文件名会参与搜索。
- 文本、PDF、Word、Excel、JSON 中可提取的关键词会参与索引。
- JSON 的 `Searchable_Keywords`、`keywords`、`keyword` 字段会被特别处理。
- 类似 `PROTOCOL:tcp` 的值会同时生成 `protocol:tcp`、`protocol`、`tcp`，所以字段名和值都可以搜索。

### 预览与查找

证据库预览窗口支持：

- 图片原图预览
- PDF 内嵌预览
- Excel/CSV 表格预览
- JSON 自动格式化显示
- 文本完整预览
- 文件打开和下载
- 类似 Word/WPS 的预览内查找：显示匹配数量，支持上一处、下一处，并高亮当前命中

### 链上存证

智能合约位于：

```text
integrated-forensic-platform/contracts/EvidenceRegistry.sol
```

演示方式：

1. 启动 Ganache 或其他本地区块链。
2. 用 Remix 或你熟悉的工具部署 `EvidenceRegistry.sol`。
3. 复制部署后的合约地址。
4. 在前端 `.env.local` 中设置 `VITE_DEFAULT_CONTRACT_ADDRESS`，或直接在页面输入。
5. 通过 MetaMask 连接对应网络。
6. 在“链上存证”页面提交或验证证据哈希。

## 常用 API

ML 服务：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/health` | 健康检查 |
| `GET` | `/api/ml/metadata` | 模型、XAI 资产和报告元数据 |
| `POST` | `/api/ml/predict` | 入侵检测预测 |
| `GET` | `/api/ml/reports/{method}` | 获取 SHAP/LIME/PDP 等报告 |
| `GET` | `/api/ml/assets/...` | 访问 XAI 图片资产 |

Searchable Encryption API：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/se/health` | 健康检查 |
| `POST` | `/api/se/auth/register` | 注册用户并生成密钥 |
| `POST` | `/api/se/auth/login` | 登录 |
| `GET` | `/api/se/documents` | 获取文档列表 |
| `POST` | `/api/se/documents/upload` | 上传证据 |
| `GET` | `/api/se/documents/search?keyword=...` | 关键词搜索 |
| `GET` | `/api/se/documents/{docId}` | 获取预览详情 |
| `GET` | `/api/se/documents/{docId}/download` | 下载解密后的原文件 |
| `POST` | `/api/se/documents/{docId}/rebuild-index` | 重建单个文档索引 |
| `DELETE` | `/api/se/documents/{docId}` | 删除文档 |

## 构建检查

前端：

```powershell
cd integrated-forensic-platform/frontend
npm run build
```

后端：

```powershell
cd integrated-forensic-platform/backend/searchable-encryption-api
mvn -DskipTests package
```

## 常见问题

### Login failed: 无法连接到服务：http://localhost:8082

Searchable Encryption API 没启动或端口不是 `8082`。检查：

```powershell
Invoke-WebRequest http://localhost:8082/api/se/health
```

### ML 服务暂时不可达：http://localhost:8001/api/ml/metadata

ML 服务没启动，或 `FORENSIC_ML_ASSET_DIR` 指向错误。检查：

```powershell
Invoke-WebRequest http://localhost:8001/health
```

### 数据库连接失败

确认 MySQL 正在运行，并且账号密码匹配。默认密码是 `123456ysy`。如果你的本地 MySQL 密码不同，启动 Spring Boot 前设置：

```powershell
$env:SE_DB_PASSWORD = "your-password"
```

### 搜不到刚改过规则的关键词

旧文件的索引不会自动变化。进入 `Documents`，选择文件后点击 `Rebuild Index`。如果要重建全部文件，可以先 `Select All`，再 `Rebuild Index`。

### 重启后需要重新登录

登录 token 保存在后端内存里。重启 Searchable Encryption API 后，前端已有 token 会失效，需要重新登录。

### Maven 打包失败：Unable to rename jar

通常是旧的 `java -jar` 进程正在占用 jar。先停掉 8082 后端进程，再执行：

```powershell
mvn -DskipTests package
```

### 前端仍显示旧界面

浏览器可能缓存了旧资源。使用 `Ctrl + F5` 强制刷新。

## 生成文件说明

以下目录或文件是本地运行产物，不需要提交：

- `frontend/node_modules/`
- `frontend/dist/`
- `backend/searchable-encryption-api/target/`
- `backend/ml-service/.venv/`
- `*.log`
- `.env.local`

加密证据库用户密钥会保存在当前系统用户目录下，例如：

```text
%USERPROFILE%\.integrated-forensics\client-keys
%USERPROFILE%\.searchable-encryption\client-keys
```

这些文件是本机运行数据，不应提交到仓库。
