import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchMyOrganizations } from '../features/organization/api'
import type { OrganizationDirectoryItem } from '../features/organization/types'

/**
 * 本人组织目录（前端架构 5.1）：仅缓存目录摘要，按 orgId 索引可查询；
 * 当前组织永远由 URL 决定，这里不保存可写的 currentOrgId。
 */
export const useOrganizationsStore = defineStore('organizations', () => {
  const items = ref<OrganizationDirectoryItem[]>([])
  const loaded = ref(false)

  async function fetchDirectory(force = false): Promise<void> {
    if (loaded.value && !force) {
      return
    }
    items.value = await fetchMyOrganizations()
    loaded.value = true
  }

  function clear(): void {
    items.value = []
    loaded.value = false
  }

  return { items, loaded, fetchDirectory, clear }
})
