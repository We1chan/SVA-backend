-- Module: streaming protocol group / GB28181 device model.
-- Idempotent MySQL migration for the GB28181 device model. Existing DIRECT
-- and PLATFORM rows are preserved, and the file may be executed repeatedly.

SET @migration_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'h_device' AND column_name = 'gb_device_id'),
    'DO 0',
    'ALTER TABLE h_device ADD COLUMN gb_device_id VARCHAR(50) NULL COMMENT ''WVP GB28181 parent device ID'' AFTER zlm_proxy_key');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'h_device' AND column_name = 'gb_channel_id'),
    'DO 0',
    'ALTER TABLE h_device ADD COLUMN gb_channel_id VARCHAR(50) NULL COMMENT ''WVP GB28181 channel ID'' AFTER gb_device_id');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'h_device' AND column_name = 'gb_media_server_id'),
    'DO 0',
    'ALTER TABLE h_device ADD COLUMN gb_media_server_id VARCHAR(50) NULL COMMENT ''WVP media server ID'' AFTER gb_channel_id');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'h_device' AND column_name = 'gb_stream_id'),
    'DO 0',
    'ALTER TABLE h_device ADD COLUMN gb_stream_id VARCHAR(255) NULL COMMENT ''Active WVP/ZLM stream ID'' AFTER gb_media_server_id');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'h_device' AND column_name = 'gb_last_sync_time'),
    'DO 0',
    'ALTER TABLE h_device ADD COLUMN gb_last_sync_time DATETIME NULL COMMENT ''Last successful WVP synchronization time'' AFTER gb_stream_id');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'h_device' AND index_name = 'uk_h_device_gb_channel'),
    'DO 0',
    'ALTER TABLE h_device ADD UNIQUE KEY uk_h_device_gb_channel (gb_device_id, gb_channel_id)');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'h_device' AND index_name = 'idx_h_device_source_online'),
    'DO 0',
    'ALTER TABLE h_device ADD KEY idx_h_device_source_online (stream_source_type, is_online)');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- Manual rollback (only after all GB28181 rows have been removed):
-- ALTER TABLE h_device DROP INDEX idx_h_device_source_online,
--   DROP INDEX uk_h_device_gb_channel, DROP COLUMN gb_last_sync_time,
--   DROP COLUMN gb_stream_id, DROP COLUMN gb_media_server_id,
--   DROP COLUMN gb_channel_id, DROP COLUMN gb_device_id;
