import { createRenderer, ssrContextKey, type Component, type ComponentPublicInstance } from 'vue'

export interface TestNode {
  type: string
  text: string
  props: Record<string, unknown>
  children: TestNode[]
  parent: TestNode | null
}

function node(type: string, text = ''): TestNode {
  return { type, text, props: {}, children: [], parent: null }
}

// 使用真实 Vue 调度和生命周期；宿主节点只保留测试需要的树结构，不模拟浏览器 DOM。
const renderer = createRenderer<TestNode, TestNode>({
  createElement: (type) => node(type),
  createText: (text) => node('#text', text),
  createComment: (text) => node('#comment', text),
  setText: (target, text) => { target.text = text },
  setElementText: (target, text) => {
    target.children.forEach((child) => { child.parent = null })
    target.children = []
    target.text = text
  },
  parentNode: (target) => target.parent,
  nextSibling: (target) => {
    const siblings = target.parent?.children ?? []
    return siblings[siblings.indexOf(target) + 1] ?? null
  },
  patchProp: (target, key, _previous, value) => { target.props[key] = value },
  insert: (target, parent, anchor = null) => {
    if (target.parent) {
      const index = target.parent.children.indexOf(target)
      if (index >= 0) target.parent.children.splice(index, 1)
    }
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(target)
    else parent.children.splice(index, 0, target)
    target.parent = parent
  },
  remove: (target) => {
    const siblings = target.parent?.children
    const index = siblings?.indexOf(target) ?? -1
    if (siblings && index >= 0) siblings.splice(index, 1)
    target.parent = null
  },
})

export function mountComponent<T>(
  component: Component,
  props: Record<string, unknown> = {},
  options: { render?: boolean } = {},
) {
  const root = node('root')
  const target = options.render ? component : { ...component, render: () => null }
  const app = renderer.createApp(target, props)
  // Vitest 的 Node 环境把 SFC 编译为 SSR 模块，setup 仍由真实 Vue 客户端生命周期执行。
  app.provide(ssrContextKey, { modules: new Set<string>() })
  const vm: ComponentPublicInstance = app.mount(root)
  const state = (vm.$ as unknown as { setupState: T }).setupState
  let unmounted = false
  return {
    state, root, vm, app,
    unmount: () => {
      if (unmounted) return
      unmounted = true
      app.unmount()
    },
  }
}

export function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((accept, fail) => {
    resolve = accept
    reject = fail
  })
  return { promise, resolve, reject }
}
