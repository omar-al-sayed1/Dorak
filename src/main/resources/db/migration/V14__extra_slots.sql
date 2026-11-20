-- One extra slot on one date, added from the week page: the doctor sees one more patient that day
-- without changing their weekly hours. It lasts the doctor's appointment length.
create table extra_slot (
    id         bigint generated always as identity primary key,
    clinic_id  bigint not null,
    doctor_id  bigint not null,
    slot_date  date   not null,
    start_time time   not null,
    unique (doctor_id, slot_date, start_time),
    foreign key (doctor_id, clinic_id) references doctor (id, clinic_id) on delete cascade
);
create index extra_slot_doctor_date_idx on extra_slot (doctor_id, slot_date);
