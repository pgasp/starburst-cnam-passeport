# Instructions pour les agents

## Projet
- **Type** : plugin Trino / Starburst Enterprise (SEP), SPI 482, Java 25.
- **Objet** : group provider et fonctions SQL branchés sur l'API d'habilitations Passeport. Voir `README.md` (composants, build) et `INTEGRATION.md` (installation, configuration, recette, destiné aux équipes techniques du client).

## Build et tests
- Maven, **JDK 25 obligatoire** (`pom.xml` : source et target 25). Avec un JDK plus ancien : erreurs de version de fichier de classe.
- `mvn clean verify` : compile, exécute les tests (JUnit 5, WireMock, Mockito) et construit le zip.
- Packaging : `trino-maven-plugin` (provisio) seul produit `target/<artifact>-<version>.zip`, et le descripteur `src/main/provisio/targz.xml` produit le `.tar.gz` de même contenu (même racine unique). Ne pas ajouter de `maven-assembly-plugin` (zip imbriqué sans jar de plugin : `No service providers of type io.trino.spi.Plugin`). Contrôler avec `unzip -l` et `tar tzf` : `<artifact>-<version>.jar` et `-services.jar` présents, aucune entrée `.zip`. Les modes des fichiers viennent du dépôt Maven local (un jar à 0600 le reste dans l'archive).
- Changer la version du `pom.xml` avant tout build destiné à une release ; ne jamais écraser une release existante.

## Conventions SPI
- `trino-spi`, Jackson (annotations) et OpenTelemetry en `<scope>provided</scope>`.
- API Trino 482 : ne pas utiliser d'anciennes conventions (contextes de sécurité historiques, anciens registres de fonctions).
- Dépendances embarquées : seulement ce que le plugin charge réellement (airlift configuration/bootstrap, Guice, Guava, Jackson databind, validation). Après tout changement de dépendances, vérifier le contenu du zip et charger `PasseportGroupProviderFactory` sur le seul classpath du zip.
- Classes : `PasseportPlugin` (enregistrement), `PasseportGroupProvider` / `PasseportAuthService` (appel API, cache Guava) / `PasseportResponseParser` (lecture de la réponse) / `PasseportConfig` (propriétés), `PasseportFunctions` (périmètre du profil actif, périmètre d'un groupe, purge du cache de l'utilisateur), `HashFunctions`, `PseudonymizeFunctions` + `Pseudonymizer`, `PasseportSystemAccessControl`.

## Points importants
- **Fail closed** : une erreur d'API (code différent de 200, délai dépassé, structure de réponse inattendue) lève une `TrinoException` et fait échouer la requête ; l'échec n'est pas mis en cache. Ne pas changer ce comportement sans demande explicite.
- `passeport.api-url` et `passeport.code-application` sont obligatoires, sans valeur par défaut ; ne jamais réintroduire de valeur par défaut propre à un client.
- Aucun nom d'hôte, de table, de colonne ou d'utilisateur réel dans le code, les tests ni la documentation : utiliser des exemples génériques (`passeport.example.org`, `catalog.schema.table`, `user.test`).
- En cas de doute entre la documentation et `pom.xml` / le code, faire confiance au code.
- **Modèle de profils (3.1.0)** : `current_passeport_perimetre()` = périmètre du seul groupe dont le nom est le rôle actif (`getEnabledSystemRoles()` moins `public` et `sysadmin`, casse et espaces de bord ignorés) ; aucun profil ou aucun groupe correspondant : `[]` ; **plusieurs profils : `TrinoException(NOT_SUPPORTED)`, contrôle fait avant toute lecture de `PasseportAuthService.getGlobalInstance()`** (l'erreur doit sortir même sur un nœud sans service). `passeport_perimetres(code_pa)` = périmètre du groupe si l'appelant en est membre, sans consulter le profil actif. `passeport_perimetre` (3.0.x) est supprimée : ne pas la réintroduire. Un groupe sans périmètre donne `[]` = aucune caisse. Ne pas revenir à la réunion des périmètres. `passeport_cache_invalidate()` : pas de purge globale ; les fonctions restent déterministes (évaluées à la planification, sur le coordinator).
