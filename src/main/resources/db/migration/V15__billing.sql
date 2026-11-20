-- Billing: what each clinic pays for the service, and the payments the operator records.
-- A clinic without a subscription row is not billed and is never paused.

create table subscription (
    clinic_id        bigint         primary key references clinic (id) on delete cascade,
    monthly_fee_usd  numeric(10, 2) not null check (monthly_fee_usd >= 0),
    -- How many months one payment usually covers, and the discount for paying that way.
    period_months    int            not null check (period_months in (1, 3, 6, 12)),
    discount_percent int            not null default 0 check (discount_percent between 0 and 100),
    -- Meta's message costs: part of the fee, or added to the next invoice from the estimates in message_log.
    message_costs    text           not null check (message_costs in ('INCLUDED', 'ON_TOP')),
    -- The day the next payment is due: the service is paid for up to (not including) this day.
    paid_until       date           not null,
    -- Days after paid_until before new bookings are paused.
    grace_days       int            not null default 14 check (grace_days between 0 and 90),
    -- Who to remind, E.164; the operator writes to them from their own WhatsApp.
    contact_phone    text,
    -- Message costs from this moment on are not invoiced yet.
    costs_from       timestamptz    not null,
    created_at       timestamptz    not null default now()
);

create table subscription_payment (
    id                bigint generated always as identity primary key,
    clinic_id         bigint         not null references clinic (id) on delete cascade,
    months            int            not null check (months between 1 and 36),
    period_start      date           not null,
    period_end        date           not null,
    fee_usd           numeric(10, 2) not null check (fee_usd >= 0),
    discount_usd      numeric(10, 2) not null check (discount_usd >= 0),
    message_costs_usd numeric(10, 2) not null check (message_costs_usd >= 0),
    -- What was actually received.
    amount_usd        numeric(10, 2) not null check (amount_usd >= 0),
    method            text           not null check (method in ('CASH', 'WHISH', 'OMT', 'BANK')),
    reference         text,
    paid_on           date           not null,
    -- The subscription's costs_from before this payment, to put back if the payment is undone.
    costs_from        timestamptz    not null,
    recorded_by       text           not null,
    recorded_at       timestamptz    not null,
    -- The public invoice page's key: random, so the link says nothing.
    invoice_token     uuid           not null unique,
    check (period_end > period_start)
);
create index subscription_payment_clinic_idx on subscription_payment (clinic_id, period_end desc);
create index subscription_payment_paid_idx on subscription_payment (paid_on);
