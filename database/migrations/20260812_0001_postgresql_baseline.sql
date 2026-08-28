-- PostgreSQL schema baseline marker.
--
-- A new database receives the complete schema from database/init.sql and records
-- this migration in schema_migration. Existing PostgreSQL databases can use this
-- stable no-op file as the starting point for checksum-verified later migrations.
SELECT 1;
