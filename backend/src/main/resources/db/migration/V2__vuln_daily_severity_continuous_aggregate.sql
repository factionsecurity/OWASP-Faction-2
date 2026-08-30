-- Continuous aggregate: daily per-severity event counts, pre-aggregated by TimescaleDB.
-- Real-time aggregation is on by default, so queries also reflect recent (un-materialized) rows.
-- NOTE: this migration runs outside a transaction (see the accompanying .conf) because
-- TimescaleDB forbids creating a continuous aggregate inside a transaction block.
-- materialized_only = false enables real-time aggregation, so queries combine the
-- materialized buckets with recent rows that a refresh policy hasn't rolled up yet.
CREATE MATERIALIZED VIEW IF NOT EXISTS vuln_daily_severity
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket('1 day', event_time) AS bucket,
       organization_id,
       severity,
       event_type,
       count(*) AS event_count
FROM vulnerability_events
GROUP BY bucket, organization_id, severity, event_type
WITH NO DATA;
