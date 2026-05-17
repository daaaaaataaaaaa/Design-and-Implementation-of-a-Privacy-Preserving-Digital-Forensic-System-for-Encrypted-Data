import { ChangeEvent, FormEvent, ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import {
  Download,
  ExternalLink,
  File as FileIcon,
  FileArchive,
  FileCode,
  FileImage,
  FileSpreadsheet,
  FileText,
  FileType,
  Search,
  X
} from "lucide-react";
import { authHeader, DocumentDetail, DocumentSummary, jsonRequest, SE_API, SpreadsheetPreview } from "../lib/api";

type AuthResponse = {
  token: string;
  username: string;
};

type NoticeTone = "info" | "success" | "danger";
type PendingAction = "idle" | "auth" | "load" | "upload" | "search" | "open" | "delete" | "download" | "rebuild";
type VaultTab = "upload" | "search" | "documents";
type PreviewFile = {
  url: string;
  mimeType: string;
  fileName: string;
};
type DocumentBlob = {
  blob: Blob;
  fileName: string;
  mimeType: string;
};

type FileKind = "image" | "pdf" | "text" | "spreadsheet" | "document" | "archive" | "code" | "file";
const MAX_INLINE_TEXT_PREVIEW_BYTES = 10 * 1024 * 1024;

function generateDocumentId() {
  const bytes = new Uint8Array(16);
  if (globalThis.crypto?.getRandomValues) {
    globalThis.crypto.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index++) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  return `doc-${Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("")}`;
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatCreatedAt(value?: string) {
  if (!value) return "--";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

function getRelativeFileName(file: File) {
  return (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
}

function getFileExtension(fileName?: string) {
  const normalized = fileName?.trim() ?? "";
  const dotIndex = normalized.lastIndexOf(".");
  return dotIndex >= 0 ? normalized.slice(dotIndex + 1).toLowerCase() : "";
}

function getFileKind(document: Pick<DocumentSummary, "fileName" | "mediaType"> & { mimeType?: string }): FileKind {
  const extension = getFileExtension(document.fileName);
  const mimeType = document.mimeType?.toLowerCase() ?? "";
  const mediaType = document.mediaType?.toLowerCase() ?? "";

  if (mediaType === "image" || mimeType.startsWith("image/") || ["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg"].includes(extension)) {
    return "image";
  }
  if (mimeType === "application/pdf" || extension === "pdf") return "pdf";
  if (mediaType === "text" || mimeType.startsWith("text/")) return "text";
  if (["xls", "xlsx", "csv"].includes(extension) || mimeType.includes("spreadsheet") || mimeType.includes("excel")) return "spreadsheet";
  if (["doc", "docx", "ppt", "pptx"].includes(extension) || mimeType.includes("word") || mimeType.includes("presentation")) return "document";
  if (["zip", "rar", "7z", "tar", "gz"].includes(extension)) return "archive";
  if (["json", "xml", "html", "css", "js", "ts", "tsx", "java", "py"].includes(extension)) return "code";
  return "file";
}

function getFileLabel(document: Pick<DocumentSummary, "fileName" | "mediaType"> & { mimeType?: string }) {
  const extension = getFileExtension(document.fileName);
  const kind = getFileKind(document);
  if (extension) return extension.toUpperCase();
  return kind.toUpperCase();
}

function formatTextPreview(rawText: string, document: Pick<DocumentSummary, "fileName" | "mediaType"> & { mimeType?: string }) {
  const extension = getFileExtension(document.fileName);
  const mimeType = document.mimeType?.toLowerCase() ?? "";
  if (extension === "json" || mimeType.includes("json")) {
    try {
      return JSON.stringify(JSON.parse(rawText), null, 2);
    } catch {
      return rawText;
    }
  }
  return rawText;
}

function FileBadge({
  document,
  compact = false
}: {
  document: Pick<DocumentSummary, "fileName" | "mediaType"> & { mimeType?: string };
  compact?: boolean;
}) {
  const kind = getFileKind(document);
  const Icon = {
    archive: FileArchive,
    code: FileCode,
    document: FileType,
    file: FileIcon,
    image: FileImage,
    pdf: FileText,
    spreadsheet: FileSpreadsheet,
    text: FileText
  }[kind];

  return (
    <span className={`se-file-badge ${kind}${compact ? " compact" : ""}`} title={document.fileName || document.mediaType || "file"}>
      <Icon aria-hidden="true" size={compact ? 18 : 32} strokeWidth={1.8} />
      <span>{getFileLabel(document)}</span>
    </span>
  );
}

async function readResponseError(response: Response) {
  const text = await response.text();
  if (!text) return `${response.status} ${response.statusText}`;
  try {
    const parsed = JSON.parse(text) as { message?: string; error?: string };
    return parsed.message || parsed.error || text;
  } catch {
    return text;
  }
}

function parseDownloadFileName(header: string | null, fallback: string) {
  if (!header) return fallback;
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (utf8Match?.[1]) return decodeURIComponent(utf8Match[1].replace(/"/g, ""));
  const quotedMatch = /filename="([^"]+)"/i.exec(header);
  if (quotedMatch?.[1]) return quotedMatch[1];
  const plainMatch = /filename=([^;]+)/i.exec(header);
  return plainMatch?.[1]?.trim() || fallback;
}

function countKeywordMatches(value: string, keyword: string) {
  const normalizedKeyword = keyword.trim().toLowerCase();
  if (!normalizedKeyword) return 0;
  const lowerValue = value.toLowerCase();
  let cursor = 0;
  let count = 0;

  while (cursor < value.length) {
    const matchIndex = lowerValue.indexOf(normalizedKeyword, cursor);
    if (matchIndex < 0) break;
    count++;
    cursor = matchIndex + normalizedKeyword.length;
  }

  return count;
}

function highlightMatches(
  value: string,
  keyword: string,
  options: { activeIndex?: number; startIndex?: number; trackMatches?: boolean } = {}
): ReactNode {
  const normalizedKeyword = keyword.trim();
  if (!normalizedKeyword) return value;

  const lowerValue = value.toLowerCase();
  const lowerKeyword = normalizedKeyword.toLowerCase();
  const parts: ReactNode[] = [];
  let cursor = 0;
  let localMatchIndex = 0;

  while (cursor < value.length) {
    const matchIndex = lowerValue.indexOf(lowerKeyword, cursor);
    if (matchIndex < 0) {
      parts.push(value.slice(cursor));
      break;
    }
    if (matchIndex > cursor) {
      parts.push(value.slice(cursor, matchIndex));
    }
    const matchText = value.slice(matchIndex, matchIndex + normalizedKeyword.length);
    const globalMatchIndex = (options.startIndex ?? 0) + localMatchIndex;
    const active = options.activeIndex === globalMatchIndex;
    parts.push(
      <mark
        className={`se-search-hit${active ? " active" : ""}`}
        data-preview-match-index={options.trackMatches ? globalMatchIndex : undefined}
        key={`${matchIndex}-${parts.length}`}
      >
        {matchText}
      </mark>
    );
    cursor = matchIndex + normalizedKeyword.length;
    localMatchIndex++;
  }

  return parts.length ? parts : value;
}

function countPreviewMatches(detail: DocumentDetail, keyword: string) {
  const documentForPreview = { ...detail, mimeType: detail.mimeType };
  const kind = getFileKind(documentForPreview);
  if (kind === "spreadsheet" && detail.spreadsheetPreview?.sheets.length) {
    return detail.spreadsheetPreview.sheets.reduce((sheetTotal, sheet) => {
      return sheetTotal + sheet.rows.reduce((rowTotal, row) => {
        return rowTotal + row.reduce((cellTotal, cell) => cellTotal + countKeywordMatches(cell ?? "", keyword), 0);
      }, 0);
    }, 0);
  }
  if (kind === "image" || kind === "pdf") {
    return 0;
  }
  return countKeywordMatches(detail.plaintextPreview?.trim() ?? "", keyword);
}

function spreadsheetColumnLabel(index: number) {
  let label = "";
  let cursor = index + 1;
  while (cursor > 0) {
    const remainder = (cursor - 1) % 26;
    label = String.fromCharCode(65 + remainder) + label;
    cursor = Math.floor((cursor - 1) / 26);
  }
  return label;
}

function SpreadsheetPreviewContent({
  preview,
  highlightKeyword,
  activeMatchIndex
}: {
  preview: SpreadsheetPreview;
  highlightKeyword: string;
  activeMatchIndex: number;
}) {
  let matchOffset = 0;

  return (
    <div className="se-spreadsheet-preview">
      {preview.sheets.map((sheet, sheetIndex) => {
        const columnCount = Math.max(sheet.columnCount, ...sheet.rows.map((row) => row.length), 1);
        return (
          <section className="se-sheet-preview" key={`${sheet.name}-${sheetIndex}`}>
            <div className="se-sheet-header">
              <strong>{sheet.name || `Sheet ${sheetIndex + 1}`}</strong>
              <span>
                {sheet.rowCount} rows
                {sheet.truncated ? " · preview truncated" : ""}
              </span>
            </div>
            <div className="se-sheet-table-wrap">
              <table className="se-sheet-table">
                <thead>
                  <tr>
                    <th className="se-sheet-corner"></th>
                    {Array.from({ length: columnCount }, (_, columnIndex) => (
                      <th key={columnIndex}>{spreadsheetColumnLabel(columnIndex)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {sheet.rows.map((row, rowIndex) => (
                    <tr key={rowIndex}>
                      <th>{rowIndex + 1}</th>
                      {Array.from({ length: columnCount }, (_, columnIndex) => {
                        const value = row[columnIndex] ?? "";
                        const startIndex = matchOffset;
                        matchOffset += countKeywordMatches(value, highlightKeyword);
                        return (
                          <td key={columnIndex}>
                            {highlightMatches(value, highlightKeyword, {
                              activeIndex: activeMatchIndex,
                              startIndex,
                              trackMatches: true
                            })}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        );
      })}
    </div>
  );
}

function PreviewContent({
  detail,
  file,
  highlightKeyword,
  activeMatchIndex
}: {
  detail: DocumentDetail;
  file: PreviewFile | null;
  highlightKeyword: string;
  activeMatchIndex: number;
}) {
  const documentForPreview = { ...detail, mimeType: file?.mimeType ?? detail.mimeType };
  const kind = getFileKind(documentForPreview);
  const textPreview = detail.plaintextPreview?.trim();

  if (file && kind === "image") {
    return (
      <div className="se-preview-viewer image">
        <img className="se-preview-image" src={file.url} alt={file.fileName || detail.fileName || detail.docId} />
      </div>
    );
  }

  if (file && kind === "pdf") {
    return (
      <div className="se-preview-viewer">
        <iframe className="se-preview-frame" title={file.fileName || detail.fileName || detail.docId} src={file.url} />
      </div>
    );
  }

  if (kind === "spreadsheet" && detail.spreadsheetPreview?.sheets.length) {
    return (
      <SpreadsheetPreviewContent
        preview={detail.spreadsheetPreview}
        highlightKeyword={highlightKeyword}
        activeMatchIndex={activeMatchIndex}
      />
    );
  }

  if (textPreview) {
    return (
      <pre>
        {highlightMatches(textPreview, highlightKeyword, {
          activeIndex: activeMatchIndex,
          trackMatches: true
        })}
      </pre>
    );
  }

  return (
    <div className="se-preview-placeholder">
      <FileBadge document={documentForPreview} />
      <span>Inline preview is unavailable for this file type. Open or download the decrypted file to inspect it.</span>
    </div>
  );
}

export function EncryptedVault() {
  const [token, setToken] = useState(localStorage.getItem("se_token") ?? "");
  const [username, setUsername] = useState(localStorage.getItem("se_user") ?? "demo");
  const [password, setPassword] = useState("demo123");
  const [activeTab, setActiveTab] = useState<VaultTab>("upload");
  const [documents, setDocuments] = useState<DocumentSummary[]>([]);
  const [searchResults, setSearchResults] = useState<DocumentSummary[]>([]);
  const [hasSearched, setHasSearched] = useState(false);
  const [selectedDetail, setSelectedDetail] = useState<DocumentDetail | null>(null);
  const [previewFile, setPreviewFile] = useState<PreviewFile | null>(null);
  const [previewFindKeyword, setPreviewFindKeyword] = useState("");
  const [activePreviewMatchIndex, setActivePreviewMatchIndex] = useState(0);
  const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());
  const [docId, setDocId] = useState(generateDocumentId);
  const [description, setDescription] = useState("");
  const [text, setText] = useState("");
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [keyword, setKeyword] = useState("");
  const [lastSearchKeyword, setLastSearchKeyword] = useState("");
  const [actionDocId, setActionDocId] = useState("");
  const [message, setMessage] = useState("Please login or register before using the encrypted vault.");
  const [noticeTone, setNoticeTone] = useState<NoticeTone>("info");
  const [pendingAction, setPendingAction] = useState<PendingAction>("idle");
  const [importMenuOpen, setImportMenuOpen] = useState(false);

  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const folderInputRef = useRef<HTMLInputElement | null>(null);
  const busy = pendingAction !== "idle";
  const loggedIn = Boolean(token);
  const noticeClass = noticeTone === "info" ? "se-status" : `se-status ${noticeTone}`;
  const activeHighlightKeyword = hasSearched ? lastSearchKeyword : "";
  const previewMatchCount = useMemo(() => {
    return selectedDetail ? countPreviewMatches(selectedDetail, previewFindKeyword) : 0;
  }, [selectedDetail, previewFindKeyword]);
  const currentPreviewMatch = previewMatchCount ? Math.min(activePreviewMatchIndex, previewMatchCount - 1) + 1 : 0;

  const selectedFileLabel = useMemo(() => {
    if (selectedFiles.length === 0) return "No file or folder selected";
    if (selectedFiles.length === 1) return getRelativeFileName(selectedFiles[0]);
    return `${selectedFiles.length} files selected`;
  }, [selectedFiles]);

  const documentIds = useMemo(() => documents.map((document) => document.docId), [documents]);
  const selectedVisibleCount = useMemo(
    () => documentIds.filter((documentId) => selectedDocIds.has(documentId)).length,
    [documentIds, selectedDocIds]
  );
  const allDocumentsSelected = documents.length > 0 && selectedVisibleCount === documents.length;

  function showMessage(nextMessage: string, tone: NoticeTone = "info") {
    setMessage(nextMessage);
    setNoticeTone(tone);
  }

  function describeError(error: unknown) {
    return error instanceof Error ? error.message : "Unknown error";
  }

  function normalizeSessionError(reason: string) {
    if (/invalid bearer|unauthorized|401/i.test(reason)) {
      logout("Login session expired. Please login again.");
      return "Login session expired. Please login again.";
    }
    return reason;
  }

  function requireLogin(action: string) {
    if (token) return true;
    showMessage(`Please login before ${action}. API: ${SE_API}`, "danger");
    return false;
  }

  function closePreview() {
    setSelectedDetail(null);
    setPreviewFile(null);
    setPreviewFindKeyword("");
    setActivePreviewMatchIndex(0);
  }

  function logout(nextMessage = "Logged out.") {
    setToken("");
    setDocuments([]);
    setSearchResults([]);
    setHasSearched(false);
    setLastSearchKeyword("");
    closePreview();
    setSelectedDocIds(new Set());
    setActionDocId("");
    localStorage.removeItem("se_token");
    showMessage(nextMessage, "info");
  }

  async function authenticate(mode: "login" | "register") {
    if (!username.trim() || !password.trim()) {
      showMessage("Username and password are required.", "danger");
      return;
    }

    setPendingAction("auth");
    showMessage(mode === "login" ? "Logging in..." : "Registering user and key material...", "info");
    try {
      const response = await jsonRequest<AuthResponse>(`${SE_API}/api/se/auth/${mode}`, {
        method: "POST",
        body: JSON.stringify({ username: username.trim(), password })
      });
      setToken(response.token);
      setUsername(response.username);
      localStorage.setItem("se_token", response.token);
      localStorage.setItem("se_user", response.username);
      showMessage(mode === "login" ? "Login successful." : "Registration successful.", "success");
      await loadDocuments(response.token, true);
    } catch (error) {
      showMessage(`${mode === "login" ? "Login" : "Register"} failed: ${describeError(error)}`, "danger");
    } finally {
      setPendingAction("idle");
    }
  }

  async function loadDocuments(nextToken = token, quiet = false) {
    if (!nextToken) {
      showMessage("Please login before refreshing documents.", "danger");
      return;
    }

    if (!quiet) {
      setPendingAction("load");
      showMessage("Refreshing document list...", "info");
    }
    try {
      const response = await jsonRequest<DocumentSummary[]>(`${SE_API}/api/se/documents`, {
        headers: authHeader(nextToken)
      });
      setDocuments(response);
      if (!quiet) showMessage(`Loaded ${response.length} document(s).`, "success");
    } catch (error) {
      showMessage(`Refresh failed: ${normalizeSessionError(describeError(error))}`, "danger");
    } finally {
      if (!quiet) setPendingAction("idle");
    }
  }

  function handleFileSelection(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.currentTarget.files ?? []);
    setSelectedFiles(files);
    setImportMenuOpen(false);
    event.currentTarget.value = "";
    showMessage(files.length ? `Selected ${files.length} file(s).` : "No file or folder selected.", "info");
  }

  function clearSelection() {
    setSelectedFiles([]);
    showMessage("File selection cleared.", "info");
  }

  async function uploadOneDocument(targetDocId: string, targetDescription: string, plainText: string, file?: File) {
    const form = new FormData();
    form.set("docId", targetDocId);
    form.set("description", targetDescription);
    if (file) {
      form.set("file", file, file.name);
    } else {
      form.set("text", plainText);
    }
    return jsonRequest<DocumentSummary>(`${SE_API}/api/se/documents/upload`, {
      method: "POST",
      headers: authHeader(token),
      body: form
    });
  }

  async function uploadDocument(event: FormEvent) {
    event.preventDefault();
    if (!requireLogin("uploading documents")) return;
    if (selectedFiles.length === 0 && !text.trim()) {
      showMessage("Please enter plain text content or choose files/folder.", "danger");
      return;
    }
    if (selectedFiles.length === 0 && !docId.trim()) {
      showMessage("Document ID is required for plain text upload.", "danger");
      return;
    }

    setPendingAction("upload");
    try {
      if (selectedFiles.length === 0) {
        showMessage("Uploading text content...", "info");
        const saved = await uploadOneDocument(docId.trim(), description, text);
        showMessage(`Upload complete: ${saved.docId}`, "success");
      } else {
        let successCount = 0;
        const failures: string[] = [];
        for (let index = 0; index < selectedFiles.length; index++) {
          const file = selectedFiles[index];
          showMessage(`Uploading (${index + 1}/${selectedFiles.length}): ${getRelativeFileName(file)}`, "info");
          try {
            await uploadOneDocument(generateDocumentId(), description, "", file);
            successCount++;
          } catch (error) {
            failures.push(`${getRelativeFileName(file)}: ${describeError(error)}`);
          }
        }
        if (failures.length) {
          showMessage(`Batch upload finished. Success: ${successCount}; Failed: ${failures.length}. ${failures.join(" | ")}`, "danger");
        } else {
          showMessage(`Batch upload finished. Success: ${successCount}; Failed: 0.`, "success");
        }
      }
      setDocId(generateDocumentId());
      setDescription("");
      setText("");
      setSelectedFiles([]);
      await loadDocuments(token, true);
    } catch (error) {
      showMessage(`Upload failed: ${normalizeSessionError(describeError(error))}`, "danger");
    } finally {
      setPendingAction("idle");
    }
  }

  async function searchDocuments() {
    if (!requireLogin("searching documents")) return;
    const normalizedKeyword = keyword.trim().toLowerCase();
    if (!normalizedKeyword) {
      showMessage("Please enter a keyword.", "danger");
      return;
    }

    setPendingAction("search");
    showMessage(`Searching for "${normalizedKeyword}"...`, "info");
    try {
      const encryptedMatches = await jsonRequest<DocumentSummary[]>(
        `${SE_API}/api/se/documents/search?keyword=${encodeURIComponent(normalizedKeyword)}`,
        { headers: authHeader(token) }
      );
      const latestDocuments = await jsonRequest<DocumentSummary[]>(`${SE_API}/api/se/documents`, {
        headers: authHeader(token)
      });
      setDocuments(latestDocuments);

      const fallbackMatches = latestDocuments.filter((document) => {
        return document.docId.toLowerCase().includes(normalizedKeyword)
          || document.fileName.toLowerCase().includes(normalizedKeyword);
      });
      const merged = new Map<string, DocumentSummary>();
      for (const document of encryptedMatches) merged.set(document.docId, document);
      for (const document of fallbackMatches) merged.set(document.docId, document);
      const results = Array.from(merged.values());
      setSearchResults(results);
      setHasSearched(true);
      setLastSearchKeyword(normalizedKeyword);
      closePreview();
      showMessage(`Found ${results.length} document(s).`, "success");
    } catch (error) {
      showMessage(`Search failed: ${normalizeSessionError(describeError(error))}`, "danger");
    } finally {
      setPendingAction("idle");
    }
  }

  async function fetchDocumentBlob(docIdToFetch: string, fallbackName: string, signal?: AbortSignal): Promise<DocumentBlob> {
    const response = await fetch(`${SE_API}/api/se/documents/${encodeURIComponent(docIdToFetch)}/download`, {
      headers: authHeader(token),
      signal
    });
    if (!response.ok) {
      throw new Error(await readResponseError(response));
    }
    const mimeType = response.headers.get("Content-Type") || "application/octet-stream";
    const rawBlob = await response.blob();
    const blob = rawBlob.type ? rawBlob : new Blob([rawBlob], { type: mimeType });
    return {
      blob,
      fileName: parseDownloadFileName(response.headers.get("Content-Disposition"), fallbackName),
      mimeType: blob.type || mimeType
    };
  }

  async function openDocument(id: string) {
    if (!requireLogin("opening documents")) return;
    setPendingAction("open");
    showMessage(`Opening ${id}...`, "info");
    try {
      const detail = await jsonRequest<DocumentDetail>(`${SE_API}/api/se/documents/${encodeURIComponent(id)}`, {
        headers: authHeader(token)
      });
      const file = await fetchDocumentBlob(detail.docId, detail.fileName || detail.docId);
      const url = URL.createObjectURL(file.blob);
      const nextDetail: DocumentDetail = {
        ...detail,
        fileName: detail.fileName || file.fileName,
        mimeType: detail.mimeType || file.mimeType
      };
      if (getFileKind(nextDetail) === "text" && file.blob.size <= MAX_INLINE_TEXT_PREVIEW_BYTES) {
        nextDetail.plaintextPreview = formatTextPreview(await file.blob.text(), nextDetail);
      }
      setSelectedDetail(nextDetail);
      setPreviewFile({ url, mimeType: file.mimeType, fileName: file.fileName });
      setPreviewFindKeyword(activeHighlightKeyword);
      setActivePreviewMatchIndex(0);
      setActionDocId(detail.docId);
      showMessage(`Opened ${file.fileName || detail.docId}.`, "success");
    } catch (error) {
      showMessage(`Open failed: ${normalizeSessionError(describeError(error))}`, "danger");
    } finally {
      setPendingAction("idle");
    }
  }

  function openPreviewFile() {
    if (!previewFile) return;
    window.open(previewFile.url, "_blank", "noopener,noreferrer");
  }

  function goToPreviousPreviewMatch() {
    if (previewMatchCount === 0) return;
    setActivePreviewMatchIndex((current) => (current - 1 + previewMatchCount) % previewMatchCount);
  }

  function goToNextPreviewMatch() {
    if (previewMatchCount === 0) return;
    setActivePreviewMatchIndex((current) => (current + 1) % previewMatchCount);
  }

  async function downloadPreviewFile() {
    if (!selectedDetail || !requireLogin("downloading documents")) return;
    setPendingAction("download");
    try {
      await downloadOne(selectedDetail.docId);
      showMessage(`Download started for ${selectedDetail.fileName || selectedDetail.docId}.`, "success");
    } catch (error) {
      showMessage(`Download failed: ${normalizeSessionError(describeError(error))}`, "danger");
    } finally {
      setPendingAction("idle");
    }
  }

  async function downloadOne(docIdToDownload: string) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 15_000);
    try {
      const fallbackName = documents.find((document) => document.docId === docIdToDownload)?.fileName
        || (selectedDetail?.docId === docIdToDownload ? selectedDetail.fileName : "")
        || docIdToDownload;
      const { blob, fileName } = await fetchDocumentBlob(docIdToDownload, fallbackName, controller.signal);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function resolveTargetDocIds() {
    if (selectedDocIds.size > 0) return Array.from(selectedDocIds);
    if (actionDocId.trim()) return [actionDocId.trim()];
    return [];
  }

  async function downloadDocuments() {
    if (!requireLogin("downloading documents")) return;
    const targetDocIds = resolveTargetDocIds();
    if (targetDocIds.length === 0) {
      showMessage("Please select a row or enter a Document ID.", "danger");
      return;
    }

    setPendingAction("download");
    try {
      for (let index = 0; index < targetDocIds.length; index++) {
        showMessage(`Downloading (${index + 1}/${targetDocIds.length}): ${targetDocIds[index]}`, "info");
        await downloadOne(targetDocIds[index]);
      }
      showMessage(`Download started for ${targetDocIds.length} document(s).`, "success");
    } catch (error) {
      showMessage(`Download failed: ${normalizeSessionError(describeError(error))}`, "danger");
    } finally {
      setPendingAction("idle");
    }
  }

  async function deleteDocuments() {
    if (!requireLogin("deleting documents")) return;
    const targetDocIds = resolveTargetDocIds();
    if (targetDocIds.length === 0) {
      showMessage("Please select a row or enter a Document ID.", "danger");
      return;
    }
    const confirmed = window.confirm(
      targetDocIds.length === 1 ? `Delete document ${targetDocIds[0]}?` : `Delete ${targetDocIds.length} selected documents?`
    );
    if (!confirmed) return;

    setPendingAction("delete");
    try {
      let successCount = 0;
      const failures: string[] = [];
      for (let index = 0; index < targetDocIds.length; index++) {
        const target = targetDocIds[index];
        showMessage(`Deleting (${index + 1}/${targetDocIds.length}): ${target}`, "info");
        try {
          await jsonRequest<{ deleted: boolean }>(`${SE_API}/api/se/documents/${encodeURIComponent(target)}`, {
            method: "DELETE",
            headers: authHeader(token)
          });
          successCount++;
        } catch (error) {
          failures.push(`${target}: ${describeError(error)}`);
        }
      }
      setSelectedDocIds(new Set());
      setActionDocId("");
      closePreview();
      await loadDocuments(token, true);
      showMessage(`Delete finished. Success: ${successCount}; Failed: ${failures.length}.`, failures.length ? "danger" : "success");
    } catch (error) {
      showMessage(`Delete failed: ${normalizeSessionError(describeError(error))}`, "danger");
    } finally {
      setPendingAction("idle");
    }
  }

  async function rebuildIndexes() {
    if (!requireLogin("rebuilding indexes")) return;
    const targetDocIds = resolveTargetDocIds();
    if (targetDocIds.length === 0) {
      showMessage("Please select a row or enter a Document ID.", "danger");
      return;
    }
    const confirmed = window.confirm(
      targetDocIds.length === 1 ? `Rebuild index for ${targetDocIds[0]}?` : `Rebuild indexes for ${targetDocIds.length} selected documents?`
    );
    if (!confirmed) return;

    setPendingAction("rebuild");
    try {
      let successCount = 0;
      const failures: string[] = [];
      for (let index = 0; index < targetDocIds.length; index++) {
        const target = targetDocIds[index];
        showMessage(`Rebuilding (${index + 1}/${targetDocIds.length}): ${target}`, "info");
        try {
          await jsonRequest<DocumentSummary>(`${SE_API}/api/se/documents/${encodeURIComponent(target)}/rebuild-index`, {
            method: "POST",
            headers: authHeader(token)
          });
          successCount++;
        } catch (error) {
          const reason = describeError(error);
          if (/invalid bearer|unauthorized|401/i.test(reason)) {
            throw new Error(normalizeSessionError(reason));
          }
          failures.push(`${target}: ${reason}`);
        }
      }
      await loadDocuments(token, true);
      const failureSummary = failures.length ? ` ${failures.join(" | ")}` : "";
      showMessage(`Rebuild finished. Success: ${successCount}; Failed: ${failures.length}.${failureSummary}`, failures.length ? "danger" : "success");
    } catch (error) {
      showMessage(`Rebuild failed: ${normalizeSessionError(describeError(error))}`, "danger");
    } finally {
      setPendingAction("idle");
    }
  }

  function applyDocumentSelection(nextSelection: Set<string>) {
    setSelectedDocIds(nextSelection);
    if (nextSelection.size === 0) {
      setActionDocId("");
    } else if (nextSelection.size === 1) {
      setActionDocId(Array.from(nextSelection)[0]);
    } else {
      setActionDocId(`${nextSelection.size} selected`);
    }
  }

  function selectAllDocuments() {
    const nextSelection = new Set(documentIds);
    applyDocumentSelection(nextSelection);
    showMessage(`Selected ${nextSelection.size} document(s).`, "info");
  }

  function clearDocumentSelection() {
    applyDocumentSelection(new Set());
    showMessage("Cleared document selection.", "info");
  }

  function switchTab(nextTab: VaultTab) {
    setActiveTab(nextTab);
    closePreview();
  }

  function toggleAllDocuments() {
    if (allDocumentsSelected) {
      clearDocumentSelection();
    } else {
      selectAllDocuments();
    }
  }

  function selectSingleDocument(document: DocumentSummary) {
    applyDocumentSelection(new Set([document.docId]));
  }

  function toggleDocumentSelection(document: DocumentSummary) {
    const next = new Set(selectedDocIds);
    if (next.has(document.docId)) {
      next.delete(document.docId);
    } else {
      next.add(document.docId);
    }
    applyDocumentSelection(next);
  }

  useEffect(() => {
    if (token) {
      loadDocuments(token, true);
    }
  }, [token]);

  useEffect(() => {
    return () => {
      if (previewFile?.url) {
        URL.revokeObjectURL(previewFile.url);
      }
    };
  }, [previewFile?.url]);

  useEffect(() => {
    if (!selectedDetail) return;
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        closePreview();
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [selectedDetail]);

  useEffect(() => {
    setActivePreviewMatchIndex(0);
  }, [previewFindKeyword, selectedDetail?.docId]);

  useEffect(() => {
    if (previewMatchCount === 0) {
      setActivePreviewMatchIndex(0);
    } else if (activePreviewMatchIndex >= previewMatchCount) {
      setActivePreviewMatchIndex(previewMatchCount - 1);
    }
  }, [activePreviewMatchIndex, previewMatchCount]);

  useEffect(() => {
    if (!selectedDetail || previewMatchCount === 0) return;
    window.requestAnimationFrame(() => {
      const activeMark = document.querySelector(`[data-preview-match-index="${activePreviewMatchIndex}"]`);
      activeMark?.scrollIntoView({ block: "center", inline: "center", behavior: "smooth" });
    });
  }, [activePreviewMatchIndex, previewMatchCount, selectedDetail]);

  return (
    <section className="page vault-workbench">
      <div className="page-header">
        <div>
          <p className="eyebrow">Searchable encryption facade</p>
          <h1>加密证据库</h1>
        </div>
      </div>

      <div className="se-module">
        <div className="se-session-row">
          {loggedIn ? (
            <>
              <span>Current User: {username}</span>
              <button className="se-button danger" type="button" onClick={() => logout()} disabled={busy}>
                Logout
              </button>
            </>
          ) : (
            <form className="se-login-form" onSubmit={(event) => { event.preventDefault(); authenticate("login"); }}>
              <label>
                Username
                <input value={username} onChange={(event) => setUsername(event.target.value)} />
              </label>
              <label>
                Password
                <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
              </label>
              <button className="se-button primary" type="submit" disabled={pendingAction === "auth"}>
                {pendingAction === "auth" ? "Working..." : "Login"}
              </button>
              <button className="se-button secondary" type="button" onClick={() => authenticate("register")} disabled={pendingAction === "auth"}>
                Register
              </button>
            </form>
          )}
        </div>

        <nav className="se-tabs" aria-label="Searchable encryption sections">
          {[
            ["upload", "Upload"],
            ["search", "Search"],
            ["documents", "Documents"]
          ].map(([key, label]) => (
            <button
              key={key}
              type="button"
              className={activeTab === key ? "active" : ""}
              onClick={() => switchTab(key as VaultTab)}
            >
              {label}
            </button>
          ))}
        </nav>

        <div className={noticeClass}>{message}</div>

        <div className="se-tab-body">
          {activeTab === "upload" && (
            <form className="se-upload-layout" onSubmit={uploadDocument}>
              <div className="se-form-grid">
                <label>Document ID:</label>
                <input value={docId} onChange={(event) => setDocId(event.target.value)} disabled={selectedFiles.length > 0} />

                <label>Selected Files:</label>
                <div className="se-inline-value">{selectedFileLabel}</div>

                <label>Import Actions:</label>
                <div className="se-file-actions">
                  <button className="se-button primary" type="button" onClick={() => setImportMenuOpen((open) => !open)} disabled={busy}>
                    Import
                  </button>
                  <button className="se-button secondary" type="button" onClick={clearSelection} disabled={busy}>
                    Clear Selection
                  </button>
                  {importMenuOpen && (
                    <div className="se-import-menu">
                      <button type="button" onClick={() => fileInputRef.current?.click()}>Import Files</button>
                      <button type="button" onClick={() => folderInputRef.current?.click()}>Import Folder</button>
                    </div>
                  )}
                  <input ref={fileInputRef} className="se-hidden-input" type="file" multiple onChange={handleFileSelection} />
                  <input
                    ref={(node) => {
                      folderInputRef.current = node;
                      node?.setAttribute("webkitdirectory", "true");
                      node?.setAttribute("directory", "true");
                    }}
                    className="se-hidden-input"
                    type="file"
                    multiple
                    onChange={handleFileSelection}
                  />
                </div>
              </div>

              <label className="se-description-row">
                <span>Description:</span>
                <input value={description} onChange={(event) => setDescription(event.target.value)} />
              </label>

              <section className="se-section-box">
                <h2>Content (plain text)</h2>
                <p>Paste plain text here when uploading as a text document (without selecting files).</p>
                <textarea value={text} onChange={(event) => setText(event.target.value)} disabled={selectedFiles.length > 0} />
              </section>

              <div className="se-footer-actions">
                <button className="se-button primary" type="submit" disabled={pendingAction === "upload"}>
                  {pendingAction === "upload" ? "Uploading..." : "Upload Document"}
                </button>
              </div>
            </form>
          )}

          {activeTab === "search" && (
            <div className="se-stack">
              <section className="se-section-box">
                <h2>Keyword Search</h2>
                <p>Search by keyword prefix, file name, or extracted document text.</p>
                <form className="se-search-row" onSubmit={(event) => { event.preventDefault(); searchDocuments(); }}>
                  <label>Search Keyword</label>
                  <input
                    value={keyword}
                    onChange={(event) => {
                      setKeyword(event.target.value);
                      setHasSearched(false);
                      setLastSearchKeyword("");
                      setSearchResults([]);
                      closePreview();
                    }}
                  />
                  <button className="se-button primary" type="submit" disabled={pendingAction === "search"}>
                    {pendingAction === "search" ? "Searching..." : "Search"}
                  </button>
                </form>
              </section>

              <section className="se-section-box">
                <h2>Search Results</h2>
                <p>Matched documents and text previews are shown here.</p>
                <div className="se-result-list">
                  {hasSearched && <div className="se-result-summary">Found {searchResults.length} documents.</div>}
                  {hasSearched && searchResults.length === 0 ? (
                    <div className="se-empty">No matched documents.</div>
                  ) : hasSearched ? (
                    searchResults.map((document) => (
                      <article className="se-result-card" key={document.docId}>
                        <button className="se-file-preview-button" type="button" onClick={() => openDocument(document.docId)}>
                          <FileBadge document={document} />
                        </button>
                  <div>
                    <strong>DocID: {document.docId}</strong>
                    <span>File: {highlightMatches(document.fileName, activeHighlightKeyword)}</span>
                    <span>Type: {document.mediaType}</span>
                    <span>Size: {formatBytes(document.fileSize)}</span>
                          <button className="se-link-button" type="button" onClick={() => openDocument(document.docId)}>
                            Open Preview
                          </button>
                        </div>
                      </article>
                    ))
                  ) : null}
                </div>
              </section>
            </div>
          )}

          {activeTab === "documents" && (
            <div className="se-stack">
              <section className="se-section-box">
                <h2>Document Actions</h2>
                <p>Select one or more rows below or enter a Document ID to manage encrypted files.</p>
                <div className="se-document-actions">
                  <button className="se-button secondary" type="button" onClick={() => loadDocuments()} disabled={pendingAction === "load"}>
                    Refresh List
                  </button>
                  <label>Doc ID:</label>
                  <input value={actionDocId} onChange={(event) => setActionDocId(event.target.value)} />
                  <button className="se-button primary" type="button" onClick={downloadDocuments} disabled={pendingAction === "download"}>
                    Download
                  </button>
                  <button className="se-button danger" type="button" onClick={deleteDocuments} disabled={pendingAction === "delete"}>
                    Delete
                  </button>
                  <button className="se-button secondary" type="button" onClick={rebuildIndexes} disabled={pendingAction === "rebuild"}>
                    Rebuild Index
                  </button>
                </div>
              </section>

              <section className="se-section-box">
                <h2>My Documents</h2>
                <p>Browse uploaded documents and use the actions above to manage one or multiple files.</p>
                <div className="se-selection-toolbar">
                  <div className="se-selection-actions">
                    <button className="se-button secondary" type="button" onClick={selectAllDocuments} disabled={busy || documents.length === 0}>
                      Select All
                    </button>
                    <button className="se-button secondary" type="button" onClick={clearDocumentSelection} disabled={busy || selectedDocIds.size === 0}>
                      Clear Selection
                    </button>
                  </div>
                  <span>{selectedDocIds.size}/{documents.length} selected</span>
                </div>
                <div className="se-table-wrap">
                  <table className="se-document-table">
                    <thead>
                      <tr>
                        <th>
                          <input
                            aria-label={allDocumentsSelected ? "Clear all document selections" : "Select all documents"}
                            type="checkbox"
                            checked={allDocumentsSelected}
                            disabled={documents.length === 0}
                            onChange={toggleAllDocuments}
                          />
                        </th>
                        <th>Document ID</th>
                        <th>File Name</th>
                        <th>Type</th>
                        <th>Size</th>
                        <th>Keywords</th>
                        <th>Created At</th>
                      </tr>
                    </thead>
                    <tbody>
                      {documents.map((document) => (
                        <tr key={document.docId} className={selectedDocIds.has(document.docId) ? "selected" : ""} onClick={() => selectSingleDocument(document)}>
                          <td>
                            <input
                              type="checkbox"
                              checked={selectedDocIds.has(document.docId)}
                              onClick={(event) => event.stopPropagation()}
                              onChange={() => toggleDocumentSelection(document)}
                            />
                          </td>
                          <td>{document.docId}</td>
                          <td>
                            <span className="se-file-name-cell">
                        <FileBadge document={document} compact />
                        <button className="se-link-button" type="button" onClick={(event) => { event.stopPropagation(); openDocument(document.docId); }}>
                          {highlightMatches(document.fileName, activeHighlightKeyword)}
                        </button>
                      </span>
                          </td>
                          <td>{document.mediaType}</td>
                          <td>{formatBytes(document.fileSize)}</td>
                          <td>{document.keywordCount}</td>
                          <td>{formatCreatedAt(document.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            </div>
          )}
        </div>

        {selectedDetail && createPortal((
          <div className="se-preview-overlay" role="presentation" onClick={closePreview}>
            <aside
              className="se-preview-panel"
              role="dialog"
              aria-modal="true"
              aria-label={`Preview ${selectedDetail.fileName || selectedDetail.docId}`}
              onClick={(event) => event.stopPropagation()}
            >
              <div className="se-preview-header">
                <div className="se-preview-title">
                  <FileBadge document={{ ...selectedDetail, mimeType: previewFile?.mimeType ?? selectedDetail.mimeType }} compact />
                  <strong>Preview: {highlightMatches(selectedDetail.fileName || selectedDetail.docId, activeHighlightKeyword)}</strong>
                </div>
                <div className="se-preview-actions">
                  <button className="se-icon-button" type="button" onClick={openPreviewFile} disabled={!previewFile} title="Open file" aria-label="Open file">
                    <ExternalLink aria-hidden="true" size={18} />
                  </button>
                  <button className="se-icon-button" type="button" onClick={downloadPreviewFile} disabled={pendingAction === "download"} title="Download" aria-label="Download">
                    <Download aria-hidden="true" size={18} />
                  </button>
                  <button className="se-icon-button" type="button" onClick={closePreview} title="Close preview" aria-label="Close preview">
                    <X aria-hidden="true" size={18} />
                  </button>
                </div>
              </div>
              <div className="se-preview-findbar">
                <label>
                  <Search aria-hidden="true" size={16} />
                  Find
                </label>
                <input
                  value={previewFindKeyword}
                  onChange={(event) => setPreviewFindKeyword(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      event.preventDefault();
                      if (event.shiftKey) {
                        goToPreviousPreviewMatch();
                      } else {
                        goToNextPreviewMatch();
                      }
                    }
                  }}
                  placeholder="Search in preview"
                />
                <span>
                  {previewFindKeyword.trim()
                    ? `${currentPreviewMatch}/${previewMatchCount} matches`
                    : "Enter a keyword"}
                </span>
                <button className="se-button secondary" type="button" onClick={goToPreviousPreviewMatch} disabled={previewMatchCount === 0}>
                  Previous
                </button>
                <button className="se-button secondary" type="button" onClick={goToNextPreviewMatch} disabled={previewMatchCount === 0}>
                  Next
                </button>
              </div>
            <dl>
              <dt>Doc ID</dt><dd>{selectedDetail.docId}</dd>
              <dt>File</dt><dd>{highlightMatches(selectedDetail.fileName, activeHighlightKeyword)}</dd>
              <dt>Media</dt><dd>{selectedDetail.mediaType}</dd>
              <dt>Size</dt><dd>{formatBytes(selectedDetail.fileSize)}</dd>
            </dl>
            <PreviewContent
              detail={selectedDetail}
              file={previewFile}
              highlightKeyword={previewFindKeyword}
              activeMatchIndex={activePreviewMatchIndex}
            />
            </aside>
          </div>
        ), document.body)}
      </div>
    </section>
  );
}
