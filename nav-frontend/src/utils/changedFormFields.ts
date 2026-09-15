/** 标量表单只提交相对打开时快照的修改，避免把其他请求已保存的字段写回旧值。 */
export function changedFormFields<T extends object>(before: Partial<T>, after: T): Partial<T> {
  const changed: Partial<T> = {}
  for (const key of Object.keys(after) as Array<keyof T>) {
    if (!Object.is(before[key], after[key])) changed[key] = after[key]
  }
  return changed
}
