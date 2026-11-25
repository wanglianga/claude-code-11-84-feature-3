import { useEffect, useState } from 'react'
import { api } from '../api'

// ================= 类型（与后端 explain 包响应对应） =================
interface RankedVenue {
  caseId: string
  district: string
  pointRank?: number
  rankInterval: { best: number; worst: number }
  score: number
  scoreInterval: { min: number; max: number }
  grade: string
  gradeInterval: string[]
  indeterminate: boolean
  missingFields: string[]
  ruleHits: Array<Record<string, unknown>>
  contributions: { method: string; items: Array<Record<string, unknown>>; coalitionEvaluations?: number; efficiencyCheck?: Record<string, unknown> }
}

interface ScenarioRow {
  scenario: string
  status: string
  detail: string
  score: number | null
  grade: string | null
  rank: number | null
  scoreDelta: number | null
  rankDelta: number | null
  rankFlip: boolean
  extra: Record<string, unknown>
}

const STATUS_CHIP: Record<string, string> = {
  COMPUTED: 'ok', BASELINE: 'info', BLOCKED: 'danger', REJECTED: 'danger', UNREACHABLE: 'warn'
}
const STATUS_TEXT: Record<string, string> = {
  COMPUTED: '已计算', BASELINE: '基线', BLOCKED: '阻塞', REJECTED: '拒绝', UNREACHABLE: '不可达'
}
const GRADE_COLOR: Record<string, string> = {
  RED: '#c0392b', ORANGE: '#d35400', YELLOW: '#b7791f', BLUE: '#2b6cb0', NONE: '#7f8c8d'
}

export default function ExplainPage() {
  const [tab, setTab] = useState<'rank' | 'venue' | 'mono'>('rank')
  const [asOf, setAsOf] = useState('2026-05-20')
  const [method, setMethod] = useState<'PATH' | 'SHAPLEY'>('PATH')
  const [ranking, setRanking] = useState<{ ranking: RankedVenue[]; accessDenied: unknown[]; gradeBands: unknown[] } | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState('')

  async function loadRanking(m = method) {
    setLoading(true)
    setError('')
    try {
      const d = await api<{ ranking: RankedVenue[]; accessDenied: unknown[]; gradeBands: unknown[] }>(
        `/explain/ranking?asOf=${asOf}&purpose=STAT&method=${m}`)
      setRanking(d)
      if (!selected && d.ranking.length) setSelected(d.ranking[0].caseId)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { loadRanking() }, [])

  return (
    <div>
      <h2 className="page-title">高风险场所排序：规则解释与稳定性</h2>
      <p className="page-desc">
        解释自定义字段如何改变规则结论：规则解释图（源字段版本→契约/换算→标准值→规则分支→等级）、
        字段贡献分解（路径归因 / Shapley）、缺失情景（翻转只给区间）、单调性检验与稳定性报告。
        三条铁律：废止字段不按现行含义回算历史（REFUSED）；缺失翻转只给区间；无批准换算 BLOCKED 不进贡献。
      </p>

      <div className="panel">
        <div className="row">
          <div>
            <label>asOf 时点</label>
            <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          </div>
          <div>
            <label>贡献口径</label>
            <select value={method} onChange={(e) => setMethod(e.target.value as 'PATH' | 'SHAPLEY')}>
              <option value="PATH">PATH 规则路径归因（逐条可审计）</option>
              <option value="SHAPLEY">SHAPLEY 联盟贡献（表达交互，2^n 计算）</option>
            </select>
          </div>
          <button className="btn" disabled={loading} onClick={() => loadRanking()}>
            {loading ? '计算中…' : '重新计算排序'}
          </button>
        </div>
      </div>

      <div className="tabs">
        <button className={tab === 'rank' ? 'active' : ''} onClick={() => setTab('rank')}>排序与贡献分解</button>
        <button className={tab === 'venue' ? 'active' : ''} onClick={() => setTab('venue')}>单场所解释与稳定性</button>
        <button className={tab === 'mono' ? 'active' : ''} onClick={() => setTab('mono')}>单调性检验</button>
      </div>

      {error && <div className="notice danger">请求被拒绝：{error}</div>}

      {tab === 'rank' && ranking && (
        <RankTab ranking={ranking.ranking} denied={ranking.accessDenied} onPick={(c) => { setSelected(c); setTab('venue') }} />
      )}
      {tab === 'venue' && (
        <VenueTab caseId={selected} setCaseId={setSelected} cases={ranking?.ranking.map((r) => r.caseId) ?? []} asOf={asOf} />
      )}
      {tab === 'mono' && <MonoTab asOf={asOf} />}
    </div>
  )
}

// ================= 标签1：排序与贡献 =================
function RankTab({ ranking, denied, onPick }: { ranking: RankedVenue[]; denied: unknown[]; onPick: (c: string) => void }) {
  return (
    <>
      <div className="panel">
        <h3>场所风险排序（缺失字段按“已批准映射可达取值”给区间；名次会翻转时无定点名次）</h3>
        <table>
          <thead>
            <tr>
              <th>名次（区间）</th><th>场所</th><th>辖区</th><th>分数区间</th><th>等级区间</th>
              <th>缺失字段</th><th>贡献（{ranking[0]?.contributions.method}）</th><th></th>
            </tr>
          </thead>
          <tbody>
            {ranking.map((r) => (
              <tr key={r.caseId}>
                <td>
                  {r.pointRank != null
                    ? <b>#{r.pointRank}</b>
                    : <span className="chip warn">#{r.rankInterval.best}–#{r.rankInterval.worst}（区间）</span>}
                </td>
                <td>{r.caseId}</td>
                <td>{r.district}</td>
                <td>
                  <b style={{ color: GRADE_COLOR[r.grade] }}>{r.score}</b>
                  {r.scoreInterval.min !== r.scoreInterval.max &&
                    <span className="small muted-text">（{r.scoreInterval.min}–{r.scoreInterval.max}）</span>}
                </td>
                <td>
                  {r.gradeInterval[0] === r.gradeInterval[1]
                    ? <span className="chip" style={{ background: `${GRADE_COLOR[r.grade]}22`, color: GRADE_COLOR[r.grade] }}>{r.grade}</span>
                    : <span className="chip warn">{r.gradeInterval[1]}–{r.gradeInterval[0]}</span>}
                </td>
                <td className="small">{r.missingFields.length ? r.missingFields.join('、') : '—'}</td>
                <td className="small">
                  {r.contributions.items.map((it) => (
                    <span key={String(it.ruleId)} className="chip muted">
                      {String(it.ruleId).replace('RULE_', '')}:
                      {it.shapleyGradeUtility != null ? String(it.shapleyGradeUtility) : String(it.points)}
                    </span>
                  ))}
                  {r.contributions.coalitionEvaluations != null &&
                    <div className="small muted-text">联盟评估 {r.contributions.coalitionEvaluations} 次</div>}
                </td>
                <td><button className="btn ghost" onClick={() => onPick(r.caseId)}>解释 →</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        {denied.length > 0 && (
          <div className="notice warn small" style={{ marginTop: 10 }}>
            访问策略剔除 {denied.length} 个场所（辖区/敏感级/目的不满足），不进入排序。
          </div>
        )}
        {ranking[0]?.contributions.efficiencyCheck && (
          <div className="notice ok small" style={{ marginTop: 10 }}>
            效率校验：<code>{JSON.stringify(ranking[0].contributions.efficiencyCheck)}</code>
          </div>
        )}
      </div>
    </>
  )
}

// ================= 标签2：单场所解释与稳定性 =================
function VenueTab({ caseId, setCaseId, cases, asOf }: { caseId: string; setCaseId: (c: string) => void; cases: string[]; asOf: string }) {
  const [graph, setGraph] = useState<Record<string, unknown> | null>(null)
  const [contrib, setContrib] = useState<Record<string, unknown> | null>(null)
  const [stability, setStability] = useState<Record<string, unknown> | null>(null)
  const [subField, setSubField] = useState('')
  const [subResult, setSubResult] = useState<Record<string, unknown> | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    if (!caseId) return
    setErr('')
    Promise.all([
      api<Record<string, unknown>>(`/explain/venues/${caseId}/graph?asOf=${asOf}&purpose=STAT`),
      api<Record<string, unknown>>(`/explain/venues/${caseId}/contributions?asOf=${asOf}&purpose=STAT&method=PATH`),
      api<Record<string, unknown>>(`/explain/venues/${caseId}/stability?asOf=${asOf}&purpose=STAT`)
    ]).then(([g, c, s]) => { setGraph(g); setContrib(c); setStability(s) })
      .catch((e) => setErr(e instanceof Error ? e.message : String(e)))
  }, [caseId, asOf])

  async function runSubstitution(useCurrent: boolean) {
    if (!subField) return
    setErr('')
    try {
      const d = await api<Record<string, unknown>>(
        `/explain/venues/${caseId}/scenarios/substitution?field=${encodeURIComponent(subField)}&useCurrentMeaning=${useCurrent}&asOf=${asOf}&purpose=STAT`)
      setSubResult(d)
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    }
  }

  const nodes = (graph?.nodes ?? []) as Array<Record<string, unknown>>
  const edges = (graph?.edges ?? []) as Array<Record<string, string>>
  const nodeTypeColor: Record<string, string> = {
    SOURCE: '#2b6cb0', CONTRACT: '#1f7a4d', CONTRACT_INTERNAL: '#1f7a4d',
    REJECTED: '#c0392b', MISSING: '#b7791f', RULE: '#6c3483', STD: '#1d2733', SCORE: '#b33b2e'
  }

  return (
    <>
      <div className="panel">
        <div className="row">
          <div>
            <label>选择场所</label>
            <select value={caseId} onChange={(e) => setCaseId(e.target.value)}>
              {cases.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          {graph && (
            <div style={{ alignSelf: 'center' }}>
              总分 <b style={{ fontSize: 22, color: '#b33b2e' }}>{String(graph.score)}</b>
              <span className="chip" style={{ marginLeft: 8, background: `${GRADE_COLOR[String((graph.grade as Record<string, unknown>).grade)]}22`,
                color: GRADE_COLOR[String((graph.grade as Record<string, unknown>).grade)] }}>
                {String((graph.grade as Record<string, unknown>).grade)} · {String((graph.grade as Record<string, unknown>).label)}
              </span>
            </div>
          )}
        </div>
      </div>

      {err && <div className="notice danger">{err}</div>}

      <div className="panel">
        <h3>规则解释图（节点带字段版本/契约批准状态；废止、拒绝、缺失独立成节点）</h3>
        <div className="graph-flow">
          {nodes.map((n) => (
            <div className="graph-node" key={String(n.id)} style={{ borderColor: nodeTypeColor[String(n.type)] }}>
              <div className="graph-type" style={{ background: nodeTypeColor[String(n.type)] }}>{String(n.type)}</div>
              <div className="graph-label">{String(n.label)}</div>
              {(n.points != null || n.value != null || n.fieldStatusNow != null) && (
                <div className="small muted-text">
                  {n.points != null && <div>得分 {String(n.points)}</div>}
                  {n.value != null && <div>标准值 {String(n.value)}</div>}
                  {n.fieldDefVersionAtSubmit != null && <div>提交时 v{String(n.fieldDefVersionAtSubmit)}（{String(n.fieldStatusAtSubmit)}）→ 现行 {String(n.currentFieldDefVersion ?? '—')}（{String(n.fieldStatusNow)}）{n.versionDrift ? ' ⚠漂移' : ''}</div>}
                  {n.status != null && <div>契约状态 {String(n.status)}</div>}
                  {n.transform != null && <div>换算 {String(n.transform)}</div>}
                  {n.reason != null && <div className="suppressed-cell">{String(n.reason)}</div>}
                </div>
              )}
            </div>
          ))}
        </div>
        <details>
          <summary>边（映射/换算/分支命中关系，{edges.length} 条）</summary>
          <table>
            <thead><tr><th>从</th><th>到</th><th>说明</th></tr></thead>
            <tbody>
              {edges.map((e, i) => (
                <tr key={i}><td className="small"><code>{e.from}</code></td><td className="small"><code>{e.to}</code></td><td className="small">{e.label}</td></tr>
              ))}
            </tbody>
          </table>
        </details>
      </div>

      <div className="panel">
        <h3>字段贡献分解（PATH 路径归因）</h3>
        {contrib && (
          <table>
            <thead><tr><th>规则</th><th>字段</th><th>命中分支</th><th>实际值</th><th>得分</th><th>份额</th><th>单调声明</th></tr></thead>
            <tbody>
              {((contrib.items ?? []) as Array<Record<string, unknown>>).map((it, i) => (
                <tr key={i}>
                  <td>{String(it.title)}<div className="small muted-text">{String(it.ruleId)} v{String(it.ruleVersion)}</div></td>
                  <td><code>{String(it.targetField)}</code></td>
                  <td className="small">{String(it.branchLabel)}</td>
                  <td>{it.actualValue == null ? '—' : String(it.actualValue)}</td>
                  <td><b>{String(it.points)}</b>{it.fieldMissing ? <span className="chip warn" style={{ marginLeft: 6 }}>缺失0分</span> : null}</td>
                  <td>{String(it.sharePct)}%</td>
                  <td><span className="chip muted">{String(it.monotonicDirection)}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <h3>替换情景（同版本值域；废止字段按现行含义回算将被 REFUSED）</h3>
        <div className="row">
          <div>
            <label>源字段</label>
            <input value={subField} placeholder="如 yi.risk_level" onChange={(e) => setSubField(e.target.value)} style={{ width: 220 }} />
          </div>
          <button className="btn ghost" onClick={() => runSubstitution(false)}>按提交时语义替换</button>
          <button className="btn ghost" onClick={() => runSubstitution(true)}>尝试按现行含义回算（应拒绝）</button>
        </div>
        {subResult && <ScenarioTable rows={(subResult.scenarios ?? []) as ScenarioRow[]} guard={subResult.guard as Record<string, unknown>} policy={String(subResult.policy ?? '')} />}
      </div>

      <div className="panel">
        <h3>稳定性报告（替换 / 缺失 / 单位换算对等级与名次的边际影响）</h3>
        {stability && (
          <>
            <div className="kpi-grid">
              <div className="kpi"><div className="num">{String((stability.baseline as Record<string, unknown>).score)}</div><div className="lbl">基线分数</div></div>
              <div className="kpi"><div className="num">{String((stability.baseline as Record<string, unknown>).grade)}</div><div className="lbl">基线等级</div></div>
              <div className="kpi suppressed"><div className="num">{String((stability.stability as Record<string, unknown>).blockedConversions)}</div><div className="lbl">阻塞的无批准换算</div></div>
              <div className="kpi suppressed"><div className="num">{String((stability.stability as Record<string, unknown>).rejectedSubstitutions)}</div><div className="lbl">拒绝的替换取值</div></div>
            </div>
            <StabilityGroup title="缺失情景" groups={stability.missingScenarios as unknown[]} />
            <StabilityGroup title="替换情景" groups={stability.substitutionScenarios as unknown[]} />
            <StabilityGroup title="单位换算情景" groups={stability.unitScenarios as unknown[]} />
            <div className="notice info small">
              等级稳定：{String((stability.stability as Record<string, unknown>).gradeStable)} ｜
              名次稳定：{String((stability.stability as Record<string, unknown>).rankStable)}
            </div>
          </>
        )}
      </div>
    </>
  )
}

function ScenarioTable({ rows, guard, policy }: { rows: ScenarioRow[]; guard?: Record<string, unknown>; policy: string }) {
  return (
    <>
      {guard && <pre className="json">{JSON.stringify(guard, null, 2)}</pre>}
      <table>
        <thead><tr><th>情景</th><th>状态</th><th>分数</th><th>等级</th><th>名次</th><th>分数Δ</th><th>名次Δ</th><th>翻转</th><th>说明</th></tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              <td className="small">{r.scenario}</td>
              <td><span className={`chip ${STATUS_CHIP[r.status] ?? 'muted'}`}>{STATUS_TEXT[r.status] ?? r.status}</span></td>
              <td>{r.score ?? '—'}</td>
              <td>{r.grade ?? '—'}</td>
              <td>{r.rank ?? '—'}</td>
              <td>{r.scoreDelta ?? '—'}</td>
              <td>{r.rankDelta ?? '—'}</td>
              <td>{r.rankFlip ? <span className="chip danger">翻转</span> : '—'}</td>
              <td className="small muted-text" style={{ maxWidth: 360 }}>{r.detail}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {policy && <div className="notice warn small" style={{ marginTop: 8 }}>{policy}</div>}
    </>
  )
}

function StabilityGroup({ title, groups }: { title: string; groups: unknown[] }) {
  if (!groups || !groups.length) return null
  return (
    <details>
      <summary>{title}（{groups.length} 个字段）</summary>
      {groups.map((g0, i) => {
        const g = g0 as Record<string, unknown>
        return (
          <div key={i} style={{ margin: '8px 0', paddingLeft: 8, borderLeft: '3px solid var(--line)' }}>
            <b><code>{String(g.field)}</code></b>
            {g.rankFlipPossible ? <span className="chip danger" style={{ marginLeft: 8 }}>名次可能翻转→只给区间</span>
              : <span className="chip ok" style={{ marginLeft: 8 }}>名次稳定</span>}
            <span className="small muted-text" style={{ marginLeft: 8 }}>
              计算 {String(g.computed)} ｜ 阻塞 {String(g.blocked)} ｜ 拒绝 {String(g.rejected)} ｜ 不可达 {String(g.unreachable)}
            </span>
            {!!g.scoreInterval && <span className="small" style={{ marginLeft: 8 }}>分数区间 {JSON.stringify(g.scoreInterval)}</span>}
            <ScenarioTable rows={(g.rows ?? []) as ScenarioRow[]} policy="" />
          </div>
        )
      })}
    </details>
  )
}

// ================= 标签3：单调性检验 =================
function MonoTab({ asOf }: { asOf: string }) {
  const [sweep, setSweep] = useState<Record<string, unknown> | null>(null)
  const [drift, setDrift] = useState<Record<string, unknown> | null>(null)
  const [candidate, setCandidate] = useState('')
  const [candidateResult, setCandidateResult] = useState<Record<string, unknown> | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    Promise.all([
      api<Record<string, unknown>>('/explain/monotonicity/rules'),
      api<Record<string, unknown>>(`/explain/monotonicity/drift?asOf=${asOf}&purpose=STAT`)
    ]).then(([s, d]) => { setSweep(s); setDrift(d) }).catch((e) => setErr(String(e)))
  }, [asOf])

  async function checkCandidate() {
    setErr('')
    try {
      const body = JSON.parse(candidate)
      const d = await api<Record<string, unknown>>('/explain/monotonicity/rule-update', { method: 'POST', body: JSON.stringify(body) })
      setCandidateResult(d)
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    }
  }

  const BAD_EXAMPLE = JSON.stringify({
    ruleId: 'RULE_CRACK', candidateVersion: 2, targetField: 'std.crack_width_mm', ruleType: 'NUMERIC_BRANCH',
    branches: [{ op: '>=', value: 20, points: 10, label: '高裂缝反而低分（违反ASC）' },
      { op: '>=', value: 10, points: 30, label: '低裂缝高分' }],
    defaultPoints: 0, monotonicDirection: 'ASC'
  }, null, 2)

  return (
    <>
      {err && <div className="notice danger">{err}</div>}
      <div className="panel">
        <h3>A. 现行规则结构扫描（对照已声明安全单调关系）</h3>
        {sweep && (
          <>
            <div className={sweep.pass ? 'notice ok' : 'notice danger'}>
              {sweep.pass ? `全部规则通过（违规数 ${String(sweep.violationCount)}）` : `发现 ${String(sweep.violationCount)} 处单调违反`}
            </div>
            <table>
              <thead><tr><th>规则</th><th>字段</th><th>声明方向</th><th>扫描点轨迹</th><th>结论</th></tr></thead>
              <tbody>
                {((sweep.ruleChecks ?? []) as Array<Record<string, unknown>>).map((r, i) => (
                  <tr key={i}>
                    <td>{String(r.ruleId)} v{String(r.ruleVersion)}</td>
                    <td><code>{String(r.targetField)}</code></td>
                    <td><span className="chip muted">{String(r.monotonicDirection)}</span></td>
                    <td className="small"><code>{JSON.stringify(r.trace)}</code></td>
                    <td>{r.pass ? <span className="chip ok">通过</span> : <span className="chip danger">违反</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </div>

      <div className="panel">
        <h3>B. 规则更新候选校验（发布前拦截单调违反，不落库）</h3>
        <textarea rows={8} style={{ width: '100%', fontFamily: 'monospace', fontSize: 12 }}
          value={candidate || BAD_EXAMPLE} onChange={(e) => setCandidate(e.target.value)} />
        <div style={{ marginTop: 8 }}><button className="btn" onClick={checkCandidate}>校验候选规则</button></div>
        {candidateResult && (
          <div style={{ marginTop: 10 }}>
            <span className={`chip ${candidateResult.decision === 'ACCEPTABLE' ? 'ok' : 'danger'}`}>
              {String(candidateResult.decision)}
            </span>
            <div className="small muted-text" style={{ marginTop: 6 }}>{String(candidateResult.note)}</div>
            <pre className="json">{JSON.stringify(candidateResult.sweep, null, 2)}</pre>
          </div>
        )}
      </div>

      <div className="panel">
        <h3>C. 字段版本漂移与实证扰动（漂移只警示、不回算历史）</h3>
        {drift && (
          <>
            <div className={drift.pass ? 'notice ok' : 'notice danger'}>
              实证扰动违反数 {String(drift.empiricalBreachCount)}
            </div>
            <h4>漂移字段使用（{((drift.driftedFieldUses ?? []) as unknown[]).length}）</h4>
            <table>
              <thead><tr><th>场所</th><th>源字段</th><th>提交时版本</th><th>现行版本</th><th>守卫说明</th></tr></thead>
              <tbody>
                {((drift.driftedFieldUses ?? []) as Array<Record<string, unknown>>).map((d, i) => (
                  <tr key={i}>
                    <td>{String(d.caseId)}</td><td><code>{String(d.sourceField)}</code></td>
                    <td>v{String(d.fieldDefVersionAtSubmit)}（{String(d.fieldStatusAtSubmit)}）</td>
                    <td>v{String(d.currentFieldDefVersion)}（{String(d.fieldStatusNow)}）</td>
                    <td className="small muted-text">{String(d.policy)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <details style={{ marginTop: 10 }}>
              <summary>实证扰动轨迹（{((drift.empiricalProbes ?? []) as unknown[]).length}）</summary>
              <pre className="json">{JSON.stringify(drift.empiricalProbes, null, 2)}</pre>
            </details>
          </>
        )}
      </div>
    </>
  )
}
