import { LockKeyhole, Search, Send, Trash2, Upload } from "lucide-react";
import { FormEvent, useEffect, useState } from "react";
import { authHeader, DocumentDetail, DocumentSummary, jsonRequest, SE_API } from "../lib/api";

type AuthResponse = {
  token: string;
  username: string;
};

export function EncryptedVault() {
  const [token, setToken] = useState(localStorage.getItem("se_token") ?? "");
  const [username, setUsername] = useState(localStorage.getItem("se_user") ?? "demo");
  const [password, setPassword] = useState("demo123");
  const [documents, setDocuments] = useState<DocumentSummary[]>([]);
  const [selected, setSelected] = useState<DocumentDetail | null>(null);
  const [docId, setDocId] = useState(`CASE-${Date.now().toString().slice(-5)}`);
  const [description, setDescription] = useState("attack evidence shap searchable keywords");
  const [text, setText] = useState('{"caseId":"CASE-001","attackType":"Generic","sourceIp":"192.168.1.100"}');
  const [keyword, setKeyword] = useState("Generic");
  const [message, setMessage] = useState("");

  const loggedIn = Boolean(token);

  async function authenticate(mode: "login" | "register") {
    setMessage("");
    const response = await jsonRequest<AuthResponse>(`${SE_API}/api/se/auth/${mode}`, {
      method: "POST",
      body: JSON.stringify({ username, password })
    });
    setToken(response.token);
    setUsername(response.username);
    localStorage.setItem("se_token", response.token);
    localStorage.setItem("se_user", response.username);
    setMessage(mode === "login" ? "已登录加密证据库" : "已注册并登录");
  }

  async function loadDocuments() {
    if (!token) return;
    const response = await jsonRequest<DocumentSummary[]>(`${SE_API}/api/se/documents`, {
      headers: authHeader(token)
    });
    setDocuments(response);
  }

  async function uploadText(event: FormEvent) {
    event.preventDefault();
    const data = new FormData();
    data.set("docId", docId);
    data.set("description", description);
    data.set("text", text);

    await jsonRequest<DocumentSummary>(`${SE_API}/api/se/documents/upload`, {
      method: "POST",
      headers: authHeader(token),
      body: data
    });
    setMessage("证据已加密写入并建立可搜索索引");
    await loadDocuments();
  }

  async function searchDocuments() {
    const response = await jsonRequest<DocumentSummary[]>(
      `${SE_API}/api/se/documents/search?keyword=${encodeURIComponent(keyword)}`,
      { headers: authHeader(token) }
    );
    setDocuments(response);
  }

  async function openDocument(id: string) {
    const response = await jsonRequest<DocumentDetail>(`${SE_API}/api/se/documents/${encodeURIComponent(id)}`, {
      headers: authHeader(token)
    });
    setSelected(response);
  }

  async function deleteDocument(id: string) {
    await jsonRequest<{ deleted: boolean }>(`${SE_API}/api/se/documents/${encodeURIComponent(id)}`, {
      method: "DELETE",
      headers: authHeader(token)
    });
    setSelected(null);
    await loadDocuments();
  }

  useEffect(() => {
    loadDocuments();
  }, [token]);

  return (
    <section className="page">
      <div className="page-header">
        <div>
          <p className="eyebrow">Searchable encryption facade</p>
          <h1>加密证据库</h1>
        </div>
      </div>

      <div className="three-column">
        <section className="panel">
          <div className="panel-heading">
            <h2>会话</h2>
            <LockKeyhole size={18} />
          </div>
          <label>Username</label>
          <input value={username} onChange={(event) => setUsername(event.target.value)} />
          <label>Password</label>
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
          <div className="button-row">
            <button className="primary-action" onClick={() => authenticate("login")}>
              登录
            </button>
            <button className="secondary-action" onClick={() => authenticate("register")}>
              注册
            </button>
          </div>
          {loggedIn && <div className="notice success">Bearer token 已保存到本地浏览器会话。</div>}
          {message && <div className="notice">{message}</div>}
        </section>

        <form className="panel" onSubmit={uploadText}>
          <div className="panel-heading">
            <h2>上传证据</h2>
            <Upload size={18} />
          </div>
          <label>Document ID</label>
          <input value={docId} onChange={(event) => setDocId(event.target.value)} />
          <label>Description / Keywords</label>
          <input value={description} onChange={(event) => setDescription(event.target.value)} />
          <label>Evidence JSON or text</label>
          <textarea value={text} onChange={(event) => setText(event.target.value)} />
          <button className="primary-action" type="submit" disabled={!loggedIn}>
            <Send size={17} /> 加密上传
          </button>
        </form>

        <section className="panel">
          <div className="panel-heading">
            <h2>密文搜索</h2>
            <Search size={18} />
          </div>
          <label>Keyword</label>
          <input value={keyword} onChange={(event) => setKeyword(event.target.value)} />
          <div className="button-row">
            <button className="primary-action" onClick={searchDocuments} disabled={!loggedIn}>
              搜索
            </button>
            <button className="secondary-action" onClick={loadDocuments} disabled={!loggedIn}>
              全部
            </button>
          </div>
        </section>
      </div>

      <div className="two-column align-start">
        <section className="panel">
          <div className="panel-heading">
            <h2>证据列表</h2>
            <span className="pill">{documents.length}</span>
          </div>
          <div className="document-list">
            {documents.map((document) => (
              <button key={document.docId} className="document-row" onClick={() => openDocument(document.docId)}>
                <strong>{document.docId}</strong>
                <span>{document.fileName}</span>
                <small>{document.keywordCount} encrypted keywords</small>
              </button>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-heading">
            <h2>证据详情</h2>
            {selected && (
              <button className="icon-button danger" title="删除证据" onClick={() => deleteDocument(selected.docId)}>
                <Trash2 size={17} />
              </button>
            )}
          </div>
          {selected ? (
            <div className="result-stack">
              <div className="kv-grid">
                <span>ID</span><strong>{selected.docId}</strong>
                <span>File</span><strong>{selected.fileName}</strong>
                <span>Media</span><strong>{selected.mediaType}</strong>
                <span>Size</span><strong>{selected.fileSize} bytes</strong>
              </div>
              <pre className="preview-box">{selected.plaintextPreview ?? selected.ciphertextBase64}</pre>
            </div>
          ) : (
            <div className="empty-state">选择一条证据后会显示解密预览或密文摘要。</div>
          )}
        </section>
      </div>
    </section>
  );
}

