import { useEffect, useRef, useState } from 'react'
import { api, getToken, type FieldDef } from '../api'
import { idb, type BaselineCache, type OfflineDraft } from '../db'
import type { SyncOutcome } from '../workers/sync.worker'

export default function OfflinePage() {
  const [fields, setFields] = useState<FieldDef[]>([])
  const [baseline, setBaseline] = useState<BaselineCache | null>(null)
  const [drafts, setDrafts] = useState<OfflineDraft[]>([])
  const [outcomes, setOutcomes] = useState<SyncOutcome[]>([])
  const [form, setForm] = useState({
    clientId: `offline-${Date.now()}`,
    caseId: 'J-2026-OFF1',
    district: 'JIA',
    templateId: 'TPL-JIA-V3',
    inspectDate: '2026-07-01',
    baseCatalogVersion: 3
  })
  const [values, setValues] = useState<Record<string, string>>({
    'std.crack_width_mm': '13',
    'jia.risk_level': '1',
    'jia.rectified': 'false'
  })
  const workerRef = useRef<Worker | null>(null)

  async function refreshDrafts() {
    setDrafts(await idb.listDrafts())
  }

  useEffect(() => {
    api<FieldDef[]>('/catalog/fields').then(setFields)
    refreshDrafts()
    const worker = new Worker(new URL('../workers/sync.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (ev: MessageEvent<SyncOutcome>) => {
      setOutcomes((prev) => [ev.data, ...prev])
      refreshDrafts()
    }
    workerRef.current = worker
    return () => worker.terminate()
  }, [])

  async function cacheBaseline() {
    const version = await api<Array<{ version: number }>>('/catalog/versions').then((vs) =>
      Math.max(...vs.map((v) => Number(v.version))))
    const fs = await api<FieldDef[]>(`/catalog/fields?version=${version}`)
    const b: BaselineCache = {
      catalogVersion: version,
      fetchedAt: new Date().toISOString(),
      fields: fs.map((f) => ({
        fieldId: f.fieldId, version: f.version, definition: f.definition,
        valueDomain: f.valueDomain, unit: f.unit, sensitivity: f.sensitivity,
        status: f.status, supersededBy: f.supersededBy
      }))
    }
    await idb.putBaseline(b)
    setBaseline(b)
  }

  async function saveDraftLocally() {
    const draft: OfflineDraft = {
      ...form,
      baseCatalogVersion: Number(form.baseCatalogVersion),
      values,
      createdAt: new Date().toISOString(),
      syncStatus: 'LOCAL'
    }
    await idb.putDraft(draft)
    await refreshDrafts()
  }

  async function syncAll() {
    let b = baseline
    if (!b) {
      await cacheBaseline()
      b = (await idb.listBaselines()).slice(-1)[0]
    }
    workerRef.current?.postMessage({ type: 'SYNC_ALL', currentBaseline: b, token: getToken()! })
  }

  async function resolveDraft(d: OfflineDraft, decision: 'REBASE_RESUBMIT' | 'ADMIN_MERGE') {
    const r = await api<{ status: string; note?: string }>(`/offline/drafts/DR-${d.clientId}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ decision })
    }).catch((e: Error) => ({ status: 'ERROR', note: e.message }))
    setOutcomes((prev) => [{
      type: 'SYNC_OUTCOME', clientId: d.clientId, status: r.status as OfflineDraft['syncStatus'],
      conflicts: [], serverMessage: r.note
    }, ...prev])
  }

  const statusChip = (s: string) =>
    ({ MERGED: 'ok', REBASED: 'info', PENDING_CONFLICT: 'danger', FAILED: 'danger', SYNCING: 'warn', LOCAL: 'muted' } as Record<string, string>)[s] || 'muted'

  return (
    <div>
      <h2 className="page-title">离线草案（IndexedDB + WebWorker 受控合并）</h2>
      <p className="page-desc">
        离线期间草案以<b>字段 ID + 基线目录版本</b>存入 IndexedDB；同步由 WebWorker 执行本地并发改动预检后提交，
        服务端按完整字段历史独立复核。语义（定义/值域/单位）或敏感级发生并发变化的草案转<b>待决</b>，不得强行进入正式数据。
      </p>

      <div className="panel">
        <div className="row">
          <button className="btn ghost" onClick={cacheBaseline}>1. 缓存当前目录基线到 IndexedDB</button>
          <span className="small muted-text">
            {baseline ? `已缓存 v${baseline.catalogVersion}（${baseline.fields.length} 个字段）` : '尚未缓存'}
          </span>
        </div>
      </div>

      <div className="panel">
        <h3>2. 离线填报（断网也可保存）</h3>
        <div className="row">
          <div><label>客户端草案 ID</label><input value={form.clientId}
            onChange={(e) => setForm({ ...form, clientId: e.target.value })} /></div>
          <div><label>案件 ID</label><input value={form.caseId}
            onChange={(e) => setForm({ ...form, caseId: e.target.value })} /></div>
          <div><label>辖区</label>
            <select value={form.district} onChange={(e) => setForm({ ...form, district: e.target.value })}>
              <option value="JIA">JIA</option><option value="YI">YI</option><option value="BING">BING</option>
            </select>
          </div>
          <div><label>模板（决定基线版本）</label>
            <select value={form.templateId} onChange={(e) => setForm({ ...form, templateId: e.target.value })}>
              <option value="TPL-JIA-V3">TPL-JIA-V3（v3，基线=当前）</option>
              <option value="TPL-JIA">TPL-JIA（v2，旧基线）</option>
              <option value="TPL-YI-NEW">TPL-YI-NEW（v2）</option>
            </select>
          </div>
          <div><label>声明基线版本</label><input type="number" value={form.baseCatalogVersion}
            onChange={(e) => setForm({ ...form, baseCatalogVersion: Number(e.target.value) })} style={{ width: 100 }} /></div>
          <div><label>检查日期</label><input type="date" value={form.inspectDate}
            onChange={(e) => setForm({ ...form, inspectDate: e.target.value })} /></div>
        </div>
        <h4>字段取值（字段 ID 固定）</h4>
        {Object.entries(values).map(([k, v]) => (
          <div className="filter-row" key={k}>
            <select value={k} onChange={(e) => {
              const next: Record<string, string> = {}
              Object.entries(values).forEach(([oldK, oldV]) => { if (oldK === k) next[e.target.value] = oldV; else next[oldK] = oldV })
              setValues(next)
            }}>
              {fields.map((f) => <option key={f.fieldId} value={f.fieldId}>{f.fieldId}</option>)}
            </select>
            <input value={v} onChange={(e) => setValues({ ...values, [k]: e.target.value })} style={{ width: 180 }} />
            <button className="btn ghost" onClick={() => {
              const next = { ...values }; delete next[k]; setValues(next)
            }}>删除</button>
          </div>
        ))}
        <button className="btn ghost" onClick={() => {
          // 切到旧基线模板（v2）并加入 v3 才改语义/敏感级的字段，制造并发治理冲突
          setForm((f) => ({ ...f, templateId: 'TPL-JIA', baseCatalogVersion: 2 }))
          setValues({ ...values, 'std.smoke_density': '5' })
        }}>
          + 加入烟气密度字段并切到 v2 旧基线（制造语义+敏感级冲突）
        </button>
        <div style={{ marginTop: 12 }}>
          <button className="btn" onClick={saveDraftLocally}>保存到 IndexedDB</button>
          <button className="btn" style={{ marginLeft: 10 }} onClick={syncAll}>3. WebWorker 同步全部草案</button>
        </div>
      </div>

      <div className="panel">
        <h3>本地草案（IndexedDB）</h3>
        <table>
          <thead><tr><th>clientId</th><th>案件/辖区</th><th>基线版本</th><th>字段数</th><th>状态</th><th>冲突/说明</th><th>操作</th></tr></thead>
          <tbody>
            {drafts.map((d) => (
              <tr key={d.clientId}>
                <td><code>{d.clientId}</code></td>
                <td>{d.caseId}<br /><span className="small muted-text">{d.district}</span></td>
                <td>v{d.baseCatalogVersion}</td>
                <td>{Object.keys(d.values).length}</td>
                <td><span className={`chip ${statusChip(d.syncStatus)}`}>{d.syncStatus}</span></td>
                <td className="small" style={{ maxWidth: 360 }}>
                  {d.conflicts && d.conflicts.length > 0
                    ? d.conflicts.map((c, i) => <div key={i}><b>{String(c.type)}</b>：{String(c.fieldId)} — {String(c.detail)}</div>)
                    : (d.serverMessage || (d.mergedInstanceId ? `已合并为 ${d.mergedInstanceId}` : '—'))}
                </td>
                <td>
                  {d.syncStatus === 'PENDING_CONFLICT' && (
                    <>
                      <button className="btn ghost small" onClick={() => resolveDraft(d, 'REBASE_RESUBMIT')}>退回重填</button>{' '}
                      <button className="btn small" onClick={() => resolveDraft(d, 'ADMIN_MERGE')}>管理员复核强合</button>
                    </>
                  )}
                  <button className="btn ghost small" onClick={async () => { await idb.deleteDraft(d.clientId); refreshDrafts() }}>
                    删除本地
                  </button>
                </td>
              </tr>
            ))}
            {drafts.length === 0 && <tr><td colSpan={7} className="muted-text">暂无本地草案</td></tr>}
          </tbody>
        </table>
      </div>

      <div className="panel">
        <h3>Worker 同步回执</h3>
        {outcomes.length === 0 && <div className="muted-text small">尚无同步记录</div>}
        {outcomes.map((o, i) => (
          <div key={i} className={`notice ${o.status === 'MERGED' ? 'ok' : o.status === 'PENDING_CONFLICT' ? 'danger' : 'info'}`}>
            <code>{o.clientId}</code> → <b>{o.status}</b>
            {o.mergedInstanceId && <> · 实例 {o.mergedInstanceId}</>}
            {o.serverMessage && <> · {o.serverMessage}</>}
            {o.conflicts.length > 0 && (
              <ul style={{ margin: '6px 0 0 18px' }}>
                {o.conflicts.map((c, j) => <li key={j}>{String(c.type)}：{String(c.fieldId)} — {String(c.detail)}</li>)}
              </ul>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
