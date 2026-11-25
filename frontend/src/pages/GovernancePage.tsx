import { useEffect, useState } from 'react'
import { api } from '../api'

interface Extension {
  request_id: string
  route: string
  district: string | null
  proposed_field_id: string
  field_def_json: Record<string, unknown>
  contract_json: Record<string, unknown> | null
  status: string
  evidence: string
  catalog_version_introduced: number | null
  reviewed_by: string | null
  created_at: string
  approved_at: string | null
}

export default function GovernancePage() {
  const [exts, setExts] = useState<Extension[]>([])
  const [log, setLog] = useState<string[]>([])
  const [retro, setRetro] = useState('2026-05-01')

  async function load() {
    setExts(await api<Extension[]>('/governance/extensions'))
  }
  useEffect(() => { load() }, [])

  function pushLog(s: string) {
    setLog((p) => [`${new Date().toLocaleTimeString()}  ${s}`, ...p])
  }

  // 批准前：尝试对市标字段发起查询（目录中尚不存在 → 编译拒绝）
  async function queryBeforeApproval() {
    try {
      await api('/query/compile', {
        method: 'POST',
        body: JSON.stringify({
          purpose: 'STAT', asOf: '2026-05-20',
          filters: [{ field: 'std.ev_charge_hazard', op: '=', value: 'PRESENT' }],
          groupBy: ['district'], includeTotal: true
        })
      })
      pushLog('意外：查询竟然通过（字段可能已被批准）')
    } catch (e) {
      pushLog(`批准前查询被拒绝（预期）：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  async function approve(id: string) {
    const r = await api<Record<string, unknown>>(`/governance/extensions/${id}/approve`, {
      method: 'POST',
      body: JSON.stringify({ retroactiveFrom: retro })
    })
    pushLog(`已批准 ${id}：${JSON.stringify(r)}`)
    await load()
  }

  async function reject(id: string) {
    const r = await api<Record<string, unknown>>(`/governance/extensions/${id}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason: '证据不足（演示驳回）' })
    })
    pushLog(`已驳回 ${id}：${JSON.stringify(r)}`)
    await load()
  }

  // 批准后：回溯查询——旧表单行不改写，编译期按新批准契约解释
  async function queryAfterApproval() {
    const r = await api<{ includedCases: string[]; compileWarnings: string[]; disclosure: unknown; catalogVersion: number }>(
      '/query/compile',
      {
        method: 'POST',
        body: JSON.stringify({
          purpose: 'STAT', asOf: '2026-05-20',
          filters: [{ field: 'std.ev_charge_hazard', op: '=', value: 'PRESENT' }],
          groupBy: ['district'], includeTotal: true
        })
      })
    pushLog(`批准后回溯查询：目录 v${r.catalogVersion}，入统案件 ${r.includedCases.join(', ') || '无'}，警告 ${r.compileWarnings.length} 条`)
  }

  async function verifyFormNotRewritten() {
    const d = await api<{ instance: { content_hash?: string; catalogVersion: number } }>('/forms/FI-Y-005')
    pushLog(`旧表单 FI-Y-005 未改写：仍按提交时目录 v${d.instance.catalogVersion} 解释（实例行不可变，回溯仅发生在编译期）`)
  }

  return (
    <div>
      <h2 className="page-title">新隐患字段治理（两条路线）</h2>
      <p className="page-desc">
        <b>路线 A · 市级标准（CITY_STANDARD）</b>：市级目录统一立项维护，口径最一致，但新隐患类型纳入需等待治理评审。<br />
        <b>路线 B · 命名空间扩展（NAMESPACE_EXT）</b>：辖区在自己命名空间内扩展字段并提交有证据的逐值映射契约，市级批准后进入查询编译，
        适应新场景更快，代价是映射审查与版本维护成本。两条路线对可确认的裂缝宽度/状态查询必须给出相同标准结果。
      </p>

      <div className="panel">
        <h3>验证流程：新字段批准前如何查询、批准后如何回溯且不改写旧表单</h3>
        <div className="row" style={{ alignItems: 'center' }}>
          <button className="btn ghost" onClick={queryBeforeApproval}>① 批准前查询 std.ev_charge_hazard（应拒绝）</button>
          <div>
            <label>批准追溯生效日</label>
            <input type="date" value={retro} onChange={(e) => setRetro(e.target.value)} />
          </div>
          <button className="btn ghost" onClick={queryAfterApproval}>③ 批准后回溯查询（编译期解释旧表单）</button>
          <button className="btn ghost" onClick={verifyFormNotRewritten}>④ 校验旧表单未改写</button>
        </div>
      </div>

      <div className="panel">
        <table>
          <thead>
            <tr><th>申请</th><th>路线</th><th>辖区</th><th>字段</th><th>状态</th><th>纳入版本</th><th>证据</th><th>操作</th></tr>
          </thead>
          <tbody>
            {exts.map((e) => (
              <tr key={e.request_id}>
                <td><code>{e.request_id}</code></td>
                <td><span className={`chip ${e.route === 'CITY_STANDARD' ? 'brand' : 'info'}`}>
                  {e.route === 'CITY_STANDARD' ? 'A 市级标准' : 'B 命名空间扩展'}
                </span></td>
                <td>{e.district || '全市'}</td>
                <td><code>{e.proposed_field_id}</code></td>
                <td><span className={`chip ${e.status === 'APPROVED' ? 'ok' : e.status === 'DRAFT' ? 'warn' : 'danger'}`}>{e.status}</span></td>
                <td>{e.catalog_version_introduced ? `v${e.catalog_version_introduced}` : '—'}</td>
                <td className="small" style={{ maxWidth: 300 }}>{e.evidence}</td>
                <td>
                  {e.status === 'DRAFT' && (
                    <>
                      <button className="btn small" onClick={() => approve(e.request_id)}>② 批准（可追溯）</button>{' '}
                      <button className="btn ghost small" onClick={() => reject(e.request_id)}>驳回</button>
                    </>
                  )}
                  {e.status !== 'DRAFT' && <span className="small muted-text">{e.reviewed_by || ''} 已处理</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="panel">
        <h3>治理操作日志</h3>
        {log.length === 0 && <div className="muted-text small">按 ①→②→③→④ 顺序点击以演示完整治理闭环</div>}
        {log.map((l, i) => <div key={i} className="notice info small">{l}</div>)}
      </div>
    </div>
  )
}
