-- Balance: the lira rate is fixed (89,500 to the dollar, in the code) and doctors have no usual fee,
-- so neither is stored any more. Payments keep the rate they were made at.
drop table lbp_rate;
drop table doctor_fee;
