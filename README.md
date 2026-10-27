# Plugin Passeport pour Starburst Enterprise (SEP) / Trino

Plugin Trino SPI qui relie SEP à l'API d'habilitations Passeport : les groupes d'un utilisateur et son périmètre de données sont lus à la volée, sans synchronisation d'annuaire.

Mode opératoire d'installation, de configuration et de recette : [INTEGRATION.md](INTEGRATION.md) (aussi embarqué dans le zip).

## Composants

| Composant | Classe | Rôle |
|---|---|---|
| Group provider `passeport` | `PasseportGroupProvider` | Appelle `GET <passeport.api-url>/<upn>/<passeport.code-application>` et renvoie les `code_pa` comme groupes. |
| Fonction `current_passeport_perimetre()` | `PasseportFunctions` | Renvoie `array(varchar)` : le périmètre du groupe Passeport dont le nom est égal au rôle BIAC actif (`public` et `sysadmin` ignorés). Aucun profil actif ou aucun groupe correspondant : tableau vide (fail closed ; un périmètre vide signifie aucune caisse, jamais toutes). **Plusieurs profils actifs : la requête échoue** (`NOT_SUPPORTED`, `Unsupported multi perimetre`), il faut `SET ROLE <profil>`. À utiliser dans les row filters. |
| Fonction `passeport_perimetres(code_pa)` | `PasseportFunctions` | Renvoie `array(varchar)` : le périmètre du groupe indiqué si l'utilisateur appelant en est membre, tableau vide sinon (ou si l'argument est `NULL`). Ne consulte pas le profil actif : pour le diagnostic, pas pour les row filters. |
| Fonction `passeport_cache_invalidate()` | `PasseportFunctions` | Invalide l'entrée de cache de l'utilisateur courant seulement (booléen) ; pas de purge globale. Évaluée sur le coordinator. |
| Fonctions `hash_user_salt`, `pseudonymize`, `current_user_biac_roles` | `HashFunctions`, `PseudonymizeFunctions`, `PasseportFunctions` | Hors du périmètre Passeport ; inactives sans `GRANT EXECUTE` dans BIAC. |
| Contrôle d'accès système `passeport` | `PasseportSystemAccessControl` | Row filters par table configurés en propriétés. Ne l'utiliser qu'enchaîné avec BIAC : ses vérifications autorisent toutes les opérations. |

Réponse de l'API lue dans deux formes (par élément) : imbriquée `[{"app":{"code_app":"X"},"listepa":[{"code_pa":"A","perimetre":"TOUS"}]}]` et plate `[{"code_pa":"A","perimetre":"TOUS"}]`. Un périmètre `"011,717"` est découpé sur la virgule. Toute autre structure fait échouer la requête (fail closed).

## Configuration (`group-provider.properties`)

| Propriété | Obligatoire | Défaut |
|---|---|---|
| `passeport.api-url` | oui | aucun |
| `passeport.code-application` | oui | aucun |
| `passeport.trust-store-path`, `passeport.trust-store-password` | non | truststore de la JVM |
| `passeport.cache-ttl` | non | `15m` |
| `passeport.cache-max-size` | non | `10000` |

Détails et exemple dans INTEGRATION.md.

## `pseudonymize(varchar) -> varchar`

HMAC-SHA256 à clé secrète (`javax.crypto.Mac`), sortie en hexadécimal majuscule (64 caractères), entrée normalisée (`strip` puis majuscules) ; `NULL` ou valeur vide donne `NULL`. La clé (32 octets minimum, en hexadécimal) est lue dans la variable d'environnement `PSEUDONYMIZATION_HMAC_KEY` du coordinator et des workers (secret Kubernetes), au premier appel ; absente ou invalide, la fonction échoue (`CONFIGURATION_INVALID`). Changer la clé change tous les pseudonymes. Ne pas accorder `EXECUTE` aux utilisateurs finaux : réidentification possible par dictionnaire sur un identifiant à format connu.

## Build

JDK 25 et Maven :

```bash
mvn clean verify
```

Livrables : `target/passeport-group-provider-<version>.zip` (produit par `trino-maven-plugin`) et `target/passeport-group-provider-<version>.tar.gz` (même contenu, second descripteur provisio `src/main/provisio/targz.xml`). Le zip : un dossier `passeport-group-provider-<version>/` avec le jar du plugin, `-services.jar` (descripteur `META-INF/services/io.trino.spi.Plugin`), les dépendances et `INTEGRATION.md` (`src/main/provisio/docs.xml`). N'ajouter aucun `maven-assembly-plugin` : il réécrirait un zip imbriqué sans jar de plugin. Contrôle : `unzip -l` doit lister `<artifact>-<version>.jar` et `-services.jar`, et aucune entrée `.zip`.

Les dépendances `trino-spi`, Jackson (annotations) et OpenTelemetry sont en portée `provided`.

## Version 3.1.0 : changement cassant

`passeport_perimetre()` et `passeport_perimetre(code_pa)` sont **supprimées**, remplacées par `current_passeport_perimetre()` (profil actif, erreur si plusieurs profils actifs) et `passeport_perimetres(code_pa)` (groupe dont l'appelant est membre, sans tenir compte du profil actif). Voir [CHANGELOG.md](CHANGELOG.md) et la section « Migration depuis la 3.0.x » d'INTEGRATION.md (row filters à réécrire, `GRANT EXECUTE` BIAC à recréer sur les nouveaux noms). Une session CLI ou JDBC sans `SET ROLE` qui active plusieurs rôles verra ses requêtes via row filter échouer : utiliser `SET ROLE <profil>`.
