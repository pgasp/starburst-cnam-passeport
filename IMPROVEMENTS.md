# Améliorations de l'implémentation

Ce document liste les optimisations architecturales implémentées dans le plugin.

## Refonte de la persistance (Depuis v1.1.10)

Historiquement, le plugin utilisait JDBC pour persister les habilitations de l'utilisateur (ses périmètres) dans une table `user_perimetre` (ex. PostgreSQL ou dans un catalogue `system`). Cette approche avait de graves inconvénients de performance et de sécurité :
1. Une sous-requête SQL lente `SELECT perimetre FROM system.passeport.user_perimetre` était générée pour chaque Row Filter.
2. Le moteur devait maintenir des connexions JDBC ouvertes (pool).
3. Le Row Filter (`PasseportSystemAccessControl`) évaluait la sous-requête avec des droits "Definer", ce qui cassait l'héritage des rôles et provoquait des rejets intempestifs de BIAC.

**L'écriture JDBC a été intégralement supprimée.**

### Le cache en mémoire (Guava)

Aujourd'hui, le `PasseportGroupProvider` utilise exclusivement le `PasseportAuthCache` et le `PasseportPerimetreCache`. 
- Lorsque l'utilisateur se connecte, l'API Passeport est interrogée.
- Les groupes (ex: codes CAISSE) et les périmètres (ex: codes IDF, PACA) sont stockés dans un cache interne de la JVM (TTL de 15 minutes).
- Lorsqu'une requête est soumise sur une table sécurisée, le `PasseportSystemAccessControl` lit le cache local, et construit une clause `IN` statique et instantanée : `region_id IN ('IDF', 'PACA')`.
- Si le cache est vide (ou si l'API a retourné une erreur), le plugin applique un filtre `FALSE`, garantissant un "fail-closed" ultra-sécurisé interdisant l'accès aux données sans briser la requête globale.
