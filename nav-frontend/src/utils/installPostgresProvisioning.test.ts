import { describe, expect, it } from 'vitest'
import { buildPostgresProvisioningSql } from './installPostgresProvisioning'

describe('1Panel PostgreSQL provisioning SQL', () => {
  it('creates a dedicated database owned by the pre-created ordinary role', () => {
    const result = buildPostgresProvisioningSql('navigation', 'navigation_app')
    expect(result.maintenanceDatabaseSql).toContain(
      'CREATE DATABASE "navigation" WITH OWNER "navigation_app"',
    )
    expect(result.applicationDatabaseSql).toContain(
      'GRANT USAGE, CREATE ON SCHEMA public TO "navigation_app";',
    )
    expect(result.maintenanceDatabaseSql).not.toContain('ON SCHEMA public')
    expect(result.applicationDatabaseSql).not.toContain('CREATE DATABASE')
  })

  it('never generates a role password or asks the installer for a superuser secret', () => {
    const result = buildPostgresProvisioningSql('navigation', 'navigation_app')
    expect(result.combinedSql).not.toMatch(/\bPASSWORD\b/i)
    expect(result.combinedSql).not.toMatch(/CREATE\s+(?:USER|ROLE)/i)
  })

  it('quotes identifiers so copied input cannot become executable SQL', () => {
    const result = buildPostgresProvisioningSql('nav"; DROP DATABASE postgres; --', 'app"; SELECT 1; --')
    expect(result.maintenanceDatabaseSql).toContain(
      'CREATE DATABASE "nav""; DROP DATABASE postgres; --" WITH OWNER "app""; SELECT 1; --"',
    )
    expect(result.combinedSql).not.toContain('OWNER "app"; SELECT')
  })
})
