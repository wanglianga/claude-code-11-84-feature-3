// 离线合并 WebWorker：
// 1) 以“字段ID + 基线目录版本”比对本地基线与当前目录定义；
// 2) 语义（definition/value_domain/unit）或敏感级并发变化 → 标记冲突待决，不发起正式合并；
// 3) 无冲突时调用后端 /api/offline/sync（服务端独立复核），回写合并结果。
import { idb, type OfflineDraft, type BaselineCache } from '../db'

export interface SyncJob {
  type: 'SYNC_ONE' | 'SYNC_ALL'
  clientId?: string
  currentBaseline: BaselineCache
  token: string
}

export interface SyncOutcome {
  type: 'SYNC_OUTCOME'
  clientId: string
  status: OfflineDraft['syncStatus']
  conflicts: Array<Record<string, unknown>>
  mergedInstanceId?: string | null
  serverMessage?: string
}

interface ConflictRow {
  [key: string]: unknown
  fieldId: string
  type: 'SEMANTIC_CHANGED' | 'SENSITIVITY_CHANGED' | 'FIELD_REMOVED' | 'FIELD_UNKNOWN_AT_BASELINE'
  detail: string
  oldVersion?: number
  newVersion?: number
}

function detectLocalConflicts(draft: OfflineDraft, current: BaselineCache): ConflictRow[] {
  const conflicts: ConflictRow[] = []
  if (draft.baseCatalogVersion >= current.catalogVersion) return conflicts
  const currentById = new Map(current.fields.map((f) => [f.fieldId, f]))
  // 基线定义从草案创建时缓存的基线中取；这里用当前基线库中旧版本行近似（Worker 内不保存历史快照时由调用方传入）
  for (const fieldId of Object.keys(draft.values)) {
    const cur = currentById.get(fieldId)
    if (!cur) {
      conflicts.push({ fieldId, type: 'FIELD_REMOVED', detail: '字段已从当前目录移除/弃用' })
      continue
    }
    if (cur.version > draft.baseCatalogVersion) {
      // 无法在 Worker 内直接拿到旧版本定义时，按版本跃升标记“需服务端复核”，
      // 服务端 OfflineService 持有完整历史，会给出精确 SEMANTIC/SENSITIVITY 判定
      conflicts.push({
        fieldId,
        type: 'SEMANTIC_CHANGED',
        detail: `字段在 v${draft.baseCatalogVersion} 之后发布新版本 v${cur.version}，转服务端语义复核`,
        oldVersion: draft.baseCatalogVersion,
        newVersion: cur.version
      })
    }
  }
  return conflicts
}

async function syncOne(draft: OfflineDraft, current: BaselineCache, token: string): Promise<SyncOutcome> {
  const localConflicts = detectLocalConflicts(draft, current)
  if (localConflicts.length > 0) {
    const pending: OfflineDraft = { ...draft, syncStatus: 'PENDING_CONFLICT', conflicts: localConflicts }
    await idb.putDraft(pending)
    return {
      type: 'SYNC_OUTCOME',
      clientId: draft.clientId,
      status: 'PENDING_CONFLICT',
      conflicts: localConflicts
    }
  }
  try {
    const updating: OfflineDraft = { ...draft, syncStatus: 'SYNCING' }
    await idb.putDraft(updating)
    const resp = await fetch('/api/offline/sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Auth-Token': token },
      body: JSON.stringify({
        clientId: draft.clientId,
        caseId: draft.caseId,
        district: draft.district,
        templateId: draft.templateId,
        inspectDate: draft.inspectDate,
        values: draft.values
      })
    })
    const body = await resp.json()
    if (!resp.ok) throw new Error(body.error || `HTTP ${resp.status}`)
    const status = body.status as OfflineDraft['syncStatus']
    const merged: OfflineDraft = {
      ...draft,
      syncStatus: status,
      conflicts: body.conflicts || [],
      mergedInstanceId: body.mergedInstanceId ?? null,
      serverMessage: body.instruction
    }
    await idb.putDraft(merged)
    return {
      type: 'SYNC_OUTCOME',
      clientId: draft.clientId,
      status,
      conflicts: body.conflicts || [],
      mergedInstanceId: body.mergedInstanceId ?? null,
      serverMessage: body.instruction
    }
  } catch (e) {
    const failed: OfflineDraft = {
      ...draft,
      syncStatus: 'FAILED',
      serverMessage: e instanceof Error ? e.message : String(e)
    }
    await idb.putDraft(failed)
    return {
      type: 'SYNC_OUTCOME',
      clientId: draft.clientId,
      status: 'FAILED',
      conflicts: [],
      serverMessage: failed.serverMessage
    }
  }
}

self.onmessage = async (ev: MessageEvent<SyncJob>) => {
  const job = ev.data
  const drafts = await idb.listDrafts()
  const targets = job.clientId ? drafts.filter((d) => d.clientId === job.clientId) : drafts
  for (const d of targets) {
    const outcome = await syncOne(d, job.currentBaseline, job.token)
    ;(self as unknown as Worker).postMessage(outcome)
  }
}
