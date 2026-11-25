import { useEffect, useState } from 'react'
import { api, type FieldDef, type QueryResult, type QueryRequest } from '../api'

interface FilterRow { field: string; op: string; value: string }

const CANONICAL: { title: string; request: QueryRequest } = {
  title: '各区当前未整改重大隐患中裂缝宽度≥10mm的案件数',
  request: {
    purpose: 'STAT',
    asOf: '2026-05-20',
    filters: [
      { field: 'std.hazard_level', op: '=', value: 'MAJOR' },
      { field: 'std.crack_width_mm', op: '>=', value: 10 },
      { field: 'std.rectification_status', op: '=', value: 'OPEN' }
    ],
    groupBy: ['district'],
    includeTotal: true
  }
}

export default function QueryPage() {
  const [fields, setFields] = useState<FieldDef[]>([])
  const [purpose, setPurpose] = useState('STAT')
  const [asOf, setAsOf] = useState('2026-05-20')
  const [catalogVersion, setCatalogVersion] = useState('')
  const [filters, setFilters] = useState<FilterRow[]>(CANONICAL.request.filters.map((f) => ({ ...f, value: String(f.value) })))
  const [result, setResult] = useState<QueryResult | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [showDiag, setShowDiag] = useState(false)

  useEffect(() => {
    api<FieldDef[]>('/catalog/fields').then((fs) => setFields(fs.filter((f) => f.fieldId.startsWith('std.'))))
  }, [])

  const stdFields = fields
  const numericFields = new Set(stdFields.filter((f) => (f.valueDomain as { type?: string }).type === 'numeric').map((f) => f.fieldId))

  function setFilter(i: number, patch: Partial<FilterRow>) {
    setFilters((prev) => prev.map((f, idx) => (idx === i ? { ...f, ...patch } : f)))
  }

  async function run(req?: QueryRequest) {
    setLoading(true)
    setError('')
    const request: QueryRequest = req ?? {
      purpose,
      asOf: asOf || undefined,
      catalogVersion: catalogVersion ? Number(catalogVersion) : undefined,
      filters: filters
        .filter((f) => f.field && f.value !== '')
        .map((f) => ({ field: f.field, op: f.op, value: numericFields.has(f.field) ? Number(f.value) : f.value })),
      groupBy: ['district'],
      includeTotal: true
    }
    try {
      setResult(await api<QueryResult>('/query/compile', { method: 'POST', body: JSON.stringify(request) }))
      setShowDiag(false)
    } catch (e) {
      setResult(null)
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  function loadCanonical() {
    setPurpose('STAT')
    setAsOf(CANONICAL.request.asOf!)
    setCatalogVersion('')
    setFilters(CANONICAL.request.filters.map((f) => ({ ...f, value: String(f.value) })))
    run(CANONICAL.request)
  }

  return (
    <div>
      <h2 className="page-title">受控查询编译器</h2>
      <p className="page-desc">
        查询只能用市级标准字段（std.*）表达。编译流水线：目的校验 → 版本/时点固定 → 逐值映射（证据/换算/生效范围）
        → 事件状态重放 → 权限过滤 → 标准化过滤 → 最小发布组（阈值 {result?.suppressionThreshold ?? 4}）与互补抑制。
      </p>

      <div className="panel">
        <button className="btn" onClick={loadCanonical}>载入题目标准查询</button>
        <span className="muted-text small" style={{ marginLeft: 10 }}>{CANONICAL.title}</span>
      </div>

      <div className="panel">
        <div className="row" style={{ marginBottom: 12 }}>
          <div>
            <label>查询目的</label>
            <select value={purpose} onChange={(e) => setPurpose(e.target.value)}>
              <option value="STAT">STAT 统计</option>
              <option value="CASE_INVEST">CASE_INVEST 个案调查</option>
              <option value="AUDIT">AUDIT 审计</option>
              <option value="GOVERN">GOVERN 治理</option>
            </select>
          </div>
          <div>
            <label>asOf 时点</label>
            <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          </div>
          <div>
            <label>目录版本（空=当前）</label>
            <input type="number" min={1} value={catalogVersion} onChange={(e) => setCatalogVersion(e.target.value)} style={{ width: 110 }} />
          </div>
        </div>

        <h4>标准化过滤条件</h4>
        {filters.map((f, i) => (
          <div className="filter-row" key={i}>
            <select value={f.field} onChange={(e) => setFilter(i, { field: e.target.value, op: '=' })}>
              {stdFields.map((s) => <option key={s.fieldId} value={s.fieldId}>{s.fieldId}（{s.displayName}）</option>)}
            </select>
            <select value={f.op} onChange={(e) => setFilter(i, { op: e.target.value })}>
              {(numericFields.has(f.field) ? ['=', '!=', '>', '>=', '<', '<='] : ['=', '!=']).map((op) =>
                <option key={op} value={op}>{op}</option>)}
            </select>
            <input value={f.value} onChange={(e) => setFilter(i, { value: e.target.value })} style={{ width: 160 }} />
            <button className="btn ghost" onClick={() => setFilters((p) => p.filter((_, idx) => idx !== i))}>删除</button>
          </div>
        ))}
        <button className="btn ghost" onClick={() => setFilters((p) => [...p, { field: stdFields[0]?.fieldId || '', op: '=', value: '' }])}>
          + 增加条件
        </button>
        <div style={{ marginTop: 14 }}>
          <button className="btn" disabled={loading} onClick={() => run()}>{loading ? '编译中…' : '编译并执行'}</button>
        </div>
      </div>

      {error && <div className="notice danger">查询被拒绝：{error}</div>}

      {result && (
        <>
          <div className="kpi-grid">
            <div className="kpi"><div className="num">{result.disclosure.total ?? '—'}</div><div className="lbl">可发布总数（原始 {result.disclosure.rawTotal}）</div></div>
            <div className="kpi"><div className="num">{result.disclosure.publishedGroupCount}</div><div className="lbl">已发布分组</div></div>
            <div className="kpi suppressed"><div className="num">{result.disclosure.suppressedGroupCount}</div><div className="lbl">被抑制分组（小组/互补）</div></div>
            <div className="kpi"><div className="num">v{result.catalogVersion}</div><div className="lbl">编译所用目录版本 · asOf {result.asOf}</div></div>
          </div>

          <div className="panel">
            <h3>披露结果（被抑制格不返回原始计数，防止相减反推）</h3>
            <table>
              <thead><tr><th>分组</th><th>发布计数</th><th>状态</th><th>原因</th></tr></thead>
              <tbody>
                {result.disclosure.groups.map((g) => (
                  <tr key={g.group}>
                    <td>{g.group}</td>
                    <td className={g.suppressed ? 'suppressed-cell' : ''}>{g.suppressed ? '抑制（不披露）' : g.count}</td>
                    <td>
                      {g.suppressed
                        ? <span className="chip danger">{g.reason === 'COMPLEMENTARY' ? '互补抑制' : '最小发布组抑制'}</span>
                        : <span className="chip ok">发布</span>}
                    </td>
                    <td className="small muted-text">
                      {g.reason === 'SMALL_GROUP' && `原始计数 < 阈值 ${result.suppressionThreshold}`}
                      {g.reason === 'COMPLEMENTARY' && '否则总数减去其他组可反推该小组'}
                      {!g.reason && '达到最小发布组'}
                    </td>
                  </tr>
                ))}
                <tr style={{ fontWeight: 700, background: '#fafbfd' }}>
                  <td>总数</td>
                  <td className={result.disclosure.totalSuppressed ? 'suppressed-cell' : ''}>
                    {result.disclosure.totalSuppressed ? '抑制' : result.disclosure.total}
                  </td>
                  <td>{result.disclosure.totalSuppressed
                    ? <span className="chip danger">{result.disclosure.totalReason}</span>
                    : <span className="chip ok">发布</span>}</td>
                  <td className="small muted-text">原始总数 {result.disclosure.rawTotal}</td>
                </tr>
              </tbody>
            </table>
          </div>

          {result.compileWarnings.length > 0 && (
            <div className="notice warn">
              <b>编译期拒绝/未决值（不强行合并）：</b>
              <ul style={{ margin: '6px 0 0 18px' }}>
                {result.compileWarnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            </div>
          )}

          <div className="panel">
            <h3>
              逐案编译诊断（{result.includedCases.length} 件入统）
              <button className="btn ghost" style={{ float: 'right' }} onClick={() => setShowDiag(!showDiag)}>
                {showDiag ? '收起' : '展开'}
              </button>
            </h3>
            {showDiag && (
              <div>
                {result.caseDiagnostics.map((d) => (
                  <details key={String(d.instanceId)}>
                    <summary>
                      <span className={d.included ? 'chip ok' : 'chip danger'}>{d.included ? '入统' : '剔除'}</span>
                      {String(d.caseId)} · {String(d.district)} · 表单目录 v{String(d.catalogVersion)} · 检查日 {String(d.inspectDate)}
                    </summary>
                    <h4>映射证据链</h4>
                    <table>
                      <thead><tr><th>源字段</th><th>源值</th><th>标准字段</th><th>标准值</th><th>契约/版本</th><th>换算</th><th>等价</th><th>证据</th></tr></thead>
                      <tbody>
                        {(d.mappings as Array<Record<string, unknown>>).map((m, i) => (
                          <tr key={i}>
                            <td><code>{String(m.sourceField)}</code></td>
                            <td>{String(m.sourceValue)}</td>
                            <td><code>{String(m.targetField)}</code></td>
                            <td>{String(m.targetValue) || '—'}</td>
                            <td className="small">{String(m.contractId)} {m.contractVersion ? `v${String(m.contractVersion)}` : ''}</td>
                            <td>{String(m.transform) || '—'}</td>
                            <td>{m.equivalent ? <span className="chip ok">是</span> : <span className="chip danger">拒绝</span>}</td>
                            <td className="small" style={{ maxWidth: 320 }}>{String(m.evidence)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <h4>事件状态重放</h4>
                    <pre className="json">{JSON.stringify(d.eventState, null, 2)}</pre>
                    {!(d.included as boolean) && (
                      <div className="notice danger small">
                        剔除原因：{(d.reasons as string[]).map((r, i) => <div key={i}>· {r}</div>)}
                      </div>
                    )}
                  </details>
                ))}
              </div>
            )}
          </div>

          <div className="panel">
            <h3>版本快照（历史复现依据）</h3>
            <pre className="json">{JSON.stringify(result.versionSnapshot, null, 2)}</pre>
          </div>
        </>
      )}
    </div>
  )
}
