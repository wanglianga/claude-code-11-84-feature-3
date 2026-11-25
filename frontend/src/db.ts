// IndexedDB 封装：离线草案与目录基线缓存
const DB_NAME = 'field-gov-db'
const DB_VERSION = 1
const STORE_DRAFTS = 'drafts'
const STORE_BASELINE = 'baseline'

export interface OfflineDraft {
  clientId: string
  caseId: string
  district: string
  templateId: string
  inspectDate: string
  baseCatalogVersion: number
  values: Record<string, string>
  createdAt: string
  syncStatus: 'LOCAL' | 'SYNCING' | 'MERGED' | 'PENDING_CONFLICT' | 'REBASED' | 'FAILED'
  conflicts?: Array<Record<string, unknown>>
  mergedInstanceId?: string | null
  serverMessage?: string
}

export interface BaselineCache {
  catalogVersion: number
  fields: Array<{
    fieldId: string
    version: number
    definition: string
    valueDomain: Record<string, unknown>
    unit?: string | null
    sensitivity: string
    status: string
    supersededBy?: string | null
  }>
  fetchedAt: string
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE_DRAFTS)) {
        db.createObjectStore(STORE_DRAFTS, { keyPath: 'clientId' })
      }
      if (!db.objectStoreNames.contains(STORE_BASELINE)) {
        db.createObjectStore(STORE_BASELINE, { keyPath: 'catalogVersion' })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function tx<T>(store: string, mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const t = db.transaction(store, mode)
    const req = fn(t.objectStore(store))
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

export const idb = {
  async putDraft(draft: OfflineDraft): Promise<void> {
    await tx(STORE_DRAFTS, 'readwrite', (s) => s.put(draft))
  },
  async listDrafts(): Promise<OfflineDraft[]> {
    return (await tx(STORE_DRAFTS, 'readonly', (s) => s.getAll())) as OfflineDraft[]
  },
  async deleteDraft(clientId: string): Promise<void> {
    await tx(STORE_DRAFTS, 'readwrite', (s) => s.delete(clientId))
  },
  async putBaseline(b: BaselineCache): Promise<void> {
    await tx(STORE_BASELINE, 'readwrite', (s) => s.put(b))
  },
  async getBaseline(version: number): Promise<BaselineCache | undefined> {
    return (await tx(STORE_BASELINE, 'readonly', (s) => s.get(version))) as BaselineCache | undefined
  },
  async listBaselines(): Promise<BaselineCache[]> {
    return (await tx(STORE_BASELINE, 'readonly', (s) => s.getAll())) as BaselineCache[]
  }
}
