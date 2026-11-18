--liquibase formatted sql

--changeset appointments:002-participant-pagination
CREATE INDEX ix_appointments_patient_cursor ON appointments(patient_id, created_at DESC, id DESC);
CREATE INDEX ix_appointments_doctor_cursor ON appointments(doctor_id, created_at DESC, id DESC);
