import { ethers } from "ethers";
import { Blocks, Link, SearchCheck, Upload } from "lucide-react";
import { useRef, useState } from "react";
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
  const evidenceFileInputRef = useRef<HTMLInputElement>(null);
  const [account, setAccount] = useState("");
  const [contractAddress, setContractAddress] = useState(getDefaultEvidenceRegistryAddress);
  const [caseId, setCaseId] = useState("CASE-001");
  const [evidenceName, setEvidenceName] = useState("Forensic Evidence Report");
  const [fileName, setFileName] = useState("evidence.json");
  const [attackType, setAttackType] = useState("Generic");
  const [sourceIp, setSourceIp] = useState("192.168.1.100");
  const [targetUrl, setTargetUrl] = useState("/api/login");
  const [description, setDescription] = useState("Hash notarization for encrypted forensic evidence");
  const [hash, setHash] = useState("");
  const [status, setStatus] = useState("");
  const [selectedFileName, setSelectedFileName] = useState("");

  async function getContract(withSigner = false) {
    const ethereum = (window as EthereumWindow).ethereum;
    if (!ethereum) {
      throw new Error("No Ethereum provider detected. Start Ganache and connect MetaMask.");
    }
    if (!ethers.isAddress(contractAddress.trim())) {
      throw new Error("Enter a valid EvidenceRegistry contract address first.");
    }
    const provider = new ethers.BrowserProvider(ethereum);
    if (withSigner) {
      const signer = await provider.getSigner();
      return new ethers.Contract(contractAddress.trim(), evidenceRegistryAbi, signer);
    }
    return new ethers.Contract(contractAddress.trim(), evidenceRegistryAbi, provider);
  }

  async function connectWallet() {
    const ethereum = (window as EthereumWindow).ethereum;
    if (!ethereum) {
      setStatus("MetaMask or a browser wallet was not detected.");
      return;
    }
    try {
      const accounts = (await ethereum.request({ method: "eth_requestAccounts" })) as string[];
      setAccount(accounts[0] ?? "");
      setStatus(accounts[0] ? "Wallet connected." : "No wallet account selected.");
    } catch (err) {
      setStatus(err instanceof Error ? err.message : "Failed to connect wallet.");
    }
  }

  async function handleFile(file?: File) {
    if (!file) return;
    setSelectedFileName(file.name);
    setFileName(file.name);
    setHash(await sha256Hex(file));
  }

  function updateContractAddress(address: string) {
    setContractAddress(address);
    rememberEvidenceRegistryAddress(address);
  }

  function validateEvidenceInput() {
    if (!contractAddress.trim()) {
      return "Enter the EvidenceRegistry contract address first.";
    }
    if (!ethers.isAddress(contractAddress.trim())) {
      return "The contract address is invalid. It must be an Ethereum address starting with 0x.";
    }
    if (!hash.trim()) {
      return "Select an evidence file first, or paste a 64-character SHA-256 hash.";
    }
    if (!/^[a-fA-F0-9]{64}$/.test(hash.trim())) {
      return "The SHA-256 hash must contain 64 hexadecimal characters.";
    }
    return "";
  }

  async function storeEvidence() {
    setStatus("");
    const validationMessage = validateEvidenceInput();
    if (validationMessage) {
      setStatus(validationMessage);
      return;
    }
    try {
      const contract = await getContract(true);
      const tx = await contract.storeJSONEvidence(
        caseId,
        hash.trim(),
        evidenceName,
        description,
        fileName,
        attackType,
        sourceIp,
        targetUrl
      );
      setStatus(`Transaction submitted: ${tx.hash}`);
      await tx.wait();
      setStatus(`On-chain evidence anchoring completed: ${tx.hash}`);
    } catch (err) {
      setStatus(err instanceof Error ? err.message : "Store transaction failed");
    }
  }

  async function verifyEvidence() {
    setStatus("");
    const validationMessage = validateEvidenceInput();
    if (validationMessage) {
      setStatus(validationMessage);
      return;
    }
    try {
      const contract = await getContract(false);
      const exists = await contract.verifyEvidence(hash.trim());
      setStatus(exists ? "Verification passed: this hash already exists on-chain." : "Evidence hash not found.");
    } catch (err) {
      setStatus(err instanceof Error ? err.message : "Verification failed");
    }
  }

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">EvidenceRegistry + ethers.js</p>
        <h1>On-Chain Evidence</h1>
        </div>
        <button className="primary-action" type="button" onClick={connectWallet}>
        <Link size={17} /> {account ? `${account.slice(0, 6)}...${account.slice(-4)}` : "Connect Wallet"}
        </button>
      </div>

      <div className="two-column align-start">
        <section className="panel">
          <div className="panel-heading">
          <h2>Contract and Hash</h2>
            <Blocks size={18} />
          </div>
          <label>Contract Address</label>
          <input value={contractAddress} onChange={(event) => updateContractAddress(event.target.value)} />
          <label>Evidence File</label>
          <input
            ref={evidenceFileInputRef}
            className="hidden-file-input"
            type="file"
            accept=".json,.txt,.csv"
            onChange={(event) => handleFile(event.target.files?.[0])}
          />
          <div className="file-picker-row">
            <button className="secondary-action" type="button" onClick={() => evidenceFileInputRef.current?.click()}>
              Choose File
            </button>
            <span className="file-picker-name">{selectedFileName || "No file selected"}</span>
          </div>
          <label>SHA-256 Hash</label>
        <textarea value={hash} onChange={(event) => setHash(event.target.value.trim())} placeholder="Select a file to calculate automatically, or paste a 64-character SHA-256 hash" />
          <div className="button-row">
            <button className="primary-action" type="button" onClick={storeEvidence}>
            <Upload size={17} /> Submit Evidence
            </button>
            <button className="secondary-action" type="button" onClick={verifyEvidence}>
            <SearchCheck size={17} /> Verify
            </button>
          </div>
          {status && <div className="notice">{status}</div>}
        </section>

        <section className="panel">
          <div className="panel-heading">
          <h2>Evidence Metadata</h2>
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
