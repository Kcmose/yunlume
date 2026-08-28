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

const engines = ref<AdminSearchEngine[]>([])
const loading = ref(true)
const submitting = ref(false)
const dialogVisible = ref(false)
const editing = ref<AdminSearchEngine | null>(null)
const keyword = ref('')
const settingDefaultId = ref<AdminSearchEngine['id'] | null>(null)
const savingSort = ref(false)
const sortDraft = ref<Record<string, number>>({})

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
  sortDraft.value[String(engine.id)] = Math.max(0, value ?? 0)
}

function iconUrl(engine: AdminSearchEngine): string {
  const icon = engine.icon?.trim()
  return icon && /^https?:\/\//i.test(icon) ? icon : ''
}

function iconMark(engine: AdminSearchEngine): string {
  const icon = engine.icon?.trim()
  return icon && icon.length <= 3 && !/^https?:\/\//i.test(icon)
    ? icon
    : engine.name.slice(0, 1).toUpperCase()
}

async function load() {
  loading.value = true
  try {
    engines.value = await getSearchEngines()
    sortDraft.value = Object.fromEntries(
      engines.value.map((engine) => [String(engine.id), engine.sortOrder]),
    )
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '搜索引擎加载失败')
  } finally {
    loading.value = false
  }
}

async function saveSort() {
  if (!sortChanged.value || savingSort.value) return
  savingSort.value = true
  try {
    engines.value = await sortSearchEngines(
      engines.value.map((engine) => ({ id: engine.id, sortOrder: draftOrder(engine) })),
    )
    sortDraft.value = Object.fromEntries(
      engines.value.map((engine) => [String(engine.id), engine.sortOrder]),
    )
    ElMessage.success('搜索引擎排序已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '排序保存失败')
  } finally {
    savingSort.value = false
  }
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(row: AdminSearchEngine) {
  editing.value = row
  dialogVisible.value = true
}

async function save(payload: SearchEnginePayload) {
  submitting.value = true
  try {
    if (editing.value) await updateSearchEngine(editing.value.id, payload)
    else await createSearchEngine(payload)
    ElMessage.success(editing.value ? '搜索引擎已更新' : '搜索引擎已创建')
    dialogVisible.value = false
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
    await setSearchEngineVisible(row.id, row.visible)
    ElMessage.success(row.visible ? '搜索引擎已启用' : '搜索引擎已停用')
    await load()
  } catch (error) {
    row.visible = !row.visible
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
                @click="makeDefault(row)"
              >
                设为默认
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="90">
            <template #default="{ row }">
              <el-switch v-model="row.visible" :aria-label="`${row.name}启用状态`" @change="toggleVisible(row)" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="right">
            <template #default="{ row }">
              <el-button circle :icon="Edit" :aria-label="`编辑搜索引擎${row.name}`" @click="openEdit(row)" />
              <el-button
                circle
                type="danger"
                plain
                :icon="Delete"
                :aria-label="`删除搜索引擎${row.name}`"
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
                :aria-label="`${row.name}启用状态`"
                @change="toggleVisible(row)"
              />
              <el-button
                v-if="!row.isDefault"
                size="small"
                :aria-label="`将${row.name}设为默认搜索引擎`"
                :loading="String(settingDefaultId) === String(row.id)"
                @click="makeDefault(row)"
              >
                设默认
              </el-button>
              <el-button circle size="small" :icon="Edit" :aria-label="`编辑搜索引擎${row.name}`" @click="openEdit(row)" />
              <el-button
                circle
                size="small"
                type="danger"
                plain
                :icon="Delete"
                :aria-label="`删除搜索引擎${row.name}`"
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
