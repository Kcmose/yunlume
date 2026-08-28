export type AdminSessionInvalidationHandler = () => void | Promise<void>

let handler: AdminSessionInvalidationHandler | null = null
let invalidationTask: Promise<boolean> | null = null

/**
 * Registers the app-level session invalidation path. The handler is installed
 * by the router so API code never needs to mutate Pinia state or force a full
 * page reload directly.
 */
export function registerAdminSessionInvalidationHandler(
  nextHandler: AdminSessionInvalidationHandler,
): () => void {
  handler = nextHandler

  return () => {
    if (handler === nextHandler) handler = null
  }
}

/**
 * Coalesces concurrent 401/403 responses into one clear-and-redirect task.
 */
export function invalidateAdminSession(): Promise<boolean> {
  if (invalidationTask) return invalidationTask

  if (!handler) {
    return Promise.resolve(false)
  }

  const currentHandler = handler
  const task = Promise.resolve().then(() => currentHandler())
  let trackedTask: Promise<boolean>
  trackedTask = task
    .then(() => true)
    .finally(() => {
      if (invalidationTask === trackedTask) invalidationTask = null
    })
  invalidationTask = trackedTask
  return trackedTask
}
