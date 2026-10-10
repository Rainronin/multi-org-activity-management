import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchMyOrganizations } from '../features/organization/api'
import { useSessionStore } from './session'
import type { OrganizationDirectoryItem } from '../features/organization/types'

/**
 * 本人组织目录（前端架构 5.1）：仅缓存目录摘要，按 orgId 索引可查询；
 * 当前组织永远由 URL 决定，这里不保存可写的 currentOrgId。
 * 目录回写核对发起时的会话代次，旧会话的迟到响应不回填（R02）。
 */
export const useOrganizationsStore = defineStore('organizations', () => {
  const items = ref<OrganizationDirectoryItem[]>([])
  const loaded = ref(false)
  let requestId = 0

  async function fetchDirectory(force = false): Promise<void> {
    if (loaded.value && !force) {
      return
    }
    const session = useSessionStore()
    const gen = session.capture()
    const currentRequest = ++requestId
    const responseItems = await fetchMyOrganizations()
    if (gen !== session.generation || currentRequest !== requestId) {
      return
    }
    items.value = responseItems
    loaded.value = true
  }

  function clear(): void {
    requestId++
    items.value = []
    loaded.value = false
  }

  return { items, loaded, fetchDirectory, clear }
})
