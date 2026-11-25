import { useEffect, useState } from 'react'
import { api, type FormInstance } from '../api'

interface MappingTraceRow {
  sourceFieldId: string
  sourceValue: string
  targetFieldId: string | null
  targetValue: string | null
  contractId: string | null
  contractVersion: number | null
  transform: string | null
  equivalent: boolean
  evidence: string
}

interface FormDetail {
  instance: FormInstance
  mappingTrace: MappingTraceRow[]
  caseEvents: Array<Record<string, unknown>>
}

export default function FormsPage() {
  const [forms, setForms] = useState<FormInstance[]>([])
  const [detail, setDetail] = useState<FormDetail | null>(null)
  const [mapCheckResult, setMapCheckResult] = useState('')

  useEffect(() => {
    api<FormInstance[]>('/forms').then(setForms)
  }, [])

  async function open(id: string) {
    setDetail(await api<FormDetail>(`/forms/${id}`))
    setMapCheckResult('')
  }

  async function mapCheckReject() {
    if (!detail) return
    const y4 = detail.instance.values['yi.risk_level'] === '4'
      ? { instanceId: detail.instance.instanceId, fieldId: 'yi.risk_level', value: '4' }
      : { instanceId: detail.instance.instanceId, fieldId: Object.keys(detail.instance.values)[0], value: Object.values(detail.instance.values)[0] }
    try {
      const r = await api<{ accepted: boolean }>('/forms/map-check', { method: 'POST', body: JSON.stringify(y4) })
      setMapCheckResult(`服务端接受：${JSON.stringify(r)}`)
    } catch (e) {
      setMapCheckResult(`服务端拒绝（预期）：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  return (
    <div>
      <h2 className="page-title">表单实例与映射证据链</h2>
      <p className="page-desc">
        表单实例不可变，提交时固定目录版本（catalog_version）。旧表（如乙区 2020 旧版 v1）始终按提交时语义解释；
        复查降级/撤销通过案件事件流重放，不修改原表单行。
      </p>

      <div className="panel">
        <table>
          <thead><tr><th>实例</th><th>案件</th><th>辖区</th><th>模板</th><th>目录版本</th><th>检查日期</th><th>来源</th><th></th></tr></thead>
          <tbody>
            {forms.map((f) => (
              <tr key={f.instanceId}>
                <td><code>{f.instanceId}</code></td>
                <td>{f.caseId}</td>
                <td>{f.district}</td>
                <td className="small">{f.templateId}</td>
                <td><span className="badge-ver">v{f.catalogVersion}</span></td>
                <td>{f.inspectDate}</td>
                <td><span className={`chip ${f.origin === 'OFFLINE_MERGE' ? 'info' : 'muted'}`}>{f.origin}</span></td>
                <td><button className="btn ghost small" onClick={() => open(f.instanceId)}>证据链</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {detail && (
        <div className="panel">
          <h3>{detail.instance.instanceId} 的标准化解释（按提交时 v{detail.instance.catalogVersion}）</h3>
          <table>
            <thead><tr><th>源字段</th><th>源值</th><th>标准字段</th><th>标准值</th><th>契约/版本</th><th>换算</th><th>等价</th><th>证据</th></tr></thead>
            <tbody>
              {detail.mappingTrace.map((m, i) => (
                <tr key={i}>
                  <td><code>{m.sourceFieldId}</code></td>
                  <td>{m.sourceValue}</td>
                  <td><code>{m.targetFieldId || '—'}</code></td>
                  <td>{m.targetValue || '—'}</td>
                  <td className="small">{m.contractId || '无契约'}{m.contractVersion ? ` v${m.contractVersion}` : ''}</td>
                  <td>{m.transform || '—'}</td>
                  <td>{m.equivalent ? <span className="chip ok">等价</span> : <span className="chip danger">拒绝</span>}</td>
                  <td className="small" style={{ maxWidth: 360 }}>{m.evidence}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h4>案件事件流（隐患状态按事件时间重放）</h4>
          <table>
            <thead><tr><th>事件类型</th><th>时间</th><th>载荷</th></tr></thead>
            <tbody>
              {detail.caseEvents.map((e, i) => (
                <tr key={i}>
                  <td><span className={`chip ${String(e.event_type).startsWith('REVIEW') ? 'warn' : 'info'}`}>{String(e.event_type)}</span></td>
                  <td className="small">{String(e.event_time)}</td>
                  <td><code className="small">{JSON.stringify(e.payload)}</code></td>
                </tr>
              ))}
            </tbody>
          </table>

          <h4>显式等价性校验（演示“拒绝无法证明语义等价的值”）</h4>
          <button className="btn ghost" onClick={mapCheckReject}>对本表单发起 map-check 校验</button>
          {mapCheckResult && <div className={`notice ${mapCheckResult.includes('拒绝') ? 'danger' : 'ok'}`} style={{ marginTop: 10 }}>{mapCheckResult}</div>}
        </div>
      )}
    </div>
  )
}
