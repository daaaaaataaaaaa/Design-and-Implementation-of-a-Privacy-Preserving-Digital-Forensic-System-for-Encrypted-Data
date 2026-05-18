function defaultApiUrl(port: number) {
  return `${window.location.protocol}//${window.location.hostname || "localhost"}:${port}`;
}

export const ML_API = import.meta.env.VITE_ML_API_URL ?? defaultApiUrl(8001);
export const SE_API = import.meta.env.VITE_SE_API_URL ?? defaultApiUrl(8082);

export type MlMetadata = {
  service: string;
  model_available: boolean;
  feature_count: number;
  explainability_assets: Record<string, string[]>;
  report_counts: Record<string, number>;
};

export type PredictionResult = {
  prediction: string | number;
  probability?: Record<string, number>;
  filled_feature_count: number;
  missing_feature_count: number;
  evidence_hash: string;
};

export type EvidenceReport = {
  Evidence_ID?: string;
  Timestamp?: string;
  Searchable_Keywords?: string[];
  Blockchain_SHA256_Hash?: string;
  Forensic_Metrics?: Record<string, unknown>;
  [key: string]: unknown;
};

export type DocumentSummary = {
  docId: string;
  fileName: string;
  mediaType: string;
  fileSize: number;
  keywordCount: number;
  createdAt?: string;
  matchCount?: number;
  matchedKeywords?: string[];
};

export type SpreadsheetPreview = {
  sheets: SpreadsheetSheetPreview[];
  truncated: boolean;
};

export type SpreadsheetSheetPreview = {
  name: string;
  rows: string[][];
  rowCount: number;
  columnCount: number;
  truncated: boolean;
};

export type DocumentDetail = DocumentSummary & {
  mimeType?: string;
  plaintextPreview?: string;
  ciphertextBase64?: string;
  spreadsheetPreview?: SpreadsheetPreview;
};

export async function jsonRequest<T>(url: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 15_000);

  try {
    const response = await fetch(url, {
      ...init,
      signal: init?.signal ?? controller.signal,
      headers: {
        ...(init?.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
        ...(init?.headers ?? {})
      }
    });

    if (!response.ok) {
      const text = await response.text();
      let reason = text;
      try {
        const parsed = JSON.parse(text) as { message?: string; error?: string };
        reason = parsed.message || parsed.error || text;
      } catch {
        // Keep the plain response body when it is not JSON.
      }
      throw new Error(reason || `${response.status} ${response.statusText}`);
    }

    return response.json() as Promise<T>;
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error(`请求超时：${url}`);
    }
    if (error instanceof TypeError) {
      throw new Error(`无法连接到服务：${url}`);
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
}

export function authHeader(token: string): Record<string, string> {
  return token ? { Authorization: `Bearer ${token}` } : {};
}
