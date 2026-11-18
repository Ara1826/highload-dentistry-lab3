--liquibase formatted sql

--changeset catalog:003-authentication-and-roles
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);
UPDATE users SET role = 'SUPERVISOR' WHERE role = 'DIRECTOR';
