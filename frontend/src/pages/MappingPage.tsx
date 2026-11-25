import { useEffect, useState } from 'react'
import { api, type MappingContract } from '../api'

export default function MappingPage() {
  const [contracts, setContracts] = useState<MappingContract[]>([])
  const [open, setOpen] = useState<string | null>(null)

  useEffect(() => {
    api<MappingContract[]>('/catalog/contracts').then(setContracts)
  }, [])

  const statusChip = (s: string) =>
    s === 'APPROVED' ? 'ok' : s === 'DRAFT' ? 'warn' : s === 'SUPERSEDED' ? 'muted' : 'danger'

  return (
    <div>
      <h2 className="page-title">映射契约（逐值证据 · 换算 · 生效范围）</h2>
      <p className="page-desc">
        源代码字段到市级标准字段的映射逐值登记：每个取值必须有证据与等价判定；<b>同名不自动合并</b>。
        <code>equivalent=false</code> 的取值（如乙区新代码 4）在查询编译期被拒绝；DRAFT 契约（待批准扩展）不得进入正式统计。
      </p>

      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>契约</th><th>源代码字段</th><th>标准目标</th><th>辖区</th><th>版本/状态</th>
              <th>生效区间</th><th>证据说明</th><th></th>
            </tr>
          </thead>
          <tbody>
            {contracts.map((c) => (
              <tr key={c.contractId}>
                <td><code>{c.contractId}</code></td>
                <td><code>{c.sourceFieldId}</code></td>
                <td><code>{c.targetFieldId}</code></td>
                <td>{c.sourceDistrict}</td>
                <td>
                  <span className="badge-ver">v{c.version}</span>{' '}
                  <span className={`chip ${statusChip(c.status)}`}>{c.status}</span>
                </td>
                <td className="small">{c.effectiveFrom}<br />{c.effectiveTo ? `至 ${c.effectiveTo}` : '至今'}</td>
                <td className="small" style={{ maxWidth: 300 }}>{c.evidenceNote}</td>
                <td>
                  <button className="btn ghost small" onClick={() => setOpen(open === c.contractId ? null : c.contractId)}>
                    {open === c.contractId ? '收起逐值' : '查看逐值'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {open && (
        <div className="panel">
          {contracts.filter((c) => c.contractId === open).map((c) => (
            <div key={c.contractId}>
              <h3>{c.contractId} 的逐值映射</h3>
              <table>
                <thead>
                  <tr><th>源代码取值</th><th>标准值</th><th>换算</th><th>等价</th><th>证据</th></tr>
                </thead>
                <tbody>
                  {c.values.map((v) => (
                    <tr key={v.sourceValue}>
                      <td><code>{v.sourceValue}</code></td>
                      <td>{v.targetValue ? <code>{v.targetValue}</code> : <span className="muted-text">不产生标准值</span>}</td>
                      <td>{v.transform || '—'}</td>
                      <td>
                        {v.equivalent
                          ? <span className="chip ok">等价</span>
                          : <span className="chip danger">拒绝（无法证明等价）</span>}
                      </td>
                      <td className="small" style={{ maxWidth: 380 }}>{v.evidence}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
