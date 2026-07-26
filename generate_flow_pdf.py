from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm
from reportlab.lib import colors
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, HRFlowable, Preformatted
from reportlab.lib.enums import TA_LEFT, TA_CENTER

OUTPUT = "FLOW_DOKUMENTACIJA.pdf"

doc = SimpleDocTemplate(
    OUTPUT,
    pagesize=A4,
    rightMargin=2*cm,
    leftMargin=2*cm,
    topMargin=2*cm,
    bottomMargin=2*cm,
)

styles = getSampleStyleSheet()

title_style = ParagraphStyle(
    "Title",
    parent=styles["Heading1"],
    fontSize=18,
    textColor=colors.HexColor("#1a1a2e"),
    spaceAfter=6,
    alignment=TA_CENTER,
)
subtitle_style = ParagraphStyle(
    "Subtitle",
    parent=styles["Normal"],
    fontSize=10,
    textColor=colors.HexColor("#555555"),
    spaceAfter=16,
    alignment=TA_CENTER,
)
h2_style = ParagraphStyle(
    "H2",
    parent=styles["Heading2"],
    fontSize=13,
    textColor=colors.HexColor("#0d3b66"),
    spaceBefore=18,
    spaceAfter=6,
    borderPad=4,
)
h3_style = ParagraphStyle(
    "H3",
    parent=styles["Heading3"],
    fontSize=11,
    textColor=colors.HexColor("#2b6cb0"),
    spaceBefore=10,
    spaceAfter=4,
)
body_style = ParagraphStyle(
    "Body",
    parent=styles["Normal"],
    fontSize=9.5,
    leading=14,
    textColor=colors.HexColor("#222222"),
    spaceAfter=6,
)
code_style = ParagraphStyle(
    "Code",
    parent=styles["Code"],
    fontSize=8,
    leading=11,
    textColor=colors.HexColor("#1a1a2e"),
    backColor=colors.HexColor("#f4f6f8"),
    borderColor=colors.HexColor("#cccccc"),
    borderWidth=0.5,
    borderPad=6,
    fontName="Courier",
    spaceAfter=8,
    spaceBefore=4,
)
bullet_style = ParagraphStyle(
    "Bullet",
    parent=styles["Normal"],
    fontSize=9.5,
    leading=14,
    leftIndent=16,
    bulletIndent=4,
    spaceAfter=3,
    textColor=colors.HexColor("#222222"),
)
note_style = ParagraphStyle(
    "Note",
    parent=styles["Normal"],
    fontSize=9,
    leading=13,
    textColor=colors.HexColor("#555555"),
    leftIndent=12,
    spaceAfter=4,
    fontName="Helvetica-Oblique",
)

def ascii_sr(text: str) -> str:
    # Transliterate Serbian Latin diacritics to ASCII equivalents.
    mapping = {
        "č": "c", "ć": "c", "š": "s", "ž": "z", "đ": "dj",
        "Č": "C", "Ć": "C", "Š": "S", "Ž": "Z", "Đ": "Dj",
    }
    return "".join(mapping.get(ch, ch) for ch in text)

def h2(text): return Paragraph(ascii_sr(text), h2_style)
def h3(text): return Paragraph(ascii_sr(text), h3_style)
def p(text): return Paragraph(ascii_sr(text), body_style)
def b(text): return Paragraph(ascii_sr(f"• {text}"), bullet_style)
def code(text): return Preformatted(ascii_sr(text), code_style)
def note(text): return Paragraph(ascii_sr(text), note_style)
def hr(): return HRFlowable(width="100%", thickness=0.5, color=colors.HexColor("#bbbbbb"), spaceAfter=8, spaceBefore=8)
def sp(h=6): return Spacer(1, h)

story = []

# ── NASLOV ──────────────────────────────────────────────────────────────
story.append(sp(10))
story.append(Paragraph(ascii_sr("Marel Norm Tracking App — Backend"), title_style))
story.append(Paragraph(ascii_sr("Kompletan Flow: Work Logovi → Daily → Monthly Report"), subtitle_style))
story.append(Paragraph(ascii_sr("Datum: 10. juni 2026."), subtitle_style))
story.append(hr())
story.append(sp(4))

# ── PREGLED ──────────────────────────────────────────────────────────────
story.append(h2("Pregled arhitekture"))
story.append(p(
    "Sistem koristi <b>asinhroni recalkulacioni mehanizam</b> zasnovan na bazi podataka (PostgreSQL). "
    "Umesto direktnog ažuriranja reporta pri svakom unosu, promene se stavljaju u red čekanja i obrađuju "
    "pozadinskim workerima. Ovo obezbeđuje visoku propusnost i konzistentnost podataka čak i pri konkurentnim zahtevima."
))
story.append(sp(4))

# ── KORAK 1 ──────────────────────────────────────────────────────────────
story.append(h2("1. Ulazna tačka — Upisivanje work logova"))
story.append(h3("Fajl: work_log/WorkLogController.java"))
story.append(p("Jedini REST endpoint za kreiranje, izmenu i brisanje work logova:"))
story.append(code("POST /api/work-logs/batch\nBody: CreateUpdateWorkLogsRequest { create: [...], update: [...], deleted: [...] }"))
story.append(p("Controller je tanak — samo delegira na <b>WorkLogService.handleBatch()</b>."))
story.append(sp(2))

# ── KORAK 2 ──────────────────────────────────────────────────────────────
story.append(h2("2. WorkLogService.handleBatch() — Mutacija + Enqueue"))
story.append(h3("Fajl: work_log/WorkLogService.java"))
story.append(p("Metoda je anotirana sa <b>@Transactional</b>. Unutar jedne transakcije:"))
story.extend([
    b("<b>Create:</b> workLogMapper.toEntity() + repository.saveAll()"),
    b("<b>Update:</b> fetch po IDs, workLogMapper.updateEntity(), save"),
    b("<b>Delete (soft):</b> existing.setIsActive(false), save — nema fizičkog brisanja"),
])
story.append(sp(4))
story.append(p("Nakon mutacija, za svaki zahvaćeni <b>WorkShift</b> (deduplicirano po shift ID-u):"))
story.append(code(
    "recalcQueueService.enqueueDailyJob(shift, \"WORK_LOG_MUTATION\")\n"
    "// UPSERT u daily_report_recalc_queue:\n"
    "// - Ako job ne postoji: INSERT sa status='PENDING'\n"
    "// - Ako već postoji: UPDATE version++ i reset na 'PENDING'\n"
    "// - Ako je IN_PROGRESS: ostavlja status, samo bumpa version"
))
story.append(p("Na kraju transakcije publishuje se Spring Application Event:"))
story.append(code("eventPublisher.publishEvent(new DailyRecalcRequestedEvent(Type.DAILY))"))
story.append(note("Ovaj event se isporučuje POSLE commita (AFTER_COMMIT), kako bi workeri videli upisane jobove."))
story.append(sp(2))

# ── KORAK 3 ──────────────────────────────────────────────────────────────
story.append(h2("3. Wake Signal — Buđenje workera"))
story.append(h3("Fajlovi: DailyRecalcRequestedEventListener.java, RecalcWorkerWakeSignal.java"))
story.append(p(
    "Listener osluškuje <b>DailyRecalcRequestedEvent</b> i poziva <b>wakeSignal.signalAll()</b>, "
    "koji radi <b>Object.notifyAll()</b> na deljenom monitoru. Worker threadovi koji su u "
    "<b>monitor.wait(timeoutMs)</b> (idle stanje) se odmah bude i kreću sa procesiranjem."
))
story.append(code(
    "@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)\n"
    "public void onRecalcRequested(DailyRecalcRequestedEvent event) {\n"
    "    wakeSignal.signalAll();\n"
    "}"
))
story.append(sp(2))

# ── KORAK 4 ──────────────────────────────────────────────────────────────
story.append(h2("4. DbQueueWorkerManager — Pozadinski threadovi"))
story.append(h3("Fajl: report_worker/DbQueueWorkerManager.java"))
story.append(p(
    "Startuje se na <b>@EventListener(ApplicationReadyEvent.class)</b> — čim se aplikacija podigne. "
    "Kreira konfigurisani broj Java platform threadova:"
))
story.extend([
    b("N x <b>dailyLoop</b> threadova (\"daily-worker-1\", \"daily-worker-2\", ...)"),
    b("M x <b>monthlyLoop</b> threadova (\"monthly-worker-1\", ...)"),
])
story.append(sp(4))
story.append(p("Logika jednog <b>dailyLoop</b>:"))
story.append(code(
    "while (running) {\n"
    "    // Svakih 20 iteracija: oporavi stuck IN_PROGRESS jobove\n"
    "    if (loop++ % 20 == 0) requeueStuckDailyJobs();\n\n"
    "    int processed = dailyReportWorker.processBatch(batchSize, workerId, loopBudget);\n\n"
    "    if (processed == 0) {\n"
    "        waitForWakeOrTimeout(idleMs);  // ceka na signal ili timeout\n"
    "        idleMs = min(maxIdleMs, idleMs * 2);  // exponential backoff idle\n"
    "    } else {\n"
    "        idleMs = minIdleMs;  // odmah ponovi ako ima posla\n"
    "    }\n"
    "}"
))
story.append(note("Isti pattern za monthlyLoop. Graceful shutdown: @PreDestroy → running=false + thread.interrupt()."))
story.append(sp(2))

# ── KORAK 5 ──────────────────────────────────────────────────────────────
story.append(h2("5. Claim + Pessimistic Lock — RecalcQueueService"))
story.append(h3("Fajl: recalc_queue/RecalcQueueService.java"))
story.append(p(
    "Metoda <b>claimDailyJobIds(batchSize, workerId)</b> koristi <b>FOR UPDATE SKIP LOCKED</b> "
    "kako bi u jednom SQL upitu i selektovala i zaključala jobove, bez blokiranja između workera:"
))
story.append(code(
    "WITH c AS (\n"
    "  SELECT id FROM daily_report_recalc_queue\n"
    "  WHERE status = 'PENDING' AND retry_count < maxRetry\n"
    "  ORDER BY requested_at, id\n"
    "  LIMIT batchSize\n"
    "  FOR UPDATE SKIP LOCKED\n"
    ")\n"
    "UPDATE daily_report_recalc_queue q\n"
    "SET status = 'IN_PROGRESS', claimed_at = NOW(), claimed_by = workerId\n"
    "FROM c WHERE q.id = c.id\n"
    "RETURNING q.id"
))
story.append(p(
    "Isti SQL pattern se koristi i za monthly jobove. "
    "Ovo garantuje da 2 workera nikada ne uzmu isti job."
))
story.append(sp(2))

# ── KORAK 6 ──────────────────────────────────────────────────────────────
story.append(h2("6. DailyRecalcService — Obrada daily job-a"))
story.append(h3("Fajl: report_worker/DailyRecalcService.java"))
story.append(p("Metoda <b>processJob(jobId)</b> je podeljena u 2 faze radi minimizovanja vremena držanja locka:"))

story.append(h3("Read faza (van transakcije):"))
story.extend([
    b("Provera statusa job-a — skip ako nije IN_PROGRESS"),
    b("Čita sve aktivne work logove za WorkShift (<b>findActiveLogsWithRefsForShift</b>)"),
])

story.append(h3("Write faza (u TransactionTemplate):"))
story.extend([
    b("Fetch WorkShift, Employee, EmployeeRecord (getOrCreate za mesec)"),
    b("Fetch ili create DailyReport za ovaj shift"),
    b("<b>Stale guard:</b> re-lock queue job sa FOR UPDATE, provera version — ako se promenio dok smo računali → reschedule na PENDING"),
    b("<b>Bulk delete</b> starih DailyReportCategory-a: deleteAllByDailyReportId()"),
    b("<b>buildCategories()</b> — rebuild svih kategorija"),
    b("<b>fillDailyTotals()</b> — agregacija po tipu"),
    b("Save DailyReport + kategorije"),
    b("Queue job → DONE"),
    b("enqueueMonthlyJob() → UPSERT u monthly_report_recalc_queue"),
    b("WebSocket push: sendDailyReportUpdate() → /topic/reports/daily"),
])

story.append(sp(4))
story.append(h3("buildCategories() — Logika računanja:"))
story.append(p("Grupiše logove po <b>WorkCodeCategory</b>, za svaku kategoriju računa:"))
story.extend([
    b("totalMinutes, totalQuantity, totalScrap — suma svih logova u kategoriji"),
    b("<b>performanceRate</b> = (log.hourlyOutput / operation.minNorm) × 100 | 100 ako operacija nema normu"),
    b("<b>approvedPerformanceRate</b> = min(performanceRate, maxEfficiencyPercent) — cap iz AppSettings"),
    b("Weighted average koeficijent po trajanju (minuti × stopa) / ukupni minuti"),
    b("<b>totalWeightedNormMinutes</b> = totalMinutes × approvedPerformanceCoefficient"),
])

story.append(sp(4))
story.append(h3("fillDailyTotals() — Agregacija u DailyReport:"))
story.extend([
    b("totalShiftMinutes, totalWorkMinutes"),
    b("totalAbsencePaidMinutes, totalAbsenceUnpaidMinutes"),
    b("totalSickLeavePaidMinutes, totalSickLeaveUnpaidMinutes"),
    b("totalQuantity, totalScrap, totalWeightedNormMinutes"),
    b("performanceCoefficient, approvedPerformanceCoefficient, performanceRate"),
    b("<b>isMealAllowed</b> = true ako totalWorkMinutes >= 240"),
    b("calcVersion++ (koliko puta je recalkulisan)"),
])
story.append(sp(2))

# ── KORAK 7 ──────────────────────────────────────────────────────────────
story.append(h2("7. MonthlyRecalcService — Obrada monthly job-a"))
story.append(h3("Fajl: report_worker/MonthlyRecalcService.java"))
story.append(p("Isti dvofazni pattern kao DailyRecalcService:"))

story.append(h3("Read faza (van transakcije):"))
story.extend([
    b("findByEmployee_IdAndWorkDateBetween(start, end) — svi DailyReport-i za mesec"),
    b("findAllByDailyReportIds() — sve DailyReportCategory za te reporte"),
])

story.append(h3("Write faza (u TransactionTemplate):"))
story.extend([
    b("Fetch/create EmployeeRecord za mesec"),
    b("Fetch ili create MonthlyReport"),
    b("<b>Stale guard</b> — isti version check kao daily"),
    b("<b>Bulk delete</b> starih MonthlyReportCategory"),
    b("<b>buildMonthlyCategories()</b> — grupišu se DailyReportCategory po WorkCodeCategory, sumiraju minute/qty/scrap"),
    b("<b>fillMonthlyTotals()</b> — agregira sve DailyReport-e za mesec"),
    b("Save MonthlyReport + kategorije (bumpa <b>@Version</b> — Hibernate optimistic lock)"),
    b("Queue job → DONE"),
    b("WebSocket push: sendMonthlyReportUpdate() → /topic/reports/monthly"),
])

story.append(sp(4))
story.append(h3("fillMonthlyTotals() — Agregacija u MonthlyReport:"))
story.extend([
    b("Sumira totalShiftMinutes, totalWorkMinutes, absence/sickLeave breakdown iz svih DailyReport-a"),
    b("mealAllowanceNum — broj dana sa isMealAllowed=true"),
    b("totalApprovedMinutes = Σ(weightedNormMinutes × normMultiplier) po kategoriji, za period"),
    b("performanceCoefficient = totalWeightedNormMinutes / totalShiftMinutes"),
    b("calcVersion++"),
])
story.append(sp(2))

# ── RETRY ──────────────────────────────────────────────────────────────
story.append(h2("8. Retry, Failure i Stuck Recovery"))
story.append(h3("Fajlovi: DailyRecalcService.markFailed(), MonthlyRecalcService.markFailed()"))
story.append(p("Ako <b>processJob()</b> baci izuzetak, worker poziva <b>markFailed(jobId, errMsg)</b>:"))
story.append(code(
    "retryCount++\n"
    "if (retryCount < maxRetry):\n"
    "    status = 'PENDING'\n"
    "    requestedAt = now + exponentialBackoff(retryCount)  // base × 2^retry, max 5 min\n"
    "else:\n"
    "    status = 'FAILED'  // permanentno neuspešan"
))
story.append(p("<b>Stuck Recovery</b> — svakih 20 loop iteracija:"))
story.append(code(
    "requeueStuckDailyJobs(stuckTimeoutSeconds, batchLimit)\n"
    "// Pronalazi IN_PROGRESS jobove koji su stuck duže od timeout-a\n"
    "// Resetuje ih na PENDING sa exponential backoff i incrementuje stuck_count"
))
story.append(sp(2))

# ── STATUS FLOW ──────────────────────────────────────────────────────────
story.append(h2("9. Status dijagram queue job-a"))
story.append(code(
    "PENDING\n"
    "  │\n"
    "  ├─ claimDailyJobIds() ──────────────────────► IN_PROGRESS\n"
    "  │                                                  │\n"
    "  │                              ┌────────────────────┤\n"
    "  │                              │  (version changed)  │\n"
    "  │◄─────────────────────────────┘                    │\n"
    "  │  (reschedule)                                     │\n"
    "  │                              ┌────────────────────┤\n"
    "  │◄─────────────────────────────┘                    │  (success)\n"
    "  │  (markFailed, retryCount < max)                   ▼\n"
    "  │                                               DONE\n"
    "  ▼\n"
    "FAILED  (retryCount >= maxRetry)"
))
story.append(sp(2))

# ── ENTITETI ──────────────────────────────────────────────────────────────
story.append(h2("10. Ključni entiteti i relacije"))
story.append(code(
    "WorkLog ──────────────────────► WorkShift\n"
    "                                    │\n"
    "                                    ▼\n"
    "                              DailyReport\n"
    "                                    │\n"
    "                                    ├─► DailyReportCategory (per WorkCodeCategory)\n"
    "                                    │\n"
    "WorkShift ──► EmployeeRecord ──► MonthlyReport\n"
    "                                    │\n"
    "                                    └─► MonthlyReportCategory (per WorkCodeCategory)\n\n"
    "Queue tabele:\n"
    "  daily_report_recalc_queue    (per WorkShift, UNIQUE on work_shift_id)\n"
    "  monthly_report_recalc_queue  (per employee + year + month, UNIQUE on (employee_id, year, month))\n"
))
story.append(sp(2))

# ── SAŽETAK ZA AGENT ──────────────────────────────────────────────────────
story.append(hr())
story.append(h2("Sažetak (za agent)"))
story.append(code(
    "STACK: Spring Boot 4 / Java 21 / PostgreSQL\n\n"
    "ULAZNA TAČKA:\n"
    "  POST /api/work-logs/batch\n"
    "    → WorkLogController\n"
    "    → WorkLogService.handleBatch()  [@Transactional]\n\n"
    "CHAIN:\n"
    "  1. handleBatch()\n"
    "     - mutira work_log (create/update/soft-delete)\n"
    "     - enqueueDailyJob()  → UPSERT daily_report_recalc_queue (PENDING)\n"
    "     - publishuje DailyRecalcRequestedEvent\n\n"
    "  2. DailyRecalcRequestedEventListener [AFTER_COMMIT]\n"
    "     - wakeSignal.signalAll()  → budi worker threadove\n\n"
    "  3. DbQueueWorkerManager [startuje se na ApplicationReadyEvent]\n"
    "     - dailyLoop / monthlyLoop threadovi\n"
    "     - while(running): processBatch() | waitForWakeOrTimeout()\n\n"
    "  4. DailyReportWorker.processBatch()\n"
    "     - RecalcQueueService.claimDailyJobIds()  [FOR UPDATE SKIP LOCKED]\n"
    "     - DailyRecalcService.processJob(jobId)\n\n"
    "  5. DailyRecalcService.processJob()\n"
    "     read phase:  fetchuje work logove\n"
    "     write TX:\n"
    "       bulk delete DailyReportCategory\n"
    "       rebuild kategorija (performance rate, weighted koeficijent)\n"
    "       fillDailyTotals() (WORK/ABSENCE/SICK_LEAVE agregacija)\n"
    "       save DailyReport + categories\n"
    "       job → DONE\n"
    "       enqueueMonthlyJob()  → UPSERT monthly_report_recalc_queue (PENDING)\n"
    "       WebSocket push: /topic/reports/daily\n\n"
    "  6. MonthlyReportWorker.processBatch()\n"
    "     - RecalcQueueService.claimMonthlyJobIds()  [FOR UPDATE SKIP LOCKED]\n"
    "     - MonthlyRecalcService.processJob(jobId)\n\n"
    "  7. MonthlyRecalcService.processJob()\n"
    "     read phase:  fetchuje sve DailyReport-e + kategorije za mesec\n"
    "     write TX:\n"
    "       bulk delete MonthlyReportCategory\n"
    "       rebuild monthly kategorija (sum po WorkCodeCategory)\n"
    "       fillMonthlyTotals() (sum daily reporta za mesec)\n"
    "       save MonthlyReport + categories  (bumpa @Version)\n"
    "       job → DONE\n"
    "       WebSocket push: /topic/reports/monthly\n\n"
    "RETRY:\n"
    "  Exception → markFailed() → exponential backoff retry (do maxRetry) → FAILED\n"
    "  Stuck IN_PROGRESS recovery: requeueStuckDailyJobs/requeueStuckMonthlyJobs svakih 20 loopova\n\n"
    "STATUS FLOW QUEUE JOBA:\n"
    "  PENDING → IN_PROGRESS → DONE\n"
    "                        ↘ PENDING (reschedule - version mismatch ili retry)\n"
    "                        ↘ FAILED  (maxRetry exceeded)"
))
story.append(sp(8))

doc.build(story)
print(f"PDF generisan: {OUTPUT}")


