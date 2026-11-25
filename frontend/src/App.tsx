import { useEffect, useState } from 'react'
import { api, clearSession, getToken, getUser, saveSession, type UserInfo } from './api'
import LoginPage from './pages/LoginPage'
import CatalogPage from './pages/CatalogPage'
import MappingPage from './pages/MappingPage'
import FormsPage from './pages/FormsPage'
import QueryPage from './pages/QueryPage'
import OfflinePage from './pages/OfflinePage'
import HistoryPage from './pages/HistoryPage'
import GovernancePage from './pages/GovernancePage'
import ExplainPage from './pages/ExplainPage'

const NAV = [
  { key: 'query', label: '受控查询' },
  { key: 'explain', label: '排序解释与稳定性' },
  { key: 'catalog', label: '字段目录' },
  { key: 'mapping', label: '映射契约' },
  { key: 'forms', label: '表单与证据链' },
  { key: 'offline', label: '离线草案' },
  { key: 'history', label: '历史复现' },
  { key: 'governance', label: '新字段治理' }
]

export default function App() {
  const [user, setUser] = useState<UserInfo | null>(getUser())
  const [page, setPage] = useState('query')

  useEffect(() => {
    if (!getToken()) setUser(null)
  }, [])

  if (!user) {
    return (
      <LoginPage
        onLogin={(u, token) => {
          saveSession(token, u)
          setUser(u)
        }}
      />
    )
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <h1>消防隐患字段治理<br />与受控查询平台</h1>
        {NAV.map((n) => (
          <button key={n.key} className={page === n.key ? 'active' : ''} onClick={() => setPage(n.key)}>
            {n.label}
          </button>
        ))}
        <div className="spacer" />
        <div className="userbox">
          <div style={{ color: '#e3e9f0', fontSize: 13 }}>{user.displayName}</div>
          <div>角色：{user.role}</div>
          <div>辖区：{user.homeDistrict || '全市'}</div>
          <div>密级：{user.clearance}</div>
          <div>目的：{user.purposes.join(' / ')}</div>
          <button style={{ marginTop: 10, color: '#ffb4ab' }} onClick={() => { clearSession(); setUser(null) }}>
            退出登录
          </button>
        </div>
      </aside>
      <main className="main">
        {page === 'query' && <QueryPage />}
        {page === 'explain' && <ExplainPage />}
        {page === 'catalog' && <CatalogPage />}
        {page === 'mapping' && <MappingPage />}
        {page === 'forms' && <FormsPage />}
        {page === 'offline' && <OfflinePage />}
        {page === 'history' && <HistoryPage />}
        {page === 'governance' && <GovernancePage />}
      </main>
    </div>
  )
}

export async function loginApi(username: string, password: string) {
  return api<{ token: string; user: UserInfo }>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  })
}
