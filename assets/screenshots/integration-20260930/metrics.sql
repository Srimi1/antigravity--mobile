SELECT name, severity, value FROM stats WHERE severity IN ('error','data_loss') AND value != 0;
SELECT t.name, COUNT(*) AS samples, MIN(c.value) AS min_bytes, MAX(c.value) AS max_bytes FROM counter c JOIN counter_track t ON c.track_id=t.id WHERE t.name='MemAvailable' GROUP BY t.name;
SELECT p.pid,p.name,t.name,MAX(c.value) AS peak_bytes FROM counter c JOIN process_counter_track t ON c.track_id=t.id JOIN process p ON t.upid=p.upid WHERE p.uid IN (10150,10151) AND t.name LIKE '%rss%' GROUP BY p.pid,p.name,t.name ORDER BY p.pid,t.name;
SELECT p.pid,p.name,SUM(s.dur)/1000000.0 AS scheduled_cpu_ms FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid) WHERE p.uid IN (10150,10151) AND s.dur>0 GROUP BY p.pid,p.name ORDER BY scheduled_cpu_ms DESC;
