-- Billing: a clinic owner pays the subscription online (Whish). One row per attempt: created when the
-- owner presses Pay, settled once the provider confirms it. A paid attempt is recorded as an ordinary
-- subscription_payment (method WHISH), so invoices and the due day work as for any payment.
create table online_payment (
    -- Our own id for the attempt, sent to the provider: random, so a return link says nothing.
    id                      uuid           primary key,
    clinic_id               bigint         not null references clinic (id) on delete cascade,
    provider                text           not null check (provider in ('WHISH')),
    months                  int            not null check (months between 1 and 36),
    amount_usd              numeric(10, 2) not null check (amount_usd > 0),
    status                  text           not null check (status in ('PENDING', 'PAID', 'FAILED', 'EXPIRED')),
    -- The provider's page where the owner pays.
    pay_url                 text,
    -- The provider's own reference for the payment, once paid.
    provider_reference      text,
    created_by              text           not null,
    created_at              timestamptz    not null,
    completed_at            timestamptz,
    -- The payment it became; null again if the operator undoes that payment.
    subscription_payment_id bigint         unique references subscription_payment (id) on delete set null
);
create index online_payment_clinic_idx on online_payment (clinic_id, created_at desc);
create index online_payment_pending_idx on online_payment (created_at) where status = 'PENDING';
