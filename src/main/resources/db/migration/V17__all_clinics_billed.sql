-- Billing: every clinic is billed by the general plan, and a deleted clinic can be brought back for
-- a few minutes.

-- The general plan's starting terms: $40 a month, 5% off for 6 months at once, 10% off for a year.
-- Only where the operator has not set a plan yet.
update billing_plan
set monthly_fee_usd = 40, discount_6_percent = 5, discount_12_percent = 10
where monthly_fee_usd = 0 and discount_6_percent = 0 and discount_12_percent = 0;

-- After the due day a clinic keeps working for 2 more days, then new bookings pause (it was 14).
update billing_plan set grace_days = 2 where grace_days = 14;

-- Clinics that were not billed yet now are: a month from today as their first due day, paying a
-- month at a time, on the general plan's terms. New clinics get the same when they are created.
insert into subscription (clinic_id, period_months, paid_until, costs_from)
select c.id, 1, (current_date + interval '1 month')::date, now()
from clinic c
where not exists (select 1 from subscription s where s.clinic_id = c.id);

-- A deleted clinic, kept whole for a few minutes so that the operator can undo the deletion; then
-- purged. data holds every row that was the clinic's, by table.
create table deleted_clinic (
    token      uuid        primary key,
    name       text        not null,
    deleted_at timestamptz not null,
    data       jsonb       not null
);
