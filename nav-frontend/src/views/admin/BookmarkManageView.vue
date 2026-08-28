<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Delete, Edit, FolderOpened, Plus, Search, Sort } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeading from '@/components/admin/PageHeading.vue'
import BookmarkFormDialog from '@/components/admin/BookmarkFormDialog.vue'
import SortOrderDialog from '@/components/admin/SortOrderDialog.vue'
import {
  batchMoveBookmarks,
  createBookmark,
  deleteBookmark,
  getBookmarks,
  setBookmarkVisible,
  sortBookmarks,
  updateBookmark,
} from '@/api/bookmark.api'
import { getCategories } from '@/api/category.api'
import type { Bookmark, BookmarkPayload } from '@/types/bookmark'
import type { Category } from '@/types/category'
import type { EntityId, SortOrderItem } from '@/types/common'
import {
  entityKey,
  mergeScopedSelection,
  navigationIconLabel,
  navigationIconUrl,
  reconcileSelectedKeys,
  selectionAfterBatchRequest,
} from '@/utils/adminNavigationManage'

interface BookmarkTableInstance {
  clearSelection: () => void
  toggleRowSelection: (row: Bookmark, selected?: boolean) => void
}

const bookmarks = ref<Bookmark[]>([])
const categories = ref<Category[]>([])
const loading = ref(true)
const submitting = ref(false)
const dialogVisible = ref(false)
const editing = ref<Bookmark | null>(null)
const keyword = ref('')
const selectedCategory = ref<EntityId | ''>('')
const tableRef = ref<BookmarkTableInstance>()
const selectedKeys = ref<string[]>([])
const moveTargetCategory = ref<EntityId | ''>('')
const moving = ref(false)
const sortVisible = ref(false)
const savingSort = ref(false)
let restoringTableSelection = false

const categoryMap = computed(() => new Map(categories.value.map((item) => [entityKey(item.id), item.name])))
const filtered = computed(() => {
  const value = keyword.value.trim().toLocaleLowerCase()
  return bookmarks.value.filter((item) => {
    const inCategory = selectedCategory.value === '' || entityKey(item.categoryId) === entityKey(selectedCategory.value)
    const matches = !value || [item.name, item.description, item.url].some((field) => field.toLocaleLowerCase().includes(value))
    return inCategory && matches
  })
})
const selectedKeySet = computed(() => new Set(selectedKeys.value))
const selectedBookmarks = computed(() => bookmarks.value.filter((item) => selectedKeySet.value.has(entityKey(item.id))))
const selectedCount = computed(() => selectedBookmarks.value.length)
const visibleSelectedCount = computed(() => filtered.value.filter((item) => selectedKeySet.value.has(entityKey(item.id))).length)
const hiddenSelectedCount = computed(() => selectedCount.value - visibleSelectedCount.value)
const allFilteredSelected = computed(() => filtered.value.length > 0 && visibleSelectedCount.value === filtered.value.length)
const someFilteredSelected = computed(() => visibleSelectedCount.value > 0 && !allFilteredSelected.value)
const selectedCategoryName = computed(() => selectedCategory.value === '' ? '' : categoryMap.value.get(entityKey(selectedCategory.value)) || '')
const categoryBookmarks = computed(() => {
  if (selectedCategory.value === '') return []
  return bookmarks.value
    .filter((item) => entityKey(item.categoryId) === entityKey(selectedCategory.value))
    .sort((left, right) => left.sortOrder - right.sortOrder || entityKey(left.id).localeCompare(entityKey(right.id)))
})
const bookmarkSortItems = computed(() => categoryBookmarks.value.map((item) => ({
  id: item.id,
  label: item.name,
  icon: navigationIconLabel(item.icon, [...item.name][0] || '▱'),
  iconUrl: navigationIconUrl(item.icon),
  meta: `${item.visible ? '前台展示' : '已隐藏'} · 当前排序值 ${item.sortOrder}`,
})))
const moveTargetName = computed(() => moveTargetCategory.value === '' ? '' : categoryMap.value.get(entityKey(moveTargetCategory.value)) || '')
const movableSelectedCount = computed(() => {
  if (moveTargetCategory.value === '') return 0
  return selectedBookmarks.value.filter((item) => entityKey(item.categoryId) !== entityKey(moveTargetCategory.value)).length
})
const canMove = computed(() => selectedCount.value > 0
  && moveTargetCategory.value !== ''
  && movableSelectedCount.value > 0
  && !moving.value)

async function load() {
  loading.value = true
  try {
    const [bookmarkData, categoryData] = await Promise.all([getBookmarks(), getCategories()])
    bookmarks.value = bookmarkData
    categories.value = categoryData
    selectedKeys.value = reconcileSelectedKeys(selectedKeys.value, bookmarkData.map((item) => item.id))
    if (
      moveTargetCategory.value !== ''
      && !categoryData.some((item) => entityKey(item.id) === entityKey(moveTargetCategory.value))
    ) {
      moveTargetCategory.value = ''
    }
    await nextTick()
    restoreTableSelection()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '书签加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  if (!categories.value.length) {
    ElMessage.warning('请先创建一个分类')
    return
  }
  editing.value = null
  dialogVisible.value = true
}

function openEdit(row: Bookmark) {
  editing.value = row
  dialogVisible.value = true
}

async function save(payload: BookmarkPayload) {
  submitting.value = true
  try {
    if (editing.value) await updateBookmark(editing.value.id, payload)
    else await createBookmark(payload)
    ElMessage.success(editing.value ? '书签已更新' : '书签已创建')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function remove(row: Bookmark) {
  try {
    await ElMessageBox.confirm(`确定删除书签“${row.name}”吗？此操作无法撤销。`, '删除书签', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
    await deleteBookmark(row.id)
    ElMessage.success('书签已删除')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

async function toggleVisible(row: Bookmark) {
  try {
    await setBookmarkVisible(row.id, row.visible)
  } catch (error) {
    row.visible = !row.visible
    ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
  }
}

function restoreTableSelection() {
  const table = tableRef.value
  if (!table) return
  restoringTableSelection = true
  table.clearSelection()
  const selected = selectedKeySet.value
  filtered.value.forEach((row) => table.toggleRowSelection(row, selected.has(entityKey(row.id))))
  void nextTick(() => {
    restoringTableSelection = false
  })
}

function scheduleTableSelectionRestore() {
  restoringTableSelection = true
  void nextTick(() => {
    if (!tableRef.value) {
      restoringTableSelection = false
      return
    }
    restoreTableSelection()
  })
}

function onTableSelectionChange(rows: Bookmark[]) {
  if (restoringTableSelection) return
  selectedKeys.value = mergeScopedSelection(
    selectedKeys.value,
    filtered.value.map((item) => item.id),
    rows.map((item) => item.id),
  )
}

function toggleSelected(row: Bookmark, selected: boolean) {
  selectedKeys.value = mergeScopedSelection(selectedKeys.value, [row.id], selected ? [row.id] : [])
  scheduleTableSelectionRestore()
}

function toggleFiltered(selected: boolean) {
  selectedKeys.value = mergeScopedSelection(
    selectedKeys.value,
    filtered.value.map((item) => item.id),
    selected ? filtered.value.map((item) => item.id) : [],
  )
  scheduleTableSelectionRestore()
}

function clearBookmarkSelection() {
  selectedKeys.value = selectionAfterBatchRequest(selectedKeys.value, true)
  tableRef.value?.clearSelection()
}

async function moveSelectedBookmarks() {
  if (!canMove.value || moving.value) return
  const targetCategory = moveTargetCategory.value
  const targetName = moveTargetName.value
  const movingCount = movableSelectedCount.value
  const alreadyThere = selectedCount.value - movingCount
  const detail = alreadyThere > 0
    ? `其中 ${alreadyThere} 个已在该分类，将保持原位。`
    : '移动后将保留这些书签原有的显示状态。'

  try {
    await ElMessageBox.confirm(
      `将已选择的 ${selectedCount.value} 个书签移动到“${targetName}”吗？${detail}`,
      '批量移动书签',
      {
        type: 'warning',
        confirmButtonText: '确认移动',
        cancelButtonText: '取消',
      },
    )
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '确认操作失败')
    }
    return
  }

  moving.value = true
  try {
    await batchMoveBookmarks(selectedBookmarks.value.map((item) => item.id), targetCategory)
    clearBookmarkSelection()
    moveTargetCategory.value = ''
    await load()
    ElMessage.success(`已将 ${movingCount} 个书签移动到“${targetName}”`)
  } catch (error) {
    selectedKeys.value = selectionAfterBatchRequest(selectedKeys.value, false)
    ElMessage.error(error instanceof Error ? error.message : '批量移动失败，已保留当前选择')
  } finally {
    moving.value = false
  }
}

function openBookmarkSort() {
  if (selectedCategory.value === '') {
    ElMessage.info('请先选择一个具体分类，再调整该分类内的书签顺序')
    return
  }
  if (categoryBookmarks.value.length < 2) {
    ElMessage.info('当前分类至少需要两个书签才能调整顺序')
    return
  }
  sortVisible.value = true
}

async function saveBookmarkSort(items: SortOrderItem[]) {
  if (savingSort.value || selectedCategory.value === '') return
  const categoryKeys = new Set(categoryBookmarks.value.map((item) => entityKey(item.id)))
  if (items.length !== categoryKeys.size || items.some((item) => !categoryKeys.has(entityKey(item.id)))) {
    ElMessage.error('分类数据已变化，请关闭弹窗后重试')
    return
  }

  savingSort.value = true
  try {
    bookmarks.value = await sortBookmarks(items)
    sortVisible.value = false
    ElMessage.success(`“${selectedCategoryName.value}”内的书签排序已保存`)
    await nextTick()
    restoreTableSelection()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '书签排序保存失败')
  } finally {
    savingSort.value = false
  }
}

watch(
  () => filtered.value.map((item) => entityKey(item.id)).join('|'),
  scheduleTableSelectionRestore,
  { flush: 'sync' },
)

onMounted(() => void load())
</script>

<template>
  <div class="admin-page bookmark-manage-page">
    <PageHeading title="书签管理" description="维护站点的每一个快捷入口，支持按分类筛选、批量移动与分类内排序。" eyebrow="BOOKMARKS">
      <el-button type="primary" @click="openCreate"><Plus /> 新增书签</el-button>
    </PageHeading>

    <section v-loading="loading" class="admin-panel data-panel navigation-manage-panel">
      <header class="data-panel__toolbar data-panel__toolbar--bookmarks">
        <div><h2>全部书签</h2><p>当前筛选 {{ filtered.length }} / {{ bookmarks.length }} 条</p></div>
        <div class="data-panel__filters bookmark-manage-filters">
          <el-select v-model="selectedCategory" placeholder="全部分类" clearable>
            <el-option label="全部分类" value="" />
            <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-input v-model="keyword" clearable placeholder="搜索名称、描述或 URL" :prefix-icon="Search" />
          <el-tooltip
            :disabled="selectedCategory !== ''"
            content="请先选择一个具体分类，排序不会作用于全部分类或关键词子集"
            placement="top"
          >
            <span class="bookmark-sort-trigger">
              <el-button
                :icon="Sort"
                :disabled="selectedCategory === '' || categoryBookmarks.length < 2"
                @click="openBookmarkSort"
              >
                {{ selectedCategoryName ? `排序：${selectedCategoryName}` : '选择分类后排序' }}
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </header>

      <div class="bookmark-bulk-bar" :class="{ 'is-active': selectedCount > 0 }">
        <div class="bookmark-bulk-bar__selection">
          <el-checkbox
            :model-value="allFilteredSelected"
            :indeterminate="someFilteredSelected"
            :disabled="!filtered.length || moving"
            aria-label="选择或取消当前筛选内的全部书签"
            @change="toggleFiltered(Boolean($event))"
          />
          <span>
            <strong>{{ selectedCount ? `已选择 ${selectedCount} 个书签` : '批量移动' }}</strong>
            <small v-if="hiddenSelectedCount > 0">其中 {{ hiddenSelectedCount }} 个不在当前筛选中</small>
            <small v-else-if="selectedCount">当前筛选中已选择 {{ visibleSelectedCount }} 个</small>
            <small v-else>勾选表格或移动卡片中的书签后，可一次移动到目标分类</small>
          </span>
        </div>
        <div class="bookmark-bulk-bar__actions">
          <el-select
            v-model="moveTargetCategory"
            clearable
            placeholder="选择目标分类"
            :disabled="!selectedCount || moving"
            aria-label="批量移动目标分类"
          >
            <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-button
            type="primary"
            :icon="FolderOpened"
            :loading="moving"
            :disabled="!canMove"
            @click="moveSelectedBookmarks"
          >
            {{ moveTargetCategory !== '' && selectedCount && !movableSelectedCount ? '已在该分类' : '移动所选' }}
          </el-button>
          <el-button v-if="selectedCount" :disabled="moving" @click="clearBookmarkSelection">清空全部</el-button>
        </div>
      </div>

      <div class="navigation-manage-table-wrap">
        <el-table
          ref="tableRef"
          :data="filtered"
          row-key="id"
          class="admin-data-table bookmark-manage-table"
          @selection-change="onTableSelectionChange"
        >
          <el-table-column type="selection" width="52" reserve-selection />
          <el-table-column label="书签" min-width="250">
            <template #default="{ row }">
              <div class="bookmark-cell"><span><img v-if="navigationIconUrl(row.icon)" :src="navigationIconUrl(row.icon)" alt="" referrerpolicy="no-referrer" /><template v-else>{{ navigationIconLabel(row.icon, [...row.name][0] || '▱') }}</template></span><div><strong>{{ row.name }}</strong><small>{{ row.url }}</small></div></div>
            </template>
          </el-table-column>
          <el-table-column label="所属分类" width="150">
            <template #default="{ row }"><el-tag effect="plain" round>{{ categoryMap.get(entityKey(row.categoryId)) || '未分类' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="85" />
          <el-table-column label="前台展示" width="110">
            <template #default="{ row }"><el-switch v-model="row.visible" :aria-label="`${row.name}前台展示`" @change="toggleVisible(row)" /></template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="right">
            <template #default="{ row }">
              <el-button circle :icon="Edit" :aria-label="`编辑书签${row.name}`" @click="openEdit(row)" />
              <el-button circle type="danger" plain :icon="Delete" :aria-label="`删除书签${row.name}`" @click="remove(row)" />
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无符合条件的书签" /></template>
        </el-table>
      </div>

      <div class="navigation-manage-mobile-list bookmark-manage-mobile-list">
        <article v-for="row in filtered" :key="row.id" class="navigation-manage-card bookmark-manage-card">
          <header>
            <el-checkbox
              :model-value="selectedKeySet.has(entityKey(row.id))"
              :disabled="moving"
              :aria-label="`选择书签${row.name}`"
              @change="toggleSelected(row, Boolean($event))"
            />
            <div class="bookmark-cell"><span><img v-if="navigationIconUrl(row.icon)" :src="navigationIconUrl(row.icon)" alt="" referrerpolicy="no-referrer" /><template v-else>{{ navigationIconLabel(row.icon, [...row.name][0] || '▱') }}</template></span><div><strong>{{ row.name }}</strong><small>{{ row.url }}</small></div></div>
          </header>
          <div class="navigation-manage-card__meta">
            <el-tag effect="plain" round>{{ categoryMap.get(entityKey(row.categoryId)) || '未分类' }}</el-tag>
            <span>排序值 {{ row.sortOrder }}</span>
          </div>
          <footer>
            <label><span>前台展示</span><el-switch v-model="row.visible" :aria-label="`${row.name}前台展示`" @change="toggleVisible(row)" /></label>
            <div>
              <el-button circle :icon="Edit" :aria-label="`编辑书签${row.name}`" @click="openEdit(row)" />
              <el-button circle type="danger" plain :icon="Delete" :aria-label="`删除书签${row.name}`" @click="remove(row)" />
            </div>
          </footer>
        </article>
        <el-empty v-if="!filtered.length" description="暂无符合条件的书签" />
      </div>
    </section>

    <BookmarkFormDialog
      v-model="dialogVisible"
      :bookmark="editing"
      :categories="categories"
      :submitting="submitting"
      @submit="save"
    />
    <SortOrderDialog
      v-model="sortVisible"
      :title="selectedCategoryName ? `调整“${selectedCategoryName}”内的书签顺序` : '调整书签顺序'"
      :description="`本次包含该分类全部 ${categoryBookmarks.length} 个书签（含隐藏项），不受关键词筛选影响。`"
      :items="bookmarkSortItems"
      :submitting="savingSort"
      empty-text="当前分类暂无可排序书签"
      @submit="saveBookmarkSort"
    />
  </div>
</template>
