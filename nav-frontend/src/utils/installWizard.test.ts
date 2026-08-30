import { describe, expect, it } from 'vitest'
import {
  buildCompleteInstallationPayload,
  installCompletionRoute,
  installRequestErrorMessage,
  isInstallPrimaryActionDisabled,
  resolveInstallWizardEntry,
  submitInstallCompletion,
} from './installWizard'

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

describe('installation completion flow', () => {
  const form = {
    siteName: '  yunlume  ',
    siteDescription: '  私人导航  ',
    username: '  admin  ',
    nickname: '  管理员  ',
    password: 'Example!Pass2026',
    confirmPassword: 'Example!Pass2026',
  }

  it('builds the administrator creation payload without leaking wizard-only fields', () => {
    expect(buildCompleteInstallationPayload(form)).toEqual({
      siteName: 'yunlume',
      siteDescription: '私人导航',
      username: 'admin',
      nickname: '管理员',
      password: 'Example!Pass2026',
      confirmPassword: 'Example!Pass2026',
    })
  })

  it('finalizes local state and redirects only after the server accepts installation', async () => {
    const events: string[] = []
    let submitted: unknown
    let redirected: unknown

    await submitInstallCompletion(form, {
      submit: async (payload) => {
        events.push('submit')
        submitted = payload
      },
      finalizeLocalState: () => events.push('finalize'),
      redirect: async (route) => {
        events.push('redirect')
        redirected = route
      },
    })

    expect(events).toEqual(['submit', 'finalize', 'redirect'])
    expect(submitted).toEqual(buildCompleteInstallationPayload(form))
    expect(redirected).toEqual(installCompletionRoute())
  })

  it('keeps the wizard active when administrator creation fails', async () => {
    let finalized = false
    let redirected = false
    const error = new Error('管理员用户名已存在')

    await expect(submitInstallCompletion(form, {
      submit: async () => { throw error },
      finalizeLocalState: () => { finalized = true },
      redirect: async () => { redirected = true },
    })).rejects.toBe(error)

    expect(finalized).toBe(false)
    expect(redirected).toBe(false)
    expect(installRequestErrorMessage(error, '安装失败')).toBe('管理员用户名已存在')
    expect(installRequestErrorMessage({ status: 500 }, '安装失败')).toBe('安装失败')
  })
})
