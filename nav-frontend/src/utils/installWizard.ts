import type { InstallState } from '@/types/install'

export type InstallWizardStep = 0 | 1 | 2 | 3 | 4 | 5

export interface InstallWizardEntry {
  step: 0 | 1 | 2
  databaseConfigured: boolean
  redisConfigured: boolean
  databaseStepWasUsed: boolean
  redisStepWasUsed: boolean
}

export interface InstallPrimaryActionState {
  step: number
  databaseConfigured: boolean
  databaseCanConfigure: boolean
  databaseBusy: boolean
  redisConfigured: boolean
  redisCanConfigure: boolean
  redisBusy: boolean
  environmentReady: boolean
  checkingEnvironment: boolean
}

/** Mirrors the visible primary button so keyboard shortcuts cannot bypass it. */
export function isInstallPrimaryActionDisabled(state: InstallPrimaryActionState): boolean {
  return (
    state.step === 0
      && !state.databaseConfigured
      && (!state.databaseCanConfigure || state.databaseBusy)
    )
    || (
      state.step === 1
      && !state.redisConfigured
      && (!state.redisCanConfigure || state.redisBusy)
    )
    || (state.step === 2 && (!state.environmentReady || state.checkingEnvironment))
}

/**
 * Maps the server's persisted first-run state to the first wizard step that
 * still requires work. Completed, disabled and indeterminate states may not
 * enter the protected configuration flow.
 */
export function resolveInstallWizardEntry(state: InstallState): InstallWizardEntry | null {
  if (state === 'DATABASE_REQUIRED') {
    return {
      step: 0,
      databaseConfigured: false,
      redisConfigured: false,
      databaseStepWasUsed: true,
      redisStepWasUsed: false,
    }
  }
  if (state === 'REDIS_REQUIRED') {
    return {
      step: 1,
      databaseConfigured: true,
      redisConfigured: false,
      databaseStepWasUsed: false,
      redisStepWasUsed: true,
    }
  }
  if (state === 'REQUIRED') {
    return {
      step: 2,
      databaseConfigured: true,
      redisConfigured: true,
      databaseStepWasUsed: false,
      redisStepWasUsed: false,
    }
  }
  return null
}
