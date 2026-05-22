function defaultApiUrl(port: number) {
  return `${window.location.protocol}//${window.location.hostname || "localhost"}:${port}`;
}

export const ML_API = import.meta.env.VITE_ML_API_URL ?? defaultApiUrl(8001);
export const SE_API = import.meta.env.VITE_SE_API_URL ?? defaultApiUrl(8082);

const AUTH_TOKEN_KEY = "se_token";
const AUTH_USER_KEY = "se_user";

export type AuthResponse = {
  token: string;
  username: string;
};

export type MlServiceStatus = {
  running: boolean;
  status: "running" | "stopped" | "starting" | "error" | string;
  apiUrl: string;
  message: string;
};

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
      throw new Error(`Request timed out: ${url}`);
    }
    if (error instanceof TypeError) {
      throw new Error(`Unable to connect to service: ${url}`);
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
}

export function authHeader(token: string): Record<string, string> {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function persistAuthSession(response: AuthResponse) {
  localStorage.setItem(AUTH_TOKEN_KEY, response.token);
  localStorage.setItem(AUTH_USER_KEY, response.username);
}

export function loadAuthSession(): AuthResponse | null {
  const token = localStorage.getItem(AUTH_TOKEN_KEY);
  const username = localStorage.getItem(AUTH_USER_KEY);
  return token && username ? { token, username } : null;
}

export function clearAuthSession() {
  localStorage.removeItem(AUTH_TOKEN_KEY);
  localStorage.removeItem(AUTH_USER_KEY);
}

export function loginPlatform(username: string, password: string) {
  return jsonRequest<AuthResponse>(`${SE_API}/api/se/auth/login`, {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
}

export function registerPlatform(username: string, password: string) {
  return jsonRequest<AuthResponse>(`${SE_API}/api/se/auth/register`, {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
}

export function changePlatformPassword(token: string, currentPassword: string, newPassword: string) {
  return jsonRequest<AuthResponse>(`${SE_API}/api/se/auth/change-password`, {
    method: "POST",
    headers: authHeader(token),
    body: JSON.stringify({ currentPassword, newPassword })
  });
}

export function resetPlatformPassword(username: string, recoveryCode: string, newPassword: string) {
  return jsonRequest<AuthResponse>(`${SE_API}/api/se/auth/reset-password`, {
    method: "POST",
    body: JSON.stringify({ username, recoveryCode, newPassword })
  });
}

async function getDirectMlServiceStatus(proxyError?: unknown): Promise<MlServiceStatus> {
  const metadata = await jsonRequest<MlMetadata>(`${ML_API}/api/ml/metadata`);
  const proxyMessage = proxyError instanceof Error ? ` Proxy check failed: ${proxyError.message}` : "";
  return {
    running: true,
    status: "running",
    apiUrl: ML_API,
    message: `ML service is available directly with ${metadata.feature_count} features loaded.${proxyMessage}`
  };
}

export async function getMlServiceStatus() {
  try {
    return await jsonRequest<MlServiceStatus>(`${SE_API}/api/ml-control/status`);
  } catch (error) {
    return getDirectMlServiceStatus(error);
  }
}

export async function startMlService(token: string) {
  try {
    return await jsonRequest<MlServiceStatus>(`${SE_API}/api/ml-control/start`, {
      method: "POST",
      headers: authHeader(token)
    });
  } catch (error) {
    try {
      return await getDirectMlServiceStatus(error);
    } catch {
      throw error;
    }
  }
}
