# AGENTS.md

## Project Snapshot
- Stack: Spring Boot 4.0.1, Java 21, Maven wrapper (`./mvnw`), PostgreSQL, JPA/Hibernate, Spring Security JWT, STOMP WebSocket, Springdoc OpenAPI UI.
- Package style is feature-first under `src/main/java/com/aleksandarparipovic/marel_app/*` (each domain owns controller/service/repository/dto/mapper where needed).
- No existing agent-specific rule files were found via glob search (`README.md`, `.github/copilot-instructions.md`, `AGENT*.md`, `CLAUDE.md`, cursor/windsurf/cline rules).

## Architecture You Need To Understand First
- Core flow is async recalculation, not direct synchronous report writes:
  - `work_log/WorkLogService.handleBatch()` mutates logs and enqueues daily jobs.
  - `report_worker/DailyReportWorker` and `report_worker/MonthlyReportWorker` are scheduled pollers (`@Scheduled`, fixedDelay 2000ms/5000ms) that claim queue jobs in batches (5/3) and delegate processing.
  - `recalc_queue/RecalcQueueService` claims jobs with `FOR UPDATE SKIP LOCKED` and marks them `PROCESSING`.
  - `report_worker/DailyRecalcService` recalculates daily report + categories, then enqueues monthly.
  - `report_worker/MonthlyRecalcService` rebuilds monthly report + categories.
  - `payroll_run_item/PayrollRunItemService.getForPayrollAccess()` lazily refreshes stale payroll items via `monthly_report.version` vs `based_on_version`.
  - `payroll_run_item/PayrollRunItemService.getForPayrollRun()` applies the same stale-version refresh semantics for run-wide item fetches (except `LOCKED` items).
- `MarelAppApplication` enables scheduling and AOP (`@EnableScheduling`, `@EnableAspectJAutoProxy`), so workers/aspects are always active in app runtime.

## Concurrency + Data Integrity Patterns (Project-Specific)
- Queue repos use pessimistic row locks (`DailyRecalcQueueRepository.findByIdForUpdate`, `MonthlyRecalcQueueRepository.findByIdForUpdate`).
- Workers are idempotent at job level: skip if status is already `PROCESSED`/`FAILED`.
- Category rebuild pattern is bulk delete then full rebuild (`DailyReportCategoryRepository.deleteAllByDailyReportId`, `MonthlyReportCategoryRepository.deleteAllByMonthlyReportId`).
- Aggregations are intentionally single-pass loops for performance (see `aggregateLogsInSinglePass`, `aggregateDailyReportsInSinglePass`).
- Report freshness is version-driven (`@Version` in reports + payroll lazy recalc).

## API + Security Conventions
- Security is stateless JWT (`config/SecurityConfig`, `auth/JwtAuthenticationFilter`); refresh tokens are rotated and hashed (`auth/refresh/RefreshTokenService`).
- Auth endpoints are open under `/api/auth/**`; most other endpoints require auth; admin-only routes enforced by role in `SecurityConfig`.
- Additional open routes are `/api/departments/**` and `/ws/**`; admin role checks are explicit on `/api/users/**` and `/api/roles/**` in `SecurityConfig`.
- Controllers typically return `ResponseEntity` and throw exceptions upward; `common/GlobalExceptionHandler` standardizes error payloads.
- Soft-delete pattern is common (`isActive`, `archivedAt`) instead of hard deletes in many domains.

## Realtime + Integration Points
- WebSocket endpoint is `/ws` with SockJS (`config/WebSocketConfig`); backend publishes on `/topic/reports/daily` and `/topic/reports/monthly` (`notification/ReportNotificationService`).
- Audit context is injected per service invocation by `audit/AuditUserAspect` through Postgres session config `set_config('app.user_id', ...)`.
- Bootstrap admin is auto-created at startup if missing (`bootstrap/AdminInitializer`) using properties from `application.properties`.

## Local Dev Workflows
- Start app: `./mvnw spring-boot:run`
- Run tests: `./mvnw test`
- Full verification/build: `./mvnw clean verify` and `./mvnw clean package`
- Current test suite is minimal (`src/test/java/.../MarelAppApplicationTests.java`), so regression checks should focus on targeted manual/API validation around queue workers and payroll refresh behavior.

## Editing Guidance For Agents
- When changing work-log, daily, monthly, or payroll logic, trace the whole chain (enqueue -> worker -> version bump -> payroll access) before editing.
- Preserve queue status semantics (`PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`) and retry handling in `markFailed` methods.
- If changing payroll freshness logic, keep `getForPayrollAccess()`, `getForPayrollRun()`, and `refreshIfStale()` semantics aligned (including `LOCKED` short-circuit behavior).
- Prefer extending existing feature package patterns rather than adding cross-cutting utility layers.
- Treat credentials in `src/main/resources/application.properties` as local/dev defaults; do not propagate them into docs, logs, or tests.

## Business-Rule Documentation (Read Before Editing These Domains)
- Before modifying user approval, manufacturing-time requests, mailing lists, production-order recipients, notifications, sessions or user preferences, read `docs/business-rules/user-requests-mailing-notifications-and-preferences.md`.
- `docs/business-rules/IMPLEMENTATION-STATUS.md` is the record of what is implemented versus schema-only, plus remaining work and known risks. Check it before assuming an endpoint exists.
- Schema for those domains ships as dated SQL in `src/main/resources/sql/2026-07-21-01..09-*.sql`. The numeric prefixes are load-bearing: they are the required application order.
- `users.account_status` is the authoritative account state; `users.is_active` is derived from it by a trigger and must never be used to express a workflow decision.
