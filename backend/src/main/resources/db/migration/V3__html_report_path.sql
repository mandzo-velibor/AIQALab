-- Where the self-contained HTML report was written (B-029).
--
-- Stored rather than derived. The report is written into the artifact directory
-- (<artifactsDir>/execution-<id>/report.html), and neither the workflow response nor
-- the CLI knows that path — it is not a function of the page URL or the execution id
-- alone. Without this column the report exists on disk and nothing can point at it.

alter table if exists test_execution
    add column html_report_path varchar(255);
