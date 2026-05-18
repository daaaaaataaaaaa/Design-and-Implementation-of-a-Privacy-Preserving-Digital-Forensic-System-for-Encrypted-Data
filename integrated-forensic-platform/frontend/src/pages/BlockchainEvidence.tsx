import { ethers } from "ethers";
import { Blocks, Link, SearchCheck, Upload } from "lucide-react";
import { useState } from "react";
import {
  evidenceRegistryAbi,
  getDefaultEvidenceRegistryAddress,
  rememberEvidenceRegistryAddress,
  sha256Hex
} from "../lib/blockchain";

type EthereumWindow = Window & {
  ethereum?: ethers.Eip1193Provider;
};

export function BlockchainEvidence() {
  const [account, setAccount] = useState("");
  const [contractAddress, setContractAddress] = useState("0x合约地址-硬编码 演示的时候记得改这里");
  const [caseId, setCaseId] = useState("CASE-001");
  const [evidenceName, setEvidenceName] = useState("Forensic Evidence Report");
  const [fileName, setFileName] = useState("evidence.json");
  const [attackType, setAttackType] = useState("Generic");
  const [sourceIp, setSourceIp] = useState("192.168.1.100");
  const [targetUrl, setTargetUrl] = useState("/api/login");
  const [description, setDescription] = useState("Hash notarization for encrypted forensic evidence");
  const [hash, setHash] = useState("");
  const [status, setStatus] = useState("");

  async function getContract(withSigner = false) {
    const ethereum = (window as EthereumWindow).ethereum;
    if (!ethereum) {
      throw new Error("No Ethereum provider detected. Start Ganache and connect MetaMask.");
    }
    const provider = new ethers.BrowserProvider(ethereum);
    if (withSigner) {
      const signer = await provider.getSigner();
      return new ethers.Contract(contractAddress, evidenceRegistryAbi, signer);
    }
    return new ethers.Contract(contractAddress, evidenceRegistryAbi, provider);
  }

  async function connectWallet() {
    const ethereum = (window as EthereumWindow).ethereum;
    if (!ethereum) {
      setStatus("未检测到 MetaMask 或浏览器钱包。");
      return;
    }
    const accounts = (await ethereum.request({ method: "eth_requestAccounts" })) as string[];
    setAccount(accounts[0] ?? "");
  }

  async function handleFile(file?: File) {
    if (!file) return;
    setFileName(file.name);
    setHash(await sha256Hex(file));
  }

  function updateContractAddress(address: string) {
    setContractAddress(address);
    rememberEvidenceRegistryAddress(address);
  }

  async function storeEvidence() {
    setStatus("");
    try {
      const contract = await getContract(true);
      const tx = await contract.storeJSONEvidence(
        caseId,
        hash,
        evidenceName,
        description,
        fileName,
        attackType,
        sourceIp,
        targetUrl
      );
      setStatus(`交易已提交：${tx.hash}`);
      await tx.wait();
      setStatus(`链上存证完成：${tx.hash}`);
    } catch (err) {
      setStatus(err instanceof Error ? err.message : "Store transaction failed");
    }
  }

  async function verifyEvidence() {
    setStatus("");
    try {
      const contract = await getContract(false);
      const exists = await contract.verifyEvidence(hash);
      setStatus(exists ? "验证通过：该哈希已经存在于链上。" : "未找到该证据哈希。");
    } catch (err) {
      setStatus(err instanceof Error ? err.message : "Verification failed");
    }
  }

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">EvidenceRegistry + ethers.js</p>
          <h1>链上存证</h1>
        </div>
        <button className="primary-action" onClick={connectWallet}>
          <Link size={17} /> {account ? `${account.slice(0, 6)}...${account.slice(-4)}` : "连接钱包"}
        </button>
      </div>

      <div className="two-column align-start">
        <section className="panel">
          <div className="panel-heading">
            <h2>合约与哈希</h2>
            <Blocks size={18} />
          </div>
          <label>Contract Address</label>
          <input value={contractAddress} onChange={(event) => updateContractAddress(event.target.value)} />
          <label>Evidence File</label>
          <input type="file" accept=".json,.txt,.csv" onChange={(event) => handleFile(event.target.files?.[0])} />
          <label>SHA-256 Hash</label>
          <textarea value={hash} onChange={(event) => setHash(event.target.value.trim())} />
          <div className="button-row">
            <button className="primary-action" disabled={!contractAddress || hash.length !== 64} onClick={storeEvidence}>
              <Upload size={17} /> 提交存证
            </button>
            <button className="secondary-action" disabled={!contractAddress || hash.length !== 64} onClick={verifyEvidence}>
              <SearchCheck size={17} /> 验证
            </button>
          </div>
          {status && <div className="notice">{status}</div>}
        </section>

        <section className="panel">
          <div className="panel-heading">
            <h2>证据元数据</h2>
          </div>
          <div className="form-grid">
            <label>Case ID<input value={caseId} onChange={(event) => setCaseId(event.target.value)} /></label>
            <label>Evidence Name<input value={evidenceName} onChange={(event) => setEvidenceName(event.target.value)} /></label>
            <label>File Name<input value={fileName} onChange={(event) => setFileName(event.target.value)} /></label>
            <label>Attack Type<input value={attackType} onChange={(event) => setAttackType(event.target.value)} /></label>
            <label>Source IP<input value={sourceIp} onChange={(event) => setSourceIp(event.target.value)} /></label>
            <label>Target URL<input value={targetUrl} onChange={(event) => setTargetUrl(event.target.value)} /></label>
          </div>
          <label>Description</label>
          <textarea value={description} onChange={(event) => setDescription(event.target.value)} />
        </section>
      </div>
    </section>
  );
}
