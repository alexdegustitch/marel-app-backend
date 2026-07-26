-- Reference data the audit trigger depends on.
-- audit_trigger_fn resolves table_id/action_id by NAME from these two tables.
-- Without the rows, every insert into an audited table fails with a NOT NULL
-- violation on audit_logs.table_id. This is configuration, not business data.
-- Generated with: pg_dump --data-only --table=audit_actions --table=audit_tables

--
-- PostgreSQL database dump
--


-- Dumped from database version 18.1 (Homebrew)
-- Dumped by pg_dump version 18.1 (Homebrew)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: audit_actions; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.audit_actions (id, action_name) FROM stdin;
1	insert
2	update
3	delete
4	login
5	logout
6	system
\.


--
-- Data for Name: audit_tables; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.audit_tables (id, table_name) FROM stdin;
1	absence_records
2	app_settings
3	bonus_categories
4	bonus_eligibility_rules
5	departments
6	employees
7	employees_bonus_history
8	livac_categories
9	manufacturing_product_times
10	manufacturing_time_operations
13	operations
14	operations_history
15	plastic_categories
16	production_order_line_items
17	production_orders
18	products
20	roles
21	sample_order_line_items
22	sample_orders
23	scraps
24	shifts
25	users
26	work_code_categories
27	work_logs
28	work_shifts
29	work_code_category_mappings
11	manufacturing_time_requests_legacy
19	requests_legacy
30	product_manufacturing_times
31	manufacturing_time_requests
12	notifications_legacy
32	user_registration_requests
33	mailing_lists
34	mailing_list_members
35	production_order_mailing_lists
36	production_order_recipients
37	notification_events
\.


--
-- Name: audit_tables_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.audit_tables_id_seq', 37, true);


--
-- PostgreSQL database dump complete
--


