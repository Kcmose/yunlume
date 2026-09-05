<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeading from '@/components/admin/PageHeading.vue'
import SearchEngineDialog from '@/components/admin/SearchEngineDialog.vue'
import {
  createSearchEngine,
  deleteSearchEngine,
  getSearchEngines,
  setDefaultSearchEngine,
  setSearchEngineVisible,
  sortSearchEngines,
  updateSearchEngine,
} from '@/api/searchEngine.api'
import type {
  AdminSearchEngine,
  SearchEnginePayload,
} from '@/types/searchEngine'
import { commitVisibleChange } from '@/utils/visibilityMutation'
import {
  searchEngineIconUrl as iconUrl,
  searchEngineMark as iconMark,
} from '@/utils/searchEnginePicker'

const engines = ref<AdminSearchEngine[]>([])
const loading = ref(true)
const submitting = ref(false)
const dialogVisible = ref(false)
const editing = ref<AdminSearchEngine | null>(null)
const keyword = ref('')
const settingDefaultId = ref<AdminSearchEngine['id'] | null>(null)
const savingSort = ref(false)
const sortDraft = ref<Record<string, number>>({})
const visibilityUpdatingIds = ref(new Set<string>())
const sortDraftVersions: Record<string, number> = {}
let nextDraftVersion = 0
let loadVersion = 0
let dialogGeneration = 0
let pendingSortVersions: Record<string, number> | null = null
let acknowledgedSortVersions: Record<string, number> | null = null

function visibilityUpdating(row: AdminSearchEngine) {
  return visibilityUpdatingIds.value.has(String(row.id))
}

const filtered = computed(() => {
  const value = keyword.value.trim().toLocaleLowerCase()
  const result = value
    ? engines.value.filter((item) =>
        [item.name, item.placeholder ?? '', item.searchUrl].some((field) =>
          field.toLocaleLowerCase().includes(value),
        ),
      )
    : engines.value

  return [...result].sort(
    (left, right) =>
      left.sortOrder - right.sortOrder || String(left.id).localeCompare(String(right.id)),
  )
})

const sortChanged = computed(() =>
  engines.value.some((engine) => sortDraft.value[String(engine.id)] !== engine.sortOrder),
)

function draftOrder(engine: AdminSearchEngine): number {
  return sortDraft.value[String(engine.id)] ?? engine.sortOrder
}

function updateDraft(engine: AdminSearchEngine, value: number | undefined) {
  const key = String(engine.id)
  sortDraft.value[key] = Math.max(0, value ?? 0)
  sortDraftVersions[key] = ++nextDraftVersion
}

function applySnapshot(next: AdminSearchEngine[]) {
  const previousOrders = new Map(engines.value.map((engine) => [String(engine.id), engine.sortOrder]))
  const nextDraft: Record<string, number> = {}
  for (const engine of next) {
    const key = String(engine.id)
    const hasDraft = Object.prototype.hasOwnProperty.call(sortDraft.value, key)
    const currentVersion = sortDraftVersions[key] ?? 0
    const acknowledged = acknowledgedSortVersions?.[key]
    // 已提交草稿仅在用户未再次输入时清理；改回旧服务器值也属于新的编辑。
    const preserve = acknowledged !== undefined
      ? currentVersion !== acknowledged
      : sortDraft.value[key] !== previousOrders.get(key)
        || (pendingSortVersions !== null && currentVersion !== pendingSortVersions[key])
    nextDraft[key] = hasDraft && preserve ? sortDraft.value[key]! : engine.sortOrder
  }
  engines.value = next
  sortDraft.value = nextDraft
  acknowledgedSortVersions = null
  for (const key of Object.keys(sortDraftVersions)) {
    if (!Object.prototype.hasOwnProperty.call(nextDraft, key)) delete sortDraftVersions[key]
  }
}

async function load() {
  const requestVersion = ++loadVersion
  loading.value = true
  try {
    const next = await getSearchEngines()
    if (requestVersion !== loadVersion) return
    applySnapshot(next)
  } catch (error) {
    if (requestVersion === loadVersion) ElMessage.error(error instanceof Error ? error.message : '搜索引擎加载失败')
  } finally {
    if (requestVersion === loadVersion) loading.value = false
  }
}

async function saveSort() {
  if (!sortChanged.value || savingSort.value) return
  savingSort.value = true
  pendingSortVersions = Object.fromEntries(
    engines.value.map((engine) => [String(engine.id), sortDraftVersions[String(engine.id)] ?? 0]),
  )
  try {
    const persisted = await sortSearchEngines(
      engines.value.map((engine) => ({ id: engine.id, sortOrder: draftOrder(engine) })),
    )
    // 即使随后的GET失败，也以已确认的排序判断草稿是否仍有未提交输入。
    // 只原位更新排序，保留并发CRUD/显隐的字段与列表成员。
    const persistedOrders = new Map(persisted.map((engine) => [String(engine.id), engine.sortOrder]))
    for (const engine of engines.value) {
      const order = persistedOrders.get(String(engine.id))
      if (order !== undefined) engine.sortOrder = order
    }
    acknowledgedSortVersions = pendingSortVersions
    // 排序响应可能早于并发CRUD；重新读取列表，并按提交版本合并草稿。
    await load()
    ElMessage.success('搜索引擎排序已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '排序保存失败')
  } finally {
    pendingSortVersions = null
    savingSort.value = false
  }
}

function openCreate() {
  dialogGeneration += 1
  editing.value = null
  dialogVisible.value = true
}

function openEdit(row: AdminSearchEngine) {
  dialogGeneration += 1
  editing.value = row
  dialogVisible.value = true
}

async function save(payload: SearchEnginePayload) {
  if (submitting.value || !dialogVisible.value) return
  const generation = dialogGeneration
  const target = editing.value
  submitting.value = true
  try {
    if (target) await updateSearchEngine(target.id, payload)
    else await createSearchEngine(payload)
    ElMessage.success(target ? '搜索引擎已更新' : '搜索引擎已创建')
    if (generation === dialogGeneration) dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function remove(row: AdminSearchEngine) {
  try {
    await ElMessageBox.confirm(
      row.isDefault
        ? `“${row.name}”是当前默认引擎。删除后系统会自动选择其他可用引擎，是否继续？`
        : `确定删除搜索引擎“${row.name}”吗？此操作无法撤销。`,
      '删除搜索引擎',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
    await deleteSearchEngine(row.id)
    ElMessage.success('搜索引擎已删除')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '删除失败')
    }
  }
}

async function toggleVisible(row: AdminSearchEngine) {
  try {
    const updated = await commitVisibleChange(row, visibilityUpdatingIds.value, async (id, visible) => {
      const persisted = await setSearchEngineVisible(id, visible)
      // 停用默认引擎还会改变其他行；读回完成前保持同一行互斥。
      await load()
      return persisted
    })
    if (!updated) return
    ElMessage.success(row.visible ? '搜索引擎已启用' : '搜索引擎已停用')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
  }
}

async function makeDefault(row: AdminSearchEngine) {
  if (row.isDefault || settingDefaultId.value !== null) return
  settingDefaultId.value = row.id
  try {
    await setDefaultSearchEngine(row.id)
    ElMessage.success(`已将“${row.name}”设为默认搜索引擎`)
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '默认引擎设置失败')
  } finally {
    settingDefaultId.value = null
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="admin-page search-engine-page">
    <PageHeading
      title="搜索引擎管理"
      description="配置公开首页的搜索方式、默认引擎与显示顺序。"
      eyebrow="SEARCH ENGINES"
    >
      <el-button type="primary" @click="openCreate"><Plus /> 新增搜索引擎</el-button>
    </PageHeading>

    <section v-loading="loading" class="admin-panel data-panel">
      <header class="data-panel__toolbar">
        <div>
          <h2>全部搜索引擎</h2>
          <p>共 {{ engines.length }} 个，当前启用 {{ engines.filter((item) => item.visible).length }} 个</p>
        </div>
        <div class="search-engine-toolbar-actions">
          <el-input
            v-model="keyword"
            clearable
            placeholder="搜索名称、提示或地址"
            :prefix-icon="Search"
          />
          <el-button
            type="primary"
            plain
            :disabled="!sortChanged"
            :loading="savingSort"
            @click="saveSort"
          >
            保存排序
          </el-button>
        </div>
      </header>

      <div class="search-engine-table-wrap">
        <el-table :data="filtered" row-key="id" class="admin-data-table search-engine-table">
          <el-table-column label="搜索引擎" min-width="265">
            <template #default="{ row }">
              <div class="search-engine-cell">
                <span class="search-engine-cell__icon">
                  <img
                    v-if="iconUrl(row)"
                    :src="iconUrl(row)"
                    alt=""
                    referrerpolicy="no-referrer"
                  />
                  <template v-else>{{ iconMark(row) }}</template>
                </span>
                <div>
                  <strong>{{ row.name }}</strong>
                  <small>{{ row.searchUrl }}</small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="提示文字" min-width="170">
            <template #default="{ row }">
              <span class="search-engine-placeholder">{{ row.placeholder || '想要搜索什么' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="排序" width="105">
            <template #default="{ row }">
              <el-input-number
                class="search-engine-sort-input"
                :model-value="draftOrder(row)"
                :min="0"
                :max="9999"
                :controls="false"
                size="small"
                :aria-label="`${row.name}排序值`"
                @update:model-value="updateDraft(row, $event)"
              />
            </template>
          </el-table-column>
          <el-table-column label="默认引擎" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.isDefault" type="success" effect="light" round>当前默认</el-tag>
              <el-button
                v-else
                link
                type="primary"
                :aria-label="`将${row.name}设为默认搜索引擎`"
                :loading="String(settingDefaultId) === String(row.id)"
                :disabled="visibilityUpdating(row)"
                @click="makeDefault(row)"
              >
                设为默认
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="90">
            <template #default="{ row }">
              <el-switch v-model="row.visible" :loading="visibilityUpdating(row)" :disabled="visibilityUpdating(row)" :aria-label="`${row.name}启用状态`" @change="toggleVisible(row)" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="right">
            <template #default="{ row }">
              <el-button circle :icon="Edit" :disabled="visibilityUpdating(row)" :aria-label="`编辑搜索引擎${row.name}`" @click="openEdit(row)" />
              <el-button
                circle
                type="danger"
                plain
                :icon="Delete"
                :aria-label="`删除搜索引擎${row.name}`"
                :disabled="visibilityUpdating(row)"
                @click="remove(row)"
              />
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无搜索引擎，点击右上角创建第一个搜索引擎" />
          </template>
        </el-table>
      </div>

      <div class="search-engine-mobile-list">
        <article v-for="row in filtered" :key="row.id" class="search-engine-card">
          <header>
            <div class="search-engine-cell">
              <span class="search-engine-cell__icon">
                <img
                  v-if="iconUrl(row)"
                  :src="iconUrl(row)"
                  alt=""
                  referrerpolicy="no-referrer"
                />
                <template v-else>{{ iconMark(row) }}</template>
              </span>
              <div>
                <strong>{{ row.name }}</strong>
                <small>{{ row.searchUrl }}</small>
              </div>
            </div>
            <div class="search-engine-card__badges">
              <el-tag v-if="row.isDefault" type="success" size="small" round>默认</el-tag>
              <el-tag v-if="!row.visible" type="info" size="small" round>已停用</el-tag>
            </div>
          </header>

          <p>{{ row.placeholder || '想要搜索什么' }}</p>

          <footer>
            <label class="search-engine-card__sort">
              <span>排序</span>
              <el-input-number
                :model-value="draftOrder(row)"
                :min="0"
                :max="9999"
                :controls="false"
                size="small"
                :aria-label="`${row.name}排序值`"
                @update:model-value="updateDraft(row, $event)"
              />
            </label>
            <div class="search-engine-card__controls">
              <el-switch
                v-model="row.visible"
                :loading="visibilityUpdating(row)"
                :disabled="visibilityUpdating(row)"
                :aria-label="`${row.name}启用状态`"
                @change="toggleVisible(row)"
              />
              <el-button
                v-if="!row.isDefault"
                size="small"
                :aria-label="`将${row.name}设为默认搜索引擎`"
                :loading="String(settingDefaultId) === String(row.id)"
                :disabled="visibilityUpdating(row)"
                @click="makeDefault(row)"
              >
                设默认
              </el-button>
              <el-button circle size="small" :icon="Edit" :disabled="visibilityUpdating(row)" :aria-label="`编辑搜索引擎${row.name}`" @click="openEdit(row)" />
              <el-button
                circle
                size="small"
                type="danger"
                plain
                :icon="Delete"
                :aria-label="`删除搜索引擎${row.name}`"
                :disabled="visibilityUpdating(row)"
                @click="remove(row)"
              />
            </div>
          </footer>
        </article>
        <el-empty v-if="!filtered.length" description="暂无符合条件的搜索引擎" />
      </div>
    </section>

    <SearchEngineDialog
      v-model="dialogVisible"
      :engine="editing"
      :submitting="submitting"
      @submit="save"
    />
  </div>
</template>
