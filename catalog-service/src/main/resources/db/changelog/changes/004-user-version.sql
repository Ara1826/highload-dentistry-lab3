--liquibase formatted sql

--changeset catalog:004-user-version
ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
