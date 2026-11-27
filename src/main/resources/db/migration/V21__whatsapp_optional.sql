-- WhatsApp is optional per clinic. Off: no reminders and no WhatsApp messages at all, and the clinic's
-- dashboard has nothing WhatsApp in it; such a clinic pays the general plan's lower fee. Clinics that
-- exist keep WhatsApp on.
alter table clinic add column whatsapp_enabled boolean not null default true;

-- The general plan's monthly fee for a clinic without WhatsApp ($20 to start; the operator changes it).
alter table billing_plan add column monthly_fee_no_whatsapp_usd numeric(10, 2) not null default 20
    check (monthly_fee_no_whatsapp_usd >= 0);
