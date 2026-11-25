import { useState } from 'react'
import type { UserInfo } from '../api'
import { loginApi } from '../App'

const ACCOUNTS = [
  { username: 'city_admin', password: 'city123', desc: '市级管理员：全市、RESTRICTED 密级、全部目的' },
  { username: 'jia_analyst', password: 'jia123', desc: '甲区分析员：仅甲区、STAT 目的、INTERNAL 密级' },
  { username: 'yi_analyst', password: 'yi123', desc: '乙区分析员：乙区 STAT/CASE_INVEST，含 1 条跨区逐案授权' },
  { username: 'auditor', password: 'audit123', desc: '审计员：全市、仅 AUDIT 目的（不可发起 STAT 查询）' }
]

export default function LoginPage({ onLogin }: { onLogin: (u: UserInfo, token: string) => void }) {
  const [username, setUsername] = useState('city_admin')
  const [password, setPassword] = useState('city123')
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    setErr('')
    try {
      const r = await loginApi(username, password)
      onLogin(r.user, r.token)
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h2>消防隐患字段治理平台</h2>
        <p className="sub">字段建模 · 受控映射 · 权限披露 · 离线合并 · 历史复现</p>
        <label>用户名</label>
        <input value={username} onChange={(e) => setUsername(e.target.value)} />
        <label>密码</label>
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        {err && <div className="notice danger">{err}</div>}
        <button className="btn" disabled={loading}>{loading ? '登录中…' : '登录'}</button>
        <div className="login-accounts">
          {ACCOUNTS.map((a) => (
            <div key={a.username}>
              <code
                style={{ cursor: 'pointer' }}
                onClick={() => { setUsername(a.username); setPassword(a.password) }}
                title="点击填充"
              >
                {a.username} / {a.password}
              </code>
              <div>{a.desc}</div>
            </div>
          ))}
        </div>
      </form>
    </div>
  )
}
