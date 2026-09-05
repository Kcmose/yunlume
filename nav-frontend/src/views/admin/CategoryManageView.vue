<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Delete, Edit, Plus, Search, Sort } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeading from '@/components/admin/PageHeading.vue'
import CategoryFormDialog from '@/components/admin/CategoryFormDialog.vue'
import SortOrderDialog from '@/components/admin/SortOrderDialog.vue'
import {
  createCategory,
  deleteCategory,
  getCategories,
  setCategoryVisible,
  sortCategories,
  updateCategory,
} from '@/api/category.api'
import type { Category, CategoryPayload } from '@/types/category'
import type { SortOrderItem } from '@/types/common'
import { navigationIconLabel, navigationIconUrl } from '@/utils/adminNavigationManage'
import { commitVisibleChange } from '@/utils/visibilityMutation'

const categories = ref<Category[]>([])
const loading = ref(true)
const submitting = ref(false)
const dialogVisible = ref(false)
const editing = ref<Category | null>(null)
const keyword = ref('')
const sortVisible = ref(false)
const savingSort = ref(false)
const visibilityUpdatingIds = ref(new Set<string>())

function visibilityUpdating(row: Category) {
  return visibilityUpdatingIds.value.has(String(row.id))
}

const filtered = computed(() => {
  const value = keyword.value.trim().toLocaleLowerCase()
  return value ? categories.value.filter((item) => item.name.toLocaleLowerCase().includes(value)) : categories.value
})
const sortItems = computed(() => categories.value.map((item) => ({
  id: item.id,
  label: item.name,
  icon: navigationIconLabel(item.icon, '✦'),
  iconUrl: navigationIconUrl(item.icon),
  meta: `${item.bookmarkCount ?? 0} 个书签 · ${item.visible ? '前台展示' : '已隐藏'}`,
})))

async function load() {
  loading.value = true
  try {
    categories.value = await getCategories()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分类加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(row: Category) {
  editing.value = row
  dialogVisible.value = true
}

async function save(payload: CategoryPayload) {
  submitting.value = true
  try {
    if (editing.value) await updateCategory(editing.value.id, payload)
    else await createCategory(payload)
    ElMessage.success(editing.value ? '分类已更新' : '分类已创建')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function remove(row: Category) {
  const bookmarkCount = row.bookmarkCount ?? 0
  if (bookmarkCount > 0) {
    try {
      await ElMessageBox.alert(
        `“${row.name}”下还有 ${bookmarkCount} 个书签，暂时不能删除。请先到书签管理中筛选该分类，将书签批量移动或删除。`,
        '分类仍有书签',
        { type: 'warning', confirmButtonText: '我知道了' },
      )
    } catch {
      // 关闭提示无需额外处理。
    }
    return
  }

  try {
    await ElMessageBox.confirm(`确定删除分类“${row.name}”吗？当前包含 0 个书签，此操作无法撤销。`, '删除分类', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
    await deleteCategory(row.id)
    ElMessage.success('分类已删除')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

async function toggleVisible(row: Category) {
  try {
    const updated = await commitVisibleChange(
      row,
      visibilityUpdatingIds.value,
      setCategoryVisible,
    )
    if (updated) ElMessage.success(row.visible ? '分类已展示' : '分类已隐藏')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
  }
}

async function saveSort(items: SortOrderItem[]) {
  if (savingSort.value) return
  savingSort.value = true
  try {
    categories.value = await sortCategories(items)
    sortVisible.value = false
    ElMessage.success('分类排序已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分类排序保存失败')
  } finally {
    savingSort.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="admin-page">
    <PageHeading title="分类管理" description="用清晰的分类组织书签，前台将按照排序值依次展示。" eyebrow="CATEGORIES">
      <el-button :icon="Sort" :disabled="categories.length < 2" @click="sortVisible = true">调整顺序</el-button>
      <el-button type="primary" @click="openCreate"><Plus /> 新增分类</el-button>
    </PageHeading>

    <section v-loading="loading" class="admin-panel data-panel navigation-manage-panel">
      <header class="data-panel__toolbar">
        <div><h2>全部分类</h2><p>共 {{ categories.length }} 个分类</p></div>
        <el-input v-model="keyword" clearable placeholder="搜索分类名称" :prefix-icon="Search" />
      </header>
      <div class="navigation-manage-table-wrap">
        <el-table :data="filtered" row-key="id" class="admin-data-table">
          <el-table-column label="分类" min-width="220">
            <template #default="{ row }">
              <div class="category-cell"><span><img v-if="navigationIconUrl(row.icon)" :src="navigationIconUrl(row.icon)" alt="" referrerpolicy="no-referrer" /><template v-else>{{ navigationIconLabel(row.icon, '✦') }}</template></span><div><strong>{{ row.name }}</strong><small>ID · {{ row.id }}</small></div></div>
            </template>
          </el-table-column>
          <el-table-column prop="bookmarkCount" label="书签数量" width="120">
            <template #default="{ row }"><span class="table-count">{{ row.bookmarkCount ?? 0 }}</span></template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="100" />
          <el-table-column label="前台展示" width="120">
            <template #default="{ row }"><el-switch v-model="row.visible" :loading="visibilityUpdating(row)" :disabled="visibilityUpdating(row)" :aria-label="`${row.name}前台展示`" @change="toggleVisible(row)" /></template>
          </el-table-column>
          <el-table-column label="操作" width="160" align="right">
            <template #default="{ row }">
              <el-button circle :icon="Edit" :disabled="visibilityUpdating(row)" :aria-label="`编辑分类${row.name}`" @click="openEdit(row)" />
              <el-button circle type="danger" plain :icon="Delete" :disabled="visibilityUpdating(row)" :aria-label="`删除分类${row.name}`" @click="remove(row)" />
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无分类，点击右上角创建第一个分类" /></template>
        </el-table>
      </div>

      <div class="navigation-manage-mobile-list">
        <article v-for="row in filtered" :key="row.id" class="navigation-manage-card">
          <header>
            <div class="category-cell"><span><img v-if="navigationIconUrl(row.icon)" :src="navigationIconUrl(row.icon)" alt="" referrerpolicy="no-referrer" /><template v-else>{{ navigationIconLabel(row.icon, '✦') }}</template></span><div><strong>{{ row.name }}</strong><small>ID · {{ row.id }}</small></div></div>
            <span class="table-count">{{ row.bookmarkCount ?? 0 }} 个</span>
          </header>
          <div class="navigation-manage-card__meta">
            <span>排序值 {{ row.sortOrder }}</span>
            <span>{{ row.visible ? '前台展示' : '前台隐藏' }}</span>
          </div>
          <footer>
            <label><span>前台展示</span><el-switch v-model="row.visible" :loading="visibilityUpdating(row)" :disabled="visibilityUpdating(row)" :aria-label="`${row.name}前台展示`" @change="toggleVisible(row)" /></label>
            <div>
              <el-button circle :icon="Edit" :disabled="visibilityUpdating(row)" :aria-label="`编辑分类${row.name}`" @click="openEdit(row)" />
              <el-button circle type="danger" plain :icon="Delete" :disabled="visibilityUpdating(row)" :aria-label="`删除分类${row.name}`" @click="remove(row)" />
            </div>
          </footer>
        </article>
        <el-empty v-if="!filtered.length" description="暂无符合条件的分类" />
      </div>
    </section>

    <CategoryFormDialog v-model="dialogVisible" :category="editing" :submitting="submitting" @submit="save" />
    <SortOrderDialog
      v-model="sortVisible"
      title="调整分类顺序"
      description="这里的顺序就是公开首页从左到右、从上到下的分类顺序。"
      :items="sortItems"
      :submitting="savingSort"
      empty-text="暂无可排序分类"
      @submit="saveSort"
    />
  </div>
</template>
