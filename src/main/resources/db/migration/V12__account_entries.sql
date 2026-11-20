-- Balance: an amount owed that belongs to no visit (an old debt, a balance brought over). Such a
-- charge has no appointment; a payment on the account already could (appointment_id is nullable).
alter table charge alter column appointment_id drop not null;
