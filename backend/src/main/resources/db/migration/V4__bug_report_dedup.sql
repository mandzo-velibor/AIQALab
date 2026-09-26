-- Deduplicate bug reports by failure signature (B-032).
--
-- A report used to be created once per execution, from the whole-run console output, so
-- a known bug came back as a new report on every run and twenty failing tests produced
-- one vague entry. dedup_key is a normalised fingerprint of the failing assertion, so the
-- same bug collapses onto the same row and can be counted instead of re-filed.
--
-- occurrences records how many runs have hit it: a recurring bug is more urgent than a
-- fresh one, and the count is the only evidence of that.
--
-- first_seen_run keeps the run that originally exposed it, so a report can be traced
-- back to the execution that proved the bug exists.

alter table if exists bug_report
    add column dedup_key varchar(64),
    add column occurrences integer,
    add column screenshot_path varchar(255),
    add column first_seen_run varchar(255);

-- Lookup is "has this project already filed this failure?", which is always
-- project-scoped. Without the index that is a sequential scan of every report the
-- account has ever filed, on every run.
create index if not exists idx_bug_report_dedup
    on bug_report (project_id, dedup_key);
