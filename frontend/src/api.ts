// 前端共享类型与 API 封装
export interface UserInfo {
  username: string
  displayName: string
  role: string
  homeDistrict: string
  clearance: string
  purposes: string[]
}

export interface FieldDef {
  fieldId: string
  version: number
  displayName: string
  definition: string
  valueDomain: Record<string, unknown>
  unit?: string | null
  granularity?: string | null
  eventTimeMeaning?: string | null
  applicableDistricts: string[]
  sensitivity: string
  validFrom: string
  validTo?: string | null
  status: string
  namespace: string
  supersededBy?: string | null
}

export interface MappingValue {
  sourceValue: string
  targetValue?: string | null
  transform?: string | null
  evidence: string
  equivalent: boolean
}

export interface MappingContract {
  contractId: string
  sourceFieldId: string
  targetFieldId: string
  sourceDistrict: string
  version: number
  status: string
  effectiveFrom: string
  effectiveTo?: string | null
  evidenceNote: string
  reviewedBy?: string | null
  values: MappingValue[]
}

export interface FormInstance {
  instanceId: string
  caseId: string
  templateId: string
  district: string
  catalogVersion: number
  inspectDate: string
  values: Record<string, string>
  origin?: string
  submitter?: string
}

export interface GroupOutcome {
  group: string
  count: number | null
  suppressed: boolean
  reason: string | null
}

export interface DisclosureResult {
  groups: GroupOutcome[]
  total: number | null
  totalSuppressed: boolean
  totalReason: string | null
  rawTotal: number
  publishedGroupCount: number
  suppressedGroupCount: number
  threshold: number
}

export interface QueryResult {
  asOf: string
  catalogVersion: number
  purpose: string
  groupBy: string[]
  disclosure: DisclosureResult
  includedCases: string[]
  caseDiagnostics: Array<Record<string, unknown>>
  compileWarnings: string[]
  versionSnapshot: Record<string, unknown>
  queryId: number
  suppressionThreshold: number
}

export interface QueryRequest {
  purpose: string
  asOf?: string
  catalogVersion?: number
  districts?: string[]
  filters: Array<{ field: string; op: string; value: string | number }>
  groupBy: string[]
  includeTotal: boolean
}

const TOKEN_KEY = 'fg_token'
const USER_KEY = 'fg_user'

export function saveSession(token: string, user: UserInfo) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getUser(): UserInfo | null {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? (JSON.parse(raw) as UserInfo) : null
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined)
  }
  const token = getToken()
  if (token) headers['X-Auth-Token'] = token
  const resp = await fetch(`/api${path}`, { ...options, headers })
  const body = await resp.json().catch(() => ({}))
  if (!resp.ok) {
    throw new Error((body as { error?: string }).error || `HTTP ${resp.status}`)
  }
  return body as T
}
