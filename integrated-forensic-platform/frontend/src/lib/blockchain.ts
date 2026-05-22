export const evidenceRegistryAbi = [
  "function storeJSONEvidence(string _caseId,string _fileHash,string _evidenceName,string _description,string _fileName,string _attackType,string _sourceIp,string _targetUrl) public returns (uint256)",
  "function verifyEvidence(string _evidenceHash) public view returns (bool)",
  "function getRecord(uint256 _recordId) public view returns (tuple(string caseId,string evidenceHash,string evidenceName,address submitter,uint256 timestamp,string description,bool isActive,string fileName,string attackType,string sourceIp,string targetUrl))",
  "function getTotalRecords() public view returns (uint256)"
];

const contractAddressStorageKey = "evidence_registry_address";
const walletAccountStorageKey = "evidence_wallet_account";

export function getDefaultEvidenceRegistryAddress() {
  if (typeof window === "undefined") {
    return import.meta.env.VITE_DEFAULT_CONTRACT_ADDRESS ?? "";
  }
  return localStorage.getItem(contractAddressStorageKey) ?? import.meta.env.VITE_DEFAULT_CONTRACT_ADDRESS ?? "";
}

export function rememberEvidenceRegistryAddress(address: string) {
  if (typeof window === "undefined") return;
  const normalized = address.trim();
  if (normalized) {
    localStorage.setItem(contractAddressStorageKey, normalized);
  } else {
    localStorage.removeItem(contractAddressStorageKey);
  }
}

export function getRememberedWalletAccount() {
  if (typeof window === "undefined") {
    return "";
  }
  return sessionStorage.getItem(walletAccountStorageKey) ?? "";
}

export function rememberWalletAccount(account: string) {
  if (typeof window === "undefined") return;
  const normalized = account.trim();
  if (normalized) {
    sessionStorage.setItem(walletAccountStorageKey, normalized);
  } else {
    sessionStorage.removeItem(walletAccountStorageKey);
  }
}

export async function sha256Hex(file: File) {
  const buffer = await file.arrayBuffer();
  const hashBuffer = await crypto.subtle.digest("SHA-256", buffer);
  return Array.from(new Uint8Array(hashBuffer))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}
