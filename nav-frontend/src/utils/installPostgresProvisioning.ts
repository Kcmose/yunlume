export interface PostgresProvisioningSql {
  maintenanceDatabaseSql: string
  applicationDatabaseSql: string
  combinedSql: string
}

function quoteIdentifier(value: string): string {
  return `"${value.replace(/"/g, '""')}"`
}

/**
 * Produces password-free SQL for a role that the deployer has already created
 * in 1Panel. User-controlled identifiers are always PostgreSQL-quoted; they
 * are never interpolated into comments or string literals.
 */
export function buildPostgresProvisioningSql(
  database: string,
  username: string,
): PostgresProvisioningSql {
  const databaseIdentifier = quoteIdentifier(database.trim())
  const usernameIdentifier = quoteIdentifier(username.trim())
  const maintenanceDatabaseSql = [
    '-- 第 1 段：连接 postgres 维护库后，以数据库管理员身份单独执行。',
    '-- 请先在 1Panel 创建下方普通登录用户；本脚本不创建用户，也不包含密码。',
    `CREATE DATABASE ${databaseIdentifier} WITH OWNER ${usernameIdentifier} ENCODING 'UTF8' TEMPLATE template0;`,
    `REVOKE ALL ON DATABASE ${databaseIdentifier} FROM PUBLIC;`,
    `GRANT CONNECT, TEMPORARY ON DATABASE ${databaseIdentifier} TO ${usernameIdentifier};`,
  ].join('\n')
  const applicationDatabaseSql = [
    '-- 第 2 段：在 1Panel 中切换到刚创建的业务数据库后执行。',
    'REVOKE CREATE ON SCHEMA public FROM PUBLIC;',
    `GRANT USAGE, CREATE ON SCHEMA public TO ${usernameIdentifier};`,
  ].join('\n')
  return {
    maintenanceDatabaseSql,
    applicationDatabaseSql,
    combinedSql: `${maintenanceDatabaseSql}\n\n${applicationDatabaseSql}`,
  }
}
