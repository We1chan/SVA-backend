-- Run after 001_extend_h_device.sql. This idempotent migration stores the
-- active RTSP route returned by WVP for consumption by sva-server.

SET @migration_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'h_device' AND column_name = 'gb_stream_url'),
    'DO 0',
    'ALTER TABLE h_device ADD COLUMN gb_stream_url VARCHAR(1024) NULL COMMENT ''Active WVP/ZLM RTSP stream URL'' AFTER gb_stream_id');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- Manual rollback (only when no analyzer depends on an active GB28181 stream):
-- ALTER TABLE h_device DROP COLUMN gb_stream_url;
