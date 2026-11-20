-- Balance (P1, SPEC section 5.6): what patients were charged and what they paid. A ledger only:
-- nothing here charges a card. Never anything medical: no descriptions on charges.
-- Append-only: never edit once applied.

-- Each doctor's usual consultation fee, offered when a visit is charged.
create table doctor_fee (
    doctor_id  bigint         primary key,
    clinic_id  bigint         not null,
    amount_usd numeric(10, 2) not null check (amount_usd >= 0),
    foreign key (doctor_id, clinic_id) references doctor (id, clinic_id)
);

-- The clinic's Lebanese pound rate of the day, for payments made in lira.
create table lbp_rate (
    clinic_id   bigint         primary key references clinic (id),
    lbp_per_usd numeric(12, 2) not null check (lbp_per_usd > 0),
    updated_at  timestamptz    not null
);

-- What a visit costs: one charge per appointment. Goes with the patient when they are deleted.
create table charge (
    id             bigint generated always as identity primary key,
    clinic_id      bigint         not null,
    patient_id     bigint         not null,
    appointment_id bigint         not null unique,
    amount_usd     numeric(10, 2) not null check (amount_usd >= 0),
    created_at     timestamptz    not null default now(),
    foreign key (patient_id, clinic_id) references patient (id, clinic_id) on delete cascade,
    foreign key (appointment_id, clinic_id) references appointment (id, clinic_id) on delete cascade
);
create index charge_clinic_patient_idx on charge (clinic_id, patient_id);

-- What a patient paid, in dollars; in lira too when paid in lira, with the rate used.
create table payment (
    id             bigint generated always as identity primary key,
    clinic_id      bigint         not null,
    patient_id     bigint         not null,
    appointment_id bigint,
    amount_usd     numeric(10, 2) not null check (amount_usd > 0),
    method         text           not null check (method in ('CASH', 'WHISH', 'OMT', 'CARD', 'BANK')),
    amount_lbp     numeric(14, 0) check (amount_lbp > 0),
    lbp_per_usd    numeric(12, 2) check (lbp_per_usd > 0),
    paid_at        timestamptz    not null,
    -- The public receipt page's key: random, so the link says nothing.
    receipt_token  uuid           not null unique default gen_random_uuid(),
    check ((amount_lbp is null) = (lbp_per_usd is null)),
    foreign key (patient_id, clinic_id) references patient (id, clinic_id) on delete cascade,
    foreign key (appointment_id, clinic_id) references appointment (id, clinic_id) on delete set null (appointment_id)
);
create index payment_clinic_paid_idx on payment (clinic_id, paid_at);
create index payment_clinic_patient_idx on payment (clinic_id, patient_id);
create index payment_appointment_idx on payment (appointment_id);

-- Closing the day: the cash the drawer should hold, and what was counted.
create table day_close (
    id                bigint generated always as identity primary key,
    clinic_id         bigint         not null references clinic (id),
    day               date           not null,
    expected_cash_usd numeric(10, 2) not null,
    counted_cash_usd  numeric(10, 2) not null check (counted_cash_usd >= 0),
    closed_by         text           not null,
    closed_at         timestamptz    not null,
    unique (clinic_id, day)
);
