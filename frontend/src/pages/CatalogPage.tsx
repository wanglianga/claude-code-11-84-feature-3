import { useEffect, useState } from 'react'
import { api, type FieldDef } from '../api'

export default function CatalogPage() {
  const [fields, setFields] = useState<FieldDef[]>([])
  const [versions, setVersions] = useState<Array<Record<string, unknown>>>([])
  const [version, setVersion] = useState<string>('')
  const [asOf, setAsOf] = useState('')
  const [namespace, setNamespace] = useState('ALL')
  const [history, setHistory] = useState<FieldDef[] | null>(null)

  async function load() {
    const q = new URLSearchParams()
    if (version) q.set('version', version)
    if (asOf) q.set('asOf', asOf)
    setFields(await api<FieldDef[]>(`/catalog/fields?${q.toString()}`))
  }

  useEffect(() => {
    api<Array<Record<string, unknown>>>('/catalog/versions').then(setVersions)
    load()
  }, [])

  const shown = fields.filter((f) => namespace === 'ALL' || f.namespace === namespace)

  return (
    <div>
      <h2 className="page-title">字段目录（稳定字段 ID + 版本化定义）</h2>
      <p className="page-desc">
        每个字段以稳定 field_id 标识，定义、值域、单位、粒度、事件时间含义、适用辖区、敏感级、有效区间与弃用替代关系随目录版本留存。
        旧表单按提交时目录版本解释，不随后续版本改写。
      </p>

      <div className="panel">
        <div className="row">
          <div>
            <label>目录版本解释</label>
            <select value={version} onChange={(e) => setVersion(e.target.value)}>
              <option value="">当前版本</option>
              {versions.map((v) => (
                <option key={String(v.version)} value={String(v.version)}>v{v.version as number} — {String(v.note)}</option>
              ))}
            </select>
          </div>
          <div>
            <label>asOf 日期</label>
            <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          </div>
          <div>
            <label>命名空间</label>
            <select value={namespace} onChange={(e) => setNamespace(e.target.value)}>
              <option value="ALL">全部</option>
              <option value="CITY">CITY 市级标准</option>
              <option value="JIA">JIA 甲区</option>
              <option value="YI">YI 乙区</option>
              <option value="BING">BING 丙区</option>
            </select>
          </div>
          <button className="btn" onClick={load}>应用解释条件</button>
        </div>
      </div>

      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>字段 ID</th><th>版本</th><th>显示名</th><th>定义</th><th>值域</th><th>单位</th>
              <th>粒度/事件时间</th><th>辖区</th><th>敏感级</th><th>有效区间</th><th>状态/替代</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((f) => (
              <tr key={`${f.fieldId}-${f.version}`}>
                <td><code>{f.fieldId}</code></td>
                <td><span className="badge-ver">v{f.version}</span></td>
                <td>{f.displayName}</td>
                <td style={{ maxWidth: 260 }}>{f.definition}</td>
                <td style={{ maxWidth: 180 }}><code className="small">{JSON.stringify(f.valueDomain)}</code></td>
                <td>{f.unit || '—'}</td>
                <td className="small">{f.granularity}<br /><span className="muted-text">{f.eventTimeMeaning}</span></td>
                <td>{f.applicableDistricts.length ? f.applicableDistricts.join(',') : '全市'}</td>
                <td>
                  <span className={`chip ${f.sensitivity === 'RESTRICTED' ? 'danger' : 'muted'}`}>{f.sensitivity}</span>
                </td>
                <td className="small">{f.validFrom}<br />{f.validTo ? `至 ${f.validTo}` : '至今'}</td>
                <td>
                  <span className={`chip ${f.status === 'ACTIVE' ? 'ok' : f.status === 'DEPRECATED' ? 'warn' : 'info'}`}>{f.status}</span>
                  {f.supersededBy && <div className="small muted-text">替代：{f.supersededBy}</div>}
                  <button className="btn ghost small" style={{ marginTop: 4, padding: '3px 8px' }}
                    onClick={async () => setHistory(await api<FieldDef[]>(`/catalog/fields/${encodeURIComponent(f.fieldId)}/history`))}>
                    版本史
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {history && (
        <div className="panel">
          <h3>字段版本史 <button className="btn ghost" style={{ float: 'right' }} onClick={() => setHistory(null)}>关闭</button></h3>
          <table>
            <thead><tr><th>版本</th><th>显示名</th><th>定义</th><th>单位</th><th>敏感级</th><th>状态</th><th>有效区间</th></tr></thead>
            <tbody>
              {history.map((f) => (
                <tr key={f.version}>
                  <td>v{f.version}</td><td>{f.displayName}</td><td style={{ maxWidth: 320 }}>{f.definition}</td>
                  <td>{f.unit || '—'}</td><td>{f.sensitivity}</td><td>{f.status}</td>
                  <td className="small">{f.validFrom} ~ {f.validTo || '至今'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
