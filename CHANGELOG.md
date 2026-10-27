# Changelog

## 3.1.0 (changement cassant)

### Ajouté
- `current_passeport_perimetre()` : `array(varchar)`, périmètre du profil actif (le seul rôle actif hors `public` et `sysadmin`, dont le nom est égal à un groupe Passeport). Aucun profil actif ou aucun groupe correspondant : `[]`. Doublon de `code_pa` à la casse près : `[]` et un WARNING. Un périmètre vide signifie aucune caisse, jamais toutes.
- **Plusieurs profils actifs : la requête échoue** (`NOT_SUPPORTED`) avec `Unsupported multi perimetre: <n> profils actifs [<rôles triés>]. Choisissez un profil avec SET ROLE <profil>`. Le contrôle est fait avant toute lecture du service Passeport : l'erreur sort aussi sur un nœud sans service.
- `passeport_perimetres(code_pa)` : `array(varchar)`, périmètre du groupe indiqué si l'utilisateur appelant en est membre, `[]` sinon (y compris argument `NULL` et doublon à la casse près, avec un WARNING). Ne consulte pas le profil actif : pour le diagnostic, pas pour les row filters.

### Supprimé
- `passeport_perimetre()` et `passeport_perimetre(varchar)`.

### Inchangé
- `passeport_cache_invalidate()`, `current_user_biac_roles()`, group provider, contrôle d'accès système, pseudonymisation, hash.

### Migration depuis la 3.0.x
1. Réécrire les row filters : `passeport_perimetre()` devient `current_passeport_perimetre()` ; `passeport_perimetre('<code_pa>')` devient `current_passeport_perimetre()` (un seul filtre pour tous les profils).
2. Recréer les `GRANT EXECUTE` BIAC sur les nouveaux noms (`current_passeport_perimetre` pour chaque rôle de profil, `passeport_perimetres` pour le diagnostic).
3. Avertissement : une session CLI ou JDBC sans `SET ROLE` qui active plusieurs rôles verra ses requêtes via row filter échouer au lieu de renvoyer un résultat vide. Utiliser `SET ROLE <profil>`.
4. Recommandation : `current_passeport_perimetre()` dans les row filters ; `passeport_perimetres(code_pa)` pour le diagnostic uniquement.

Détail : INTEGRATION.md, section 7.

## 3.0.24
- `passeport_perimetre()` renvoie le périmètre du seul groupe dont le nom est le rôle actif (jusqu'à la 3.0.23 : réunion des périmètres de tous les groupes) ; surcharge `passeport_perimetre(code_pa)` ; `passeport_cache_invalidate()`.

## 3.0.23
- Acceptation des tableaux JSON entourés d'apostrophes et du JSON double-encodé dans la réponse de l'API ; messages d'erreur et avertissements plus clairs.
