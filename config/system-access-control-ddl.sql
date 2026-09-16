-- Déploiement SystemAccessControl / user_perimetre sur pga
-- Catalog cible : postgres (le catalog "system" par défaut du plugin ne supporte pas CREATE TABLE)

CREATE SCHEMA IF NOT EXISTS postgres.security_control;

-- Colonnes strictement alignées sur ce que PasseportGroupProvider.writePerimetresToDb() écrit
-- (upn, code_pa, perimetre) -- pas de colonne updated_at, le code ne la peuple pas.
CREATE TABLE IF NOT EXISTS postgres.security_control.user_perimetre (
    upn        VARCHAR NOT NULL,
    code_pa    VARCHAR NOT NULL,
    perimetre  VARCHAR NOT NULL
);
