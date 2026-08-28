import { describe, expect, it } from 'vitest'
import { isInstallPrimaryActionDisabled, resolveInstallWizardEntry } from './installWizard'

describe('first-run wizard state transitions', () => {
  it('starts with PostgreSQL when no database has been persisted', () => {
    expect(resolveInstallWizardEntry('DATABASE_REQUIRED')).toEqual({
      step: 0,
      databaseConfigured: false,
      redisConfigured: false,
      databaseStepWasUsed: true,
      redisStepWasUsed: false,
    })
  })

  it('skips PostgreSQL and opens Redis after the database restart', () => {
    expect(resolveInstallWizardEntry('REDIS_REQUIRED')).toMatchObject({
      step: 1,
      databaseConfigured: true,
      redisConfigured: false,
      databaseStepWasUsed: false,
      redisStepWasUsed: true,
    })
  })

  it('runs environment checks when both external services are persisted', () => {
    expect(resolveInstallWizardEntry('REQUIRED')).toMatchObject({
      step: 2,
      databaseConfigured: true,
      redisConfigured: true,
    })
  })

  it.each(['COMPLETED', 'DISABLED', 'UNKNOWN'] as const)(
    'does not enter protected setup from %s',
    (state) => expect(resolveInstallWizardEntry(state)).toBeNull(),
  )
})

describe('wizard primary action guard', () => {
  const ready = {
    step: 0 as const,
    databaseConfigured: false,
    databaseCanConfigure: true,
    databaseBusy: false,
    redisConfigured: false,
    redisCanConfigure: false,
    redisBusy: false,
    environmentReady: false,
    checkingEnvironment: false,
  }

  it('blocks Enter before PostgreSQL or Redis has a valid one-time ticket', () => {
    expect(isInstallPrimaryActionDisabled({ ...ready, databaseCanConfigure: false })).toBe(true)
    expect(isInstallPrimaryActionDisabled({
      ...ready,
      step: 1,
      databaseConfigured: true,
      redisCanConfigure: false,
    })).toBe(true)
  })

  it('allows the same states as the visible apply buttons', () => {
    expect(isInstallPrimaryActionDisabled(ready)).toBe(false)
    expect(isInstallPrimaryActionDisabled({
      ...ready,
      step: 1,
      databaseConfigured: true,
      redisCanConfigure: true,
    })).toBe(false)
  })
})
