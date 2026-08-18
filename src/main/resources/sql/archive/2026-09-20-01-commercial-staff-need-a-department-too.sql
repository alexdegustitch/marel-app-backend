-- =============================================================================
-- Commercial staff need a department too
-- =============================================================================
-- THE DECISION
-- Every employee belongs to a department: employees.department_id is NOT NULL,
-- and the reports, kartoni and the employees filter all group by it. When the
-- COMMERCIAL compensation scheme was added it became possible to describe a
-- worker who has no production sector, and the create form asked for one
-- anyway.
--
-- The owner was offered the alternative — make department_id nullable — and
-- chose this instead: keep the column mandatory and give commercial staff a
-- department of their own. That costs nothing structurally. Making it nullable
-- would have meant a worker who silently drops out of every report that joins
-- departments, and a "no sector" case to handle in each of them.
--
-- WHAT THIS DOES
-- Adds the department. That is all. NOBODY is moved into it: the only employee
-- ever flagged as commercial (id 6) was ruled a data error by the owner and
-- stays on STANDARD in their existing sector. Whoever belongs here gets put
-- here through the employee screen, one person at a time, by somebody who knows.
--
-- NOT LINKED TO THE SCHEME. Nothing checks that a COMMERCIAL-scheme employee
-- sits in this department, or that everybody here is on that scheme. A
-- department is where somebody works and a scheme is how they are paid; the
-- 2026-09-19-03 migration exists precisely because those two had been conflated
-- once already.
--
-- Re-runnable.
-- =============================================================================

INSERT INTO departments (name, description, is_active, created_at)
SELECT 'Komercijala',
       'Prodaja i komercijalni poslovi. Nije proizvodni sektor — radnici ovde nemaju normu.',
       TRUE,
       now()
WHERE NOT EXISTS (
    SELECT 1 FROM departments WHERE name = 'Komercijala'
);
