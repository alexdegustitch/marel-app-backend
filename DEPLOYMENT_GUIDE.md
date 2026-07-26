# Event-Driven Recalculation System - Deployment Guide

## 📋 Pre-Deployment Checklist

- [ ] Database backups completed
- [ ] Staging environment available for testing
- [ ] Team notified of maintenance window (if required)
- [ ] Rollback plan documented

---

## 🚀 Deployment Steps

### Step 1: Apply Database Migration

**File**: `src/main/resources/sql/2026-04-08-event-driven-recalc.sql`

**Execute in PostgreSQL**:
```bash
psql -U postgres -d marel_app -f src/main/resources/sql/2026-04-08-event-driven-recalc.sql
```

**Or using Spring Boot (Flyway/Liquibase if configured)**:
The migration will auto-apply on next startup if using Spring DB migration tools.

**Verify Migration**:
```sql
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'daily_report_recalc_queue' 
AND column_name = 'version';

SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'monthly_report_recalc_queue' 
AND column_name = 'version';
```

Both should return `version | integer`.

---

### Step 2: Deploy New Build

**Build**:
```bash
./mvnw clean package
```

**Result**: `target/marel-app-0.0.1-SNAPSHOT.jar`

**Deploy**:
```bash
# Stop current instance
systemctl stop marel-app

# Backup old JAR
cp /opt/marel-app/marel-app.jar /opt/marel-app/marel-app.jar.backup

# Deploy new JAR
cp target/marel-app-0.0.1-SNAPSHOT.jar /opt/marel-app/marel-app.jar

# Start new instance
systemctl start marel-app
```

**Or using Docker**:
```bash
docker build -t marel-app:latest .
docker run -d --restart always \
  -e DATABASE_URL=jdbc:postgresql://db:5432/marel_app \
  -e DATABASE_USER=postgres \
  -e DATABASE_PASSWORD=... \
  marel-app:latest
```

---

### Step 3: Verify Deployment

**1. Check Application Startup**:
```bash
# View logs
tail -f /var/log/marel-app/application.log

# Should see:
# - "Started MarelAppApplication in X seconds"
# - No errors about missing columns or table structures
```

**2. Test Work Log Mutation**:
```bash
# Create or update a work log
curl -X POST http://localhost:8080/api/work-logs/batch \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "create": [
      {
        "workShiftId": 1,
        "startAt": "2026-04-08T06:00:00Z",
        "endAt": "2026-04-08T14:00:00Z",
        "workCodeCategoryId": 1,
        "operationId": 1,
        "durationMin": 480,
        "quantity": 100,
        "hourlyOutput": 50
      }
    ]
  }'

# Expected response: HTTP 200 with work log details
# Important: Request should block until recalculation completes
```

**3. Verify Daily Report Updated**:
```bash
curl -X GET http://localhost:8080/api/daily-reports?employeeId=1&workDate=2026-04-08 \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Should show updated report with:
# - totalWorkMinutes, totalQuantity, etc. reflecting new work logs
# - version > 0
# - lastRecalculatedAt: recent timestamp
```

**4. Verify Monthly Report Updated**:
```bash
curl -X GET http://localhost:8080/api/monthly-reports?employeeId=1&year=2026&month=4 \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Should show updated monthly aggregates
```

**5. Check WebSocket Notifications** (using browser console):
```javascript
// Connect to WebSocket
const client = new SockJS('http://localhost:8080/ws');
const stompClient = Stomp.over(client);

stompClient.connect({}, function(frame) {
    // Subscribe to daily reports
    stompClient.subscribe('/topic/reports/daily', function(message) {
        console.log('Daily report update:', JSON.parse(message.body));
        // { type: 'DAILY_REPORT_UPDATED', employeeId: 1, workDate: '2026-04-08', workShiftId: 1 }
    });
    
    // Subscribe to monthly reports
    stompClient.subscribe('/topic/reports/monthly', function(message) {
        console.log('Monthly report update:', JSON.parse(message.body));
        // { type: 'MONTHLY_REPORT_UPDATED', employeeId: 1, year: 2026, month: 4 }
    });
});
```

**6. Monitor Application Logs**:
```bash
# Watch for event-driven processing logs
grep -i "event-driven\|processPendingJobs\|version check" /var/log/marel-app/application.log

# Expected patterns:
# - "Starting event-driven daily recalculation processing"
# - "Claimed daily job X for worker daily-UUID"
# - "Daily report recalculated for employee=..."
# - "Starting event-driven monthly recalculation processing"
```

---

## ⚡ Performance Verification

### Before (Polling)
- Daily jobs scanned every 2 seconds (even if no changes)
- Monthly jobs scanned every 5 seconds
- Latency: 2-10 seconds from mutation to report update
- Database queries: Constant polling load

### After (Event-Driven)
- Jobs only processed when work logs change
- Latency: <100ms from mutation to recalculation start
- Database queries: Only on actual mutations
- Zero background polling

**Benchmark Test**:
```bash
# Create 100 work logs
time curl -X POST http://localhost:8080/api/work-logs/batch \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{...}' --silent -o /dev/null -w "%{time_total}s\n"

# Expected: Complete within 1-2 seconds (including recalculation)
# Old system: 2-5 seconds (additional polling delay)
```

---

## 🔍 Troubleshooting

### Issue: "Cannot resolve column 'version'"
**Cause**: Migration not applied  
**Fix**: Run migration SQL manually, then restart app

### Issue: Work log mutation hangs (no response)
**Cause**: Recalculation stuck or deadlock  
**Fix**: Check application logs, restart instance, check database locks
```sql
-- Check for locks
SELECT * FROM pg_locks WHERE NOT granted;

-- Kill blocking transaction if needed
SELECT pg_terminate_backend(pid) FROM pg_stat_activity 
WHERE datname = 'marel_app' AND query LIKE '%recalc_queue%';
```

### Issue: Version mismatch warnings in logs
**Expected behavior** - not an error  
**Info**: Stale jobs are automatically retried; this is safe

### Issue: Daily/Monthly jobs stuck in PROCESSING
**Cause**: Worker crashed mid-processing  
**Fix**: Manually reset status in database
```sql
UPDATE daily_report_recalc_queue 
SET status = 'PENDING', locked_at = NULL, locked_by = NULL 
WHERE status = 'PROCESSING' AND processed_at IS NULL 
AND locked_at < NOW() - INTERVAL '5 minutes';
```

---

## 📊 Monitoring

### Key Metrics to Track

| Metric | Query |
|--------|-------|
| Pending daily jobs | `SELECT COUNT(*) FROM daily_report_recalc_queue WHERE status = 'PENDING'` |
| Failed daily jobs | `SELECT COUNT(*) FROM daily_report_recalc_queue WHERE status = 'FAILED'` |
| Avg recalc time | Check `lastRecalculatedAt - requestedAt` in queue tables |
| Stuck jobs | `SELECT * FROM daily_report_recalc_queue WHERE status = 'PROCESSING' AND locked_at < NOW() - INTERVAL '1 hour'` |

### Alerting

Set up alerts for:
- Failed jobs exceed 10: `SELECT COUNT(*) FROM daily_report_recalc_queue WHERE status = 'FAILED' > 10`
- Jobs stuck > 5 min: `SELECT COUNT(*) FROM daily_report_recalc_queue WHERE status = 'PROCESSING' AND locked_at < NOW() - INTERVAL '5 minutes' > 0`

---

## ✅ Rollback Procedure

If issues arise:

**1. Rollback Code**:
```bash
# Restore previous JAR
cp /opt/marel-app/marel-app.jar.backup /opt/marel-app/marel-app.jar

# Restart with old version
systemctl restart marel-app
```

**2. Rollback Database** (if needed):
```bash
# Scheduled polling will resume automatically with old code
# Migration is backward compatible (new column is optional)
# No data loss or schema changes to revert
```

**3. Verify Rollback**:
```bash
# Old polling should resume (~2s delay)
grep "fixedDelay" /var/log/marel-app/application.log
```

---

## 📞 Support

| Issue | Owner | Contact |
|-------|-------|---------|
| Database migration | DBA | dba@company.com |
| Application deployment | DevOps | devops@company.com |
| Feature bugs | Development | dev@company.com |

---

## 🎉 Post-Deployment

- [ ] All tests passing in staging
- [ ] Production deployment successful
- [ ] Monitoring dashboards updated
- [ ] Team trained on new workflow
- [ ] Documentation updated
- [ ] Rollback plan tested

**Go-live time**: As soon as all checks completed ✅

