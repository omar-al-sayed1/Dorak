-- Billing: one general plan for every clinic, which a clinic's subscription can override.
-- The discount is no longer "for the clinic's usual period": there is one for paying 6 months at
-- once and one for paying a year.

create table billing_plan (
    -- A single row.
    id                  smallint       primary key check (id = 1),
    monthly_fee_usd     numeric(10, 2) not null check (monthly_fee_usd >= 0),
    discount_6_percent  int            not null check (discount_6_percent between 0 and 100),
    discount_12_percent int            not null check (discount_12_percent between 0 and 100),
    grace_days          int            not null check (grace_days between 0 and 90),
    message_costs       text           not null check (message_costs in ('INCLUDED', 'ON_TOP'))
);
insert into billing_plan (id, monthly_fee_usd, discount_6_percent, discount_12_percent, grace_days, message_costs)
values (1, 0, 0, 0, 14, 'INCLUDED');

-- On a subscription these are now the clinic's own terms: null means "as the general plan".
-- Subscriptions that exist keep the terms they were given.
alter table subscription
    add column discount_6_percent  int check (discount_6_percent between 0 and 100),
    add column discount_12_percent int check (discount_12_percent between 0 and 100),
    alter column monthly_fee_usd drop not null,
    alter column message_costs drop not null,
    alter column grace_days drop not null,
    alter column grace_days drop default;

update subscription set discount_6_percent = discount_percent where period_months = 6 and discount_percent > 0;
update subscription set discount_12_percent = discount_percent where period_months = 12 and discount_percent > 0;

alter table subscription drop column discount_percent;
