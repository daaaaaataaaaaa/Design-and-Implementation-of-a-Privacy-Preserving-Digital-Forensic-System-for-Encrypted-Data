export const ML_API = import.meta.env.VITE_ML_API_URL ?? "http://localhost:8001";
export const SE_API = import.meta.env.VITE_SE_API_URL ?? "http://localhost:8082";

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
};

export type DocumentDetail = DocumentSummary & {
  mimeType?: string;
  plaintextPreview?: string;
  ciphertextBase64?: string;
};

export async function jsonRequest<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: {
      ...(init?.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
      ...(init?.headers ?? {})
    }
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `${response.status} ${response.statusText}`);
  }

  return response.json() as Promise<T>;
}

export function authHeader(token: string): Record<string, string> {
  return token ? { Authorization: `Bearer ${token}` } : {};
}
