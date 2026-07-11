-- Perfetto v57.2-da1d152cf
-- Raw trace SHA-256:
-- 74d7d518d72faa9707c751cc05910093523ea2023cb5948ffea3a02201295e1c
WITH
app AS (
  SELECT upid, pid
  FROM process
  WHERE name = 'com.pocketagent.android'
  ORDER BY start_ts
  LIMIT 1
),
main_thread AS (
  SELECT t.utid
  FROM thread t
  JOIN app ON t.upid = app.upid AND t.tid = app.pid
),
render_thread AS (
  SELECT t.utid
  FROM thread t
  JOIN app ON t.upid = app.upid
  WHERE t.name = 'RenderThread'
  LIMIT 1
),
main_state AS (
  SELECT state, dur
  FROM thread_state
  WHERE utid = (SELECT utid FROM main_thread) AND dur >= 0
),
render_slices AS (
  SELECT s.name, s.dur
  FROM slice s
  JOIN thread_track tt ON s.track_id = tt.id
  WHERE tt.utid = (SELECT utid FROM render_thread) AND s.dur >= 0
)
SELECT
  'main_running_max_ms' AS metric,
  ROUND(MAX(CASE WHEN state = 'Running' THEN dur END) / 1e6, 3) AS value,
  'ms' AS unit
FROM main_state
UNION ALL
SELECT
  'main_runnable_wait_max_ms',
  ROUND(MAX(CASE WHEN state = 'R' THEN dur END) / 1e6, 3),
  'ms'
FROM main_state
UNION ALL
SELECT
  'render_drawframes_24_to_30_ms_count',
  SUM(
    CASE
      WHEN name GLOB 'DrawFrames *' AND dur / 1e6 BETWEEN 24 AND 30 THEN 1
      ELSE 0
    END
  ),
  'count'
FROM render_slices
UNION ALL
SELECT
  'fullscreen_drawing_slice_count', COUNT(*), 'count'
FROM render_slices
WHERE name = 'Drawing  0.00  0.00 1080.00 2400.00'
UNION ALL
SELECT
  'fullscreen_drawing_avg_ms', ROUND(AVG(dur) / 1e6, 3), 'ms'
FROM render_slices
WHERE name = 'Drawing  0.00  0.00 1080.00 2400.00'
UNION ALL
SELECT
  'flush_commands_avg_ms', ROUND(AVG(dur) / 1e6, 3), 'ms'
FROM render_slices
WHERE name = 'flush commands'
UNION ALL
SELECT
  'egl_swap_avg_ms', ROUND(AVG(dur) / 1e6, 3), 'ms'
FROM render_slices
WHERE name = 'eglSwapBuffersWithDamageKHR';
