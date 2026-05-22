import { ethers } from "ethers";
import { Blocks, Link, Loader2, SearchCheck, Upload } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import {
  evidenceRegistryAbi,
  getDefaultEvidenceRegistryAddress,
  getRememberedWalletAccount,
  rememberEvidenceRegistryAddress,
  rememberWalletAccount,
  sha256Hex
} from "../lib/blockchain";

type EthereumProvider = ethers.Eip1193Provider & {
  on?: (event: "accountsChanged", listener: (accounts: string[]) => void) => void;
  removeListener?: (event: "accountsChanged", listener: (accounts: string[]) => void) => void;
};

type EthereumWindow = Window & {
  ethereum?: EthereumProvider;
};

type NoticeTone = "info" | "success" | "danger";
type PendingAction = "store" | "verify" | "";

export function BlockchainEvidence() {
  const evidenceFileInputRef = useRef<HTMLInputElement>(null);
  const hashInputRef = useRef<HTMLTextAreaElement>(null);
  const [account, setAccount] = useState(getRememberedWalletAccount);
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
  const [statusTone, setStatusTone] = useState<NoticeTone>("info");
  const [pendingAction, setPendingAction] = useState<PendingAction>("");
  const [selectedFileName, setSelectedFileName] = useState("");

  function showStatus(message: string, tone: NoticeTone = "info") {
    setStatus(message);
    setStatusTone(tone);
  }

  function getBlockchainErrorMessage(error: unknown, fallback: string) {
    if (!(error instanceof Error)) {
      return fallback;
    }
    if (error.message.includes("BAD_DATA") || error.message.includes("could not decode result data")) {
      return "The contract at this address did not return a valid EvidenceRegistry response. Make sure the Contract Address is the deployed EvidenceRegistry contract, not a wallet/account address, and that MetaMask is on the matching Ganache network.";
    }
    return error.message;
  }

  function updateWalletAccount(nextAccount: string) {
    setAccount(nextAccount);
    rememberWalletAccount(nextAccount);
  }

  useEffect(() => {
    const ethereumProvider = (window as EthereumWindow).ethereum;
    if (!ethereumProvider) return;
    const walletProvider: EthereumProvider = ethereumProvider;

    let mounted = true;

    async function syncWalletAccount() {
      try {
        const accounts = (await walletProvider.request({ method: "eth_accounts" })) as string[];
        if (mounted) {
          updateWalletAccount(accounts[0] ?? "");
        }
      } catch {
        if (mounted) {
          updateWalletAccount("");
        }
      }
    }

    function handleAccountsChanged(accounts: string[]) {
      const nextAccount = accounts[0] ?? "";
      updateWalletAccount(nextAccount);
      showStatus(nextAccount ? "Wallet connected." : "Wallet disconnected.", nextAccount ? "success" : "info");
    }

    syncWalletAccount();
    walletProvider.on?.("accountsChanged", handleAccountsChanged);

    return () => {
      mounted = false;
      walletProvider.removeListener?.("accountsChanged", handleAccountsChanged);
    };
  }, []);

  async function getContract(withSigner = false) {
    const ethereum = (window as EthereumWindow).ethereum;
    if (!ethereum) {
      throw new Error("No Ethereum provider detected. Start Ganache and connect MetaMask.");
    }
    const normalizedContractAddress = contractAddress.trim();
    if (!ethers.isAddress(normalizedContractAddress)) {
      throw new Error("Enter a valid EvidenceRegistry contract address first.");
    }
    const provider = new ethers.BrowserProvider(ethereum);
    const deployedCode = await provider.getCode(normalizedContractAddress);
    if (deployedCode === "0x") {
      throw new Error(
        "No EvidenceRegistry contract was found at this address. Paste the deployed contract address, not your wallet account address, and check that MetaMask is on the same Ganache network."
      );
    }
    if (withSigner) {
      const signer = await provider.getSigner();
      return new ethers.Contract(normalizedContractAddress, evidenceRegistryAbi, signer);
    }
    return new ethers.Contract(normalizedContractAddress, evidenceRegistryAbi, provider);
  }

  async function connectWallet() {
    const ethereum = (window as EthereumWindow).ethereum;
    if (!ethereum) {
      showStatus("MetaMask or a browser wallet was not detected.", "danger");
      return;
    }
    try {
      const accounts = (await ethereum.request({ method: "eth_requestAccounts" })) as string[];
      updateWalletAccount(accounts[0] ?? "");
      showStatus(accounts[0] ? "Wallet connected." : "No wallet account selected.", accounts[0] ? "success" : "info");
    } catch (err) {
      showStatus(err instanceof Error ? err.message : "Failed to connect wallet.", "danger");
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
    if (account && contractAddress.trim().toLowerCase() === account.toLowerCase()) {
      return "This is the connected wallet account. Paste the deployed EvidenceRegistry contract address from Remix instead.";
    }
    if (!hash.trim()) {
      return "Select an evidence file first, or paste a 64-character SHA-256 hash.";
    }
    if (!/^[a-fA-F0-9]{64}$/.test(hash.trim())) {
      return "The SHA-256 hash must contain 64 hexadecimal characters.";
    }
    return "";
  }

  function focusHashInput() {
    window.setTimeout(() => hashInputRef.current?.focus(), 0);
  }

  async function storeEvidence() {
    showStatus("");
    const validationMessage = validateEvidenceInput();
    if (validationMessage) {
      showStatus(validationMessage, "danger");
      if (!hash.trim() || !/^[a-fA-F0-9]{64}$/.test(hash.trim())) {
        focusHashInput();
      }
      return;
    }
    setPendingAction("store");
    showStatus("Submitting evidence hash to the EvidenceRegistry contract...");
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
      showStatus(`Transaction submitted: ${tx.hash}`);
      await tx.wait();
      showStatus(`On-chain evidence anchoring completed: ${tx.hash}`, "success");
    } catch (err) {
      showStatus(getBlockchainErrorMessage(err, "Store transaction failed"), "danger");
    } finally {
      setPendingAction("");
    }
  }

  async function verifyEvidence() {
    showStatus("");
    const validationMessage = validateEvidenceInput();
    if (validationMessage) {
      showStatus(validationMessage, "danger");
      if (!hash.trim() || !/^[a-fA-F0-9]{64}$/.test(hash.trim())) {
        focusHashInput();
      }
      return;
    }
    setPendingAction("verify");
    showStatus("Checking this SHA-256 hash on chain...");
    try {
      const contract = await getContract(false);
      const exists = await contract.verifyEvidence(hash.trim());
      showStatus(
        exists
          ? "Verification passed: this hash already exists on-chain."
          : "Evidence hash not found on chain. Submit it first if this evidence should be notarized.",
        exists ? "success" : "info"
      );
    } catch (err) {
      showStatus(getBlockchainErrorMessage(err, "Verification failed"), "danger");
    } finally {
      setPendingAction("");
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
          <label>EvidenceRegistry Contract Address</label>
          <input
            value={contractAddress}
            onChange={(event) => updateContractAddress(event.target.value)}
            placeholder="Paste the deployed EvidenceRegistry address from Remix"
          />
          <p className="field-hint">
            Wallet account: {account ? `${account.slice(0, 6)}...${account.slice(-4)}` : "not connected"}. This field needs the contract address from Remix Deployed Contracts.
          </p>
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
        <textarea ref={hashInputRef} value={hash} onChange={(event) => setHash(event.target.value.trim())} placeholder="Select a file to calculate automatically, or paste a 64-character SHA-256 hash" />
          <div className="button-row">
            <button className="primary-action" type="button" onClick={storeEvidence} disabled={Boolean(pendingAction)}>
            {pendingAction === "store" ? <Loader2 className="spin" size={17} /> : <Upload size={17} />} {pendingAction === "store" ? "Submitting" : "Submit Evidence"}
            </button>
            <button className="secondary-action" type="button" onClick={verifyEvidence} disabled={Boolean(pendingAction)}>
            {pendingAction === "verify" ? <Loader2 className="spin" size={17} /> : <SearchCheck size={17} />} {pendingAction === "verify" ? "Checking" : "Check Hash"}
            </button>
          </div>
          {status && <div className={`notice ${statusTone}`}>{status}</div>}
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
