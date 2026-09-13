<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElTag } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { useSessionStore } from '../../stores/session'
import { fetchWorkbench, todoKindLabel, workbenchRoleLabel, type Workbench } from './workbench'

// P7.1／P7.2 工作台：按身份展示管理卡片、待办与本人卡片；数字带来源说明，点击进入授权明细。
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const orgId = computed(() => String(route.params.orgId ?? ''))
const workbench = ref<Workbench | null>(null)
const loading = ref(false)
const errorText = ref('')

let requestSeq = 0

async function load(): Promise<void> {
  if (!orgId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const data = await fetchWorkbench(orgId.value)
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    workbench.value = data
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    workbench.value = null
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '工作台数据加载失败，请重试。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch(orgId, () => {
  void load()
})
onMounted(load)

function open(path: string) {
  if (path) {
    void router.push(path)
  }
}

defineExpose({ reload: load })
</script>

<template>
  <section class="workbench">
    <div class="workbench__head">
      <h2>工作台</h2>
      <ElTag
        v-if="workbench"
        size="small"
      >
        {{ workbenchRoleLabel[workbench.role] }}
      </ElTag>
    </div>

    <p
      v-if="loading"
      class="workbench__note"
      aria-live="polite"
    >
      正在加载工作台……
    </p>
    <p
      v-else-if="errorText"
      class="workbench__error"
      role="alert"
    >
      {{ errorText }}
    </p>

    <template v-else-if="workbench">
      <dl
        v-if="workbench.cards.length > 0 || workbench.myCards.length > 0"
        class="workbench__cards"
      >
        <div
          v-for="card in [...workbench.cards, ...workbench.myCards]"
          :key="card.key"
          class="workbench__card"
        >
          <dt>{{ card.label }}</dt>
          <dd class="workbench__value">
            {{ card.value }}
          </dd>
          <p class="workbench__hint">
            {{ card.hint }}
          </p>
          <ElButton
            v-if="card.detailPath"
            size="small"
            link
            @click="open(card.detailPath)"
          >
            {{ card.detailLabel || '查看明细' }}
          </ElButton>
        </div>
      </dl>

      <div class="workbench__todos">
        <h3>待办</h3>
        <ul v-if="workbench.todos.length > 0">
          <li
            v-for="(todo, index) in workbench.todos"
            :key="`${todo.kind}-${index}`"
          >
            <ElTag
              size="small"
              type="warning"
            >
              {{ todoKindLabel[todo.kind] ?? todo.kind }}
            </ElTag>
            <span class="workbench__todo-title">{{ todo.title }}</span>
            <span class="workbench__note">{{ todo.detail }}</span>
            <ElButton
              size="small"
              link
              @click="open(todo.path)"
            >
              处理
            </ElButton>
          </li>
        </ul>
        <p
          v-else
          class="workbench__note"
        >
          暂无待处理事项。
        </p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.workbench {
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
}

.workbench__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-4);
}

.workbench__head h2 {
  font-size: var(--ui-font-size-section);
}

.workbench__note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.workbench__error {
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.workbench__cards {
  display: grid;
  gap: var(--ui-layout-grid-gap);
  grid-template-columns: repeat(auto-fill, minmax(min(100%, var(--ui-layout-card-min-width)), 1fr));
  margin-top: var(--ui-space-4);
}

.workbench__card dt {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.workbench__value {
  margin-top: var(--ui-space-1);
  font-size: var(--ui-font-size-kpi);
  font-variant-numeric: tabular-nums;
}

.workbench__hint {
  margin-top: var(--ui-space-1);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.workbench__todos {
  margin-top: var(--ui-space-6);
}

.workbench__todos h3 {
  font-size: var(--ui-font-size-emphasis);
  margin-bottom: var(--ui-space-2);
}

.workbench__todos ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.workbench__todos li {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  flex-wrap: wrap;
  font-size: var(--ui-font-size-small);
}

.workbench__todo-title {
  font-weight: var(--ui-font-weight-medium);
}
</style>
