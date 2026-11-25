import { useEffect, useState } from 'react'
import { api } from '../api'

interface HistoryRow {
  query_id: number
  username: string
  purpose: string
  query_json: Record<string, unknown>
  created_at: string
}

interface ReplayResult {
  queryId: number
  pinnedRequest: Record<string, unknown>
  snapshot: Record<string, unknown>
  groupComparison: Array<Record<string, unknown>>
  reproduceMatch: boolean
  replayResult: { disclosure: { groups: Array<Record<string, unknown>>; total: number | null } }
}

export default function HistoryPage() {
  const [rows, setRows] = useState<HistoryRow[]>([])
  const [replay, setReplay] = useState<ReplayResult | null>(null)
  const [busy, setBusy] = useState<number | null>(null)

  async function load() {
    setRows(await api<HistoryRow[]>('/query/history'))
  }
  useEffect(() => { load() }, [])

  async function doReplay(id: number) {
    setBusy(id)
    try {
      setReplay(await api<ReplayResult>(`/query/history/${id}/replay`, { method: 'POST' }))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div>
      <h2 className="page-title">历史查询复现（按当时表单/字段/映射版本）</h2>
      <p className="page-desc">
        每次受控查询都保存查询表达与版本快照（目录版本、asOf、契约版本清单）。复现时强制按快照重新编译并与存档结果逐组比对；
        即使目录已演进，旧表单仍按提交时语义解释，结果可重复。
      </p>

      <div className="panel">
        <table>
          <thead><tr><th>ID</th><th>目的</th><th>查询表达</th><th>时间</th><th></th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.query_id}>
                <td>#{r.query_id}</td>
                <td><span className="chip info">{r.purpose}</span></td>
                <td><code className="small">{JSON.stringify(r.query_json)}</code></td>
                <td className="small">{r.created_at}</td>
                <td><button className="btn small" disabled={busy === r.query_id} onClick={() => doReplay(r.query_id)}>
                  {busy === r.query_id ? '重放中…' : '按快照复现'}
                </button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {replay && (
        <div className="panel">
          <h3>复现结果 #{replay.queryId}
            {replay.reproduceMatch
              ? <span className="chip ok" style={{ marginLeft: 10 }}>与存档完全一致</span>
              : <span className="chip danger" style={{ marginLeft: 10 }}>存在差异</span>}
          </h3>
          <h4>固定版本快照</h4>
          <pre className="json">{JSON.stringify(replay.snapshot, null, 2)}</pre>
          <h4>逐组比对（存档 vs 复现）</h4>
          <table>
            <thead><tr><th>分组</th><th>存档计数</th><th>复现计数</th><th>存档抑制</th><th>复现抑制</th><th>一致</th></tr></thead>
            <tbody>
              {replay.groupComparison.map((g, i) => (
                <tr key={i}>
                  <td>{String(g.group)}</td>
                  <td>{g.storedCount === null || g.storedCount === undefined ? '抑制' : String(g.storedCount)}</td>
                  <td>{g.replayCount === null || g.replayCount === undefined ? '抑制' : String(g.replayCount)}</td>
                  <td>{String(g.storedSuppressed)}</td>
                  <td>{String(g.replaySuppressed)}</td>
                  <td>{g.same ? <span className="chip ok">是</span> : <span className="chip danger">否</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <h4>固定后的查询请求（忽略客户端任何版本改动）</h4>
          <pre className="json">{JSON.stringify(replay.pinnedRequest, null, 2)}</pre>
        </div>
      )}
    </div>
  )
}
