# Plugin Passeport pour Starburst Enterprise : mode opératoire d'intégration

Version du plugin : 3.0.22. Document destiné aux équipes techniques chargées de l'installation, de la configuration et de la recette.

Ce document couvre le group provider et la fonction SQL de périmètre, pour les tests T01 à T03.

---

## 1. Objet et périmètre

### Ce que fait le plugin

- **Group provider `passeport`** : à chaque requête SQL, SEP demande au plugin les groupes de l'utilisateur. Le plugin interroge l'API Passeport avec l'UPN de l'utilisateur et le `code_application` configuré, et renvoie les codes profil (`code_pa`) comme groupes. SEP fait correspondre le nom de chaque groupe au nom d'un rôle BIAC.
- **Fonction SQL `passeport_perimetre()`** : renvoie, sous forme de `array(varchar)`, le périmètre (par exemple les caisses) de l'utilisateur courant, issu de la même réponse de l'API. Elle sert dans un row filter BIAC.
- Les groupes et le périmètre sont gardés en mémoire (cache) pendant une durée configurable (15 minutes par défaut).
- En cas d'erreur de l'API, la requête échoue (fail closed, voir section 8).

### Ce que le plugin ne couvre pas encore

- Le **chiffrement réversible** : prévu dans une version ultérieure.
- La **purge du cache** à la demande (par utilisateur ou globale) : prévue dans une version ultérieure. Dans cette version, une habilitation modifiée est prise en compte à l'expiration du TTL (section 8).

### Autres fonctions présentes dans le plugin

`hash_user_salt`, `pseudonymize` et `current_user_biac_roles` sont présentes mais hors périmètre de ce document. Elles sont inactives tant qu'aucun `GRANT EXECUTE` ne les concerne (section 7).

Le plugin embarque aussi un composant de contrôle d'accès système (`access-control.name=passeport`). Il n'est pas utilisé ici et ne doit pas être configuré.

---

## 2. Prérequis

### Version de SEP et de Java

| Élément | Valeur |
|---|---|
| SPI Trino | 482 (le plugin est compilé contre `trino-spi` 482) |
| Java | 25 (classes compilées pour Java 25 ; une JVM plus ancienne refuse de charger le plugin avec `UnsupportedClassVersionError`) |
| Version SEP visée | 482-e |
| Version SEP 480-e | compatibilité **à valider** |

Vérification, avant installation :

1. Version du serveur : `SELECT version();` (ou la page d'information du cluster).
2. Version de Java du coordinator : `kubectl exec <pod-coordinator> -- java -version` (ou `oc exec`).
3. Sur une version SEP différente de la 482-e (par exemple la 480-e), installer le plugin sur un environnement de test et dérouler la recette de la section 9. Une incompatibilité d'API du SPI se manifeste au démarrage ou au premier appel par `NoSuchMethodError`, `NoClassDefFoundError` ou `AbstractMethodError` dans les logs du coordinator.

### Flux réseau vers l'API

- Le **coordinator** appelle l'API Passeport en HTTP(S) (les workers n'appellent pas l'API : la configuration du group provider n'est lue que par le coordinator).
- Ouvrir le flux du pod coordinator vers l'hôte et le port de l'API (NetworkPolicy, pare-feu, proxy éventuel). Le plugin n'utilise pas de proxy : l'accès doit être direct.
- Le plugin n'envoie aucun en-tête d'authentification (voir section 6).
- Délais : 10 s pour la connexion, 15 s pour la réponse.

### Truststore si l'API est en TLS

- Si le certificat de l'API est émis par une autorité absente du truststore de la JVM, fournir un truststore (JKS ou PKCS12) contenant la chaîne de certification, et le monter dans le pod **coordinator**. Le format JKS et le format PKCS12 ont été chargés avec succès lors de la validation du plugin.
- Sans `passeport.trust-store-path`, le truststore par défaut de la JVM est utilisé.
- Un truststore illisible ou au mauvais mot de passe empêche le démarrage du group provider (fail closed).
- Création d'un truststore PKCS12 à partir d'un certificat d'autorité :

```bash
keytool -importcert -noprompt -alias passeport-ca -file autorite.crt \
        -keystore passeport-truststore.p12 -storetype PKCS12
```

Le truststore n'est lu qu'au démarrage : un renouvellement de certificat impose un redémarrage du coordinator.

### `code_application`

Code de l'application Passeport correspondant au projet, transmis tel quel dans l'URL. C'est un paramètre de configuration, sans valeur par défaut. Il doit être fourni par la CNAM (section 11).

### UPN fourni par le SSO

Le plugin interroge l'API avec l'identifiant de l'utilisateur tel que le voit SEP après authentification. Cet identifiant doit être l'**UPN** attendu par l'API Passeport. Si le SSO fournit un autre attribut (adresse e-mail, identifiant court), l'API ne trouvera pas l'utilisateur. Vérifier que l'attribut utilisé comme nom d'utilisateur par SEP est bien l'UPN (section 11).

---

## 3. Contenu et vérification du livrable

Livrable : `passeport-group-provider-3.0.22.zip`, accompagné de son empreinte SHA-256 communiquée séparément.

Contenu : un dossier unique `passeport-group-provider-3.0.22/` contenant

| Fichier | Rôle |
|---|---|
| `passeport-group-provider-3.0.22.jar` | code du plugin |
| `passeport-group-provider-3.0.22-services.jar` | descripteur de chargement du plugin |
| `INTEGRATION.md` | ce document |
| `bootstrap-234.jar`, `configuration-234.jar`, `log-234.jar`, `units-1.0.jar` | bibliothèques de configuration (airlift) |
| `guice-7.0.0.jar`, `aopalliance-1.0.jar`, `jakarta.inject-api-2.0.1.jar`, `jakarta.annotation-api-2.1.1.jar` | injection de dépendances |
| `guava-32.0.0-jre.jar`, `failureaccess-1.0.1.jar`, `jsr305-3.0.2.jar`, `checker-qual-3.33.0.jar`, `error_prone_annotations-2.18.0.jar`, `j2objc-annotations-2.8.jar` | Guava (dont le cache) et ses annotations |
| `jackson-databind-2.15.2.jar`, `jackson-core-2.15.2.jar` | lecture du JSON de l'API |
| `hibernate-validator-8.0.0.Final.jar`, `jakarta.validation-api-3.0.2.jar`, `jboss-logging-3.4.1.Final.jar`, `classmate-1.5.1.jar` | validation de la configuration |

Aucun fichier `LICENSE` ni `NOTICE` n'est fourni avec cette version.

Vérification de l'intégrité (à faire avant toute installation) :

```bash
sha256sum passeport-group-provider-3.0.22.zip
# comparer à l'empreinte communiquée avec le livrable
unzip -l passeport-group-provider-3.0.22.zip
```

Le zip doit contenir `passeport-group-provider-3.0.22.jar` et `passeport-group-provider-3.0.22-services.jar`, et aucune entrée `.zip`.

---

## 4. Installation sur SEP (Helm / OpenShift)

Le plugin doit être installé sur le **coordinator et sur tous les workers** : la fonction SQL doit être connue de tous les nœuds, même si la configuration du group provider n'est lue que par le coordinator.

Le dossier du plugin doit se trouver sous `/usr/lib/starburst/plugin/` (un sous-dossier par plugin ; tous les jars du dossier sont chargés). Le chargement a lieu au démarrage : un changement de plugin impose un redémarrage des pods.

### Option A : initContainer qui télécharge le zip depuis le dépôt d'artefacts interne

L'initContainer télécharge le zip depuis le dépôt d'artefacts de la CNAM, vérifie le SHA-256, décompresse dans un volume `emptyDir`, qui est monté dans le dossier `plugin/` du conteneur SEP.

Extrait de values générique, à adapter à la version du chart (les clés `initContainers`, `additionalVolumes`, `env` et `envFrom` peuvent différer) :

```yaml
coordinator:
  initContainers:
    - name: init-plugin-passeport
      # Image d'outils disposant de wget (ou curl), sha256sum et unzip, fixée par digest.
      # À choisir par la CNAM, selon son catalogue d'images autorisées.
      image: <registre-interne>/<image-outils>@sha256:<digest-a-choisir>
      command: ["/bin/sh", "-c"]
      args:
        - |
          set -eu
          wget -O /tmp/passeport.zip "https://<depot-artefacts-interne>/passeport/passeport-group-provider-3.0.22.zip"
          echo "<sha256-communique-avec-le-livrable>  /tmp/passeport.zip" | sha256sum -c -
          unzip -o -j /tmp/passeport.zip -d /usr/lib/starburst/plugin/passeport
      securityContext:
        runAsNonRoot: true
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]

worker:
  initContainers:
    - name: init-plugin-passeport
      image: <registre-interne>/<image-outils>@sha256:<digest-a-choisir>
      command: ["/bin/sh", "-c"]
      args:
        - |
          set -eu
          wget -O /tmp/passeport.zip "https://<depot-artefacts-interne>/passeport/passeport-group-provider-3.0.22.zip"
          echo "<sha256-communique-avec-le-livrable>  /tmp/passeport.zip" | sha256sum -c -
          unzip -o -j /tmp/passeport.zip -d /usr/lib/starburst/plugin/passeport
      securityContext:
        runAsNonRoot: true
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]

# Volume partagé entre l'initContainer et le conteneur SEP : le chemin donné est le dossier
# du plugin. Le chart doit le monter dans les initContainers et dans le conteneur principal.
additionalVolumes:
  - path: /usr/lib/starburst/plugin/passeport
    volume:
      emptyDir: {}
```

Points d'attention :

- `unzip -j` met les fichiers à plat dans le dossier du plugin (le zip contient un dossier racine unique).
- Si la vérification du SHA-256 échoue, l'initContainer s'arrête en erreur et le pod ne démarre pas : c'est le comportement voulu.
- Sur OpenShift, les pods s'exécutent avec un UID attribué par la plateforme : l'`emptyDir` doit être inscriptible par ce UID (`fsGroup` ou politique SCC en vigueur) et l'image de l'initContainer ne doit pas exiger root.
- Le `/tmp` de l'initContainer doit être inscriptible ; sinon monter un second `emptyDir`.

### Option B : image dérivée

Construire une image à partir de l'image SEP autorisée par la CNAM, avec le plugin décompressé dans `plugin/`, puis l'utiliser pour le coordinator et les workers. Exemple :

```dockerfile
FROM <registre-interne>/<image-sep-autorisee>@sha256:<digest>
# le contexte de build contient le dossier issu de la décompression du zip vérifié
COPY --chown=<uid-sep>:0 passeport-group-provider-3.0.22/ /usr/lib/starburst/plugin/passeport/
```

Vérifier le SHA-256 du zip avant de le décompresser dans le contexte de build. Pour changer de version du plugin, reconstruire l'image.

---

## 5. Configuration de `group-provider.properties`

Fichier `etc/group-provider.properties` du **coordinator** (par le mécanisme du chart qui dépose des fichiers dans `etc/`). Le group provider est lu au démarrage : un changement impose un redémarrage du coordinator.

### Propriétés

| Propriété | Obligatoire | Défaut | Description |
|---|---|---|---|
| `group-provider.name` | oui | aucun | Doit valoir `passeport` (propriété SEP, pas du plugin). |
| `passeport.api-url` | **oui** | aucun | URL de base de l'API, sans l'UPN ni le code application. Un `/` final est ignoré. Schéma `http` ou `https`, sans paramètre ni fragment. |
| `passeport.code-application` | **oui** | aucun | Code de l'application Passeport, ajouté à l'URL. |
| `passeport.trust-store-path` | non | aucun (truststore de la JVM) | Chemin du truststore (JKS ou PKCS12) dans le pod coordinator. |
| `passeport.trust-store-password` | non | aucun | Mot de passe du truststore. Valeur masquée dans les logs de démarrage. |
| `passeport.cache-ttl` | non | `15m` | Durée de vie d'une entrée du cache (par exemple `30s`, `5m`, `1h`). Minimum `1s`. |
| `passeport.cache-max-size` | non | `10000` | Nombre maximal d'utilisateurs en cache. Minimum `1`. |
| `group-provider.group-case` | non | `KEEP` | Propriété SEP (casse des noms de groupes : `KEEP`, `LOWER`, `UPPER`). Utile si les rôles BIAC ne respectent pas la casse des `code_pa`. |

Si `passeport.api-url` ou `passeport.code-application` est absente ou vide, le chargement du group provider échoue au démarrage avec un message de la forme :

```
Failed to initialize Passeport group provider: Missing required property 'passeport.api-url' (no default value): set it in group-provider.properties
```

Une propriété inconnue ou une valeur invalide (durée illisible, taille inférieure à 1) fait aussi échouer le démarrage.

Les propriétés `passeport.row-filter-mappings` et `passeport.service-account` sont acceptées mais concernent le composant de contrôle d'accès système, non utilisé ici : ne pas les renseigner.

### Exemple

```properties
group-provider.name=passeport
passeport.api-url=https://passeport.example.org/s1sem/habilitations
passeport.code-application=<code_application>
passeport.trust-store-path=/etc/starburst/tls/passeport-truststore.p12
passeport.trust-store-password=${ENV:PASSEPORT_TRUSTSTORE_PASSWORD}
passeport.cache-ttl=15m
```

### Mot de passe du truststore et secret Kubernetes

Le mot de passe ne doit pas figurer en clair dans les values Helm ni dans un ConfigMap. Le fournir par une variable d'environnement du pod coordinator alimentée par un secret Kubernetes (`secretKeyRef` ou `envFrom`), puis la référencer par `${ENV:PASSEPORT_TRUSTSTORE_PASSWORD}`.

Vérification de la prise en charge de `${ENV:...}` : le code amont de Trino (version 482) résout les références `${ENV:...}` dans `group-provider.properties` avant de passer la configuration au plugin. Cette résolution **n'a pas été vérifiée dans le code de SEP 482-e** : à confirmer lors de la recette. Si la référence n'est pas résolue, le plugin reçoit la chaîne littérale `${ENV:...}` comme mot de passe et le chargement du truststore échoue au démarrage. Dans ce cas, il faut une autre méthode d'injection du secret (par exemple un fichier de configuration monté depuis un secret Kubernetes) : le mot de passe ne doit pas être écrit en clair dans les values Helm.

---

## 6. Contrat de l'API attendu

### Requête

```
GET <passeport.api-url>/<upn>/<passeport.code-application>
Accept: application/json
```

- L'UPN et le code application sont encodés comme segments de chemin (pourcentage, UTF-8) : tout caractère autre que `A-Z a-z 0-9 - . _ ~ @` est encodé. Un UPN ne peut donc pas ajouter de segment, de paramètre ou de fragment à l'URL. Exemple : `user.test+tag@example.org` donne `user.test%2Btag@example.org`.
- Aucun en-tête d'authentification, aucun certificat client : le plugin n'envoie que `Accept`. **Si l'API exige une authentification, ce n'est pas géré dans cette version** (section 11).
- Les redirections ne sont pas suivies.

### Réponse

- Seul le code HTTP **200** est accepté. Tout autre code (y compris 404 ou une redirection) est une erreur.
- Le corps doit être un tableau JSON. Deux formes sont lues, par élément :

Forme imbriquée (format réel de l'API), lue en priorité :

```json
[{"app":{"code_app":"SY_APP","compl1":"%"},
  "listepa":[{"code_pa":"APP_ADMIN","perimetre":"TOUS"},
             {"code_pa":"APP_VIEWER"}]}]
```

Forme plate (forme simplifiée, toujours acceptée) :

```json
[{"code_pa":"APP_ADMIN","perimetre":"TOUS"},{"code_pa":"APP_VIEWER"}]
```

Un même élément peut porter les deux formes. Les autres champs (`compl1`, etc.) sont ignorés.

Règles de lecture :

- Chaque `code_pa` devient un groupe ; les doublons sont éliminés.
- `perimetre` est facultatif. Un `code_pa` sans périmètre donne un groupe sans périmètre.
- **Périmètre multiple** : une valeur comme `"011,717"` est découpée sur la virgule ; chaque valeur est débarrassée de ses espaces de bord, les valeurs vides sont ignorées. Le périmètre de l'utilisateur est l'ensemble des valeurs de tous ses profils, sans doublon, dans l'ordre d'apparition. Une valeur unique comme `TOUS` reste telle quelle. Un périmètre numérique JSON est accepté (converti en texte).
- **Filtre sur l'application** : si un élément porte `app.code_app` et qu'il diffère du `code_application` configuré (sans tenir compte de la casse ni des espaces de bord), l'élément est ignoré et un avertissement est journalisé. Un élément sans `app` (forme plate) n'est pas filtré.
- Un tableau vide `[]` est une réponse valide : l'utilisateur n'a aucun groupe et aucun périmètre.
- **Toute autre structure fait échouer la requête** : racine qui n'est pas un tableau, élément qui n'est pas un objet, élément sans `code_pa` ni `listepa`, `listepa` qui n'est pas un tableau, `code_pa` absent, vide ou non textuel, `perimetre` qui n'est ni texte ni nombre, JSON invalide.

---

## 7. BIAC : correspondance des groupes et fonction de périmètre

### Correspondance 1:1 entre groupe Passeport et rôle BIAC

SEP fait correspondre le nom de chaque groupe renvoyé par le group provider au rôle BIAC de **même nom** (la documentation Starburst indique que les noms affichés par `SELECT current_groups()` sont ceux qui sont mis en correspondance avec les rôles BIAC). Le principe retenu : un `code_pa` Passeport correspond à un seul rôle BIAC, et inversement.

Exemple (noms fictifs) :

| Groupe Passeport (`code_pa`) | Rôle BIAC | Privilèges portés par le rôle |
|---|---|---|
| `APP_AUDITOR` | `APP_AUDITOR` | `SELECT` sur `catalog.schema.table` ; `EXECUTE` sur `passeport_perimetre` ; row filter sur `catalog.schema.table` |
| `APP_VIEWER` | `APP_VIEWER` | `SELECT` sur une vue de `catalog.schema` |

Mise en oeuvre : créer dans BIAC les rôles portant exactement les noms des `code_pa`, puis leur accorder les privilèges (interface de BIAC ou API BIAC ; les privilèges de table peuvent aussi s'écrire en SQL, par exemple `GRANT SELECT ON catalog.schema.table TO ROLE "APP_AUDITOR"`). La façon exacte d'associer un groupe à un rôle dans votre version de SEP est décrite dans la documentation BIAC de cette version : **à confirmer lors de la recette**. Respecter la casse (ou utiliser `group-provider.group-case`).

### `GRANT EXECUTE` sur `passeport_perimetre()`

BIAC traite les fonctions personnalisées comme une entité de privilège (privilège `EXECUTE`). Pour que `passeport_perimetre()` fonctionne :

- le privilège `EXECUTE` doit être accordé **fonction par fonction** (jamais « toutes les fonctions ») à chaque rôle concerné ;
- il n'est **pas hérité** par l'imbrication de rôles : un rôle qui reçoit un autre rôle ne reçoit pas ses `EXECUTE` sur les fonctions. Accorder le privilège à chaque rôle qui en a besoin, y compris à un rôle qui n'est utilisé qu'à travers un row filter. Ce comportement est à confirmer par la recette ;
- sans grant, l'appel échoue avec `PERMISSION_DENIED` (comportement fermé).

L'octroi se fait par l'interface de BIAC (privilèges, catégorie fonctions) ou par son API ; la documentation Starburst ne donne pas de syntaxe SQL pour les privilèges sur fonctions. L'entité à désigner : fonction `passeport_perimetre`, dans le schéma `builtin` du catalogue `system` (à confirmer dans votre version de l'interface).

### Exemple de row filter

Table avec une colonne `caisse` :

```sql
contains(passeport_perimetre(), caisse)
```

Expression à renseigner dans le row filter BIAC de la table. Une alternative équivalente est `caisse = ANY (passeport_perimetre())`. L'utilisateur ne voit que les lignes dont la caisse figure dans son périmètre. Un périmètre vide (aucun `perimetre` renvoyé par l'API) donne aucune ligne, de même qu'une colonne `caisse` à `NULL`.

**Valeur `TOUS`** : le plugin ne l'interprète pas. Il renvoie `TOUS` comme n'importe quelle autre valeur. Avec l'expression ci-dessus, un utilisateur dont le périmètre est `["TOUS"]` ne verrait que les lignes dont la caisse vaut littéralement `TOUS`. La signification de `TOUS` (accès à toutes les caisses, ou autre) et l'écriture du filtre correspondante sont une **règle métier à définir par la CNAM** ; le plugin n'en impose aucune.

### Autres fonctions

`hash_user_salt`, `pseudonymize`, `current_user_biac_roles` : présentes, hors périmètre de ce document, inactives sans `GRANT EXECUTE`.

---

## 8. Comportement et exploitation

### Cache et TTL

- Le cache est en mémoire du coordinator, par utilisateur (clé : UPN). Il contient les groupes et le périmètre issus d'un seul appel API.
- Durée de vie : `passeport.cache-ttl` (15 minutes par défaut), comptée à partir du chargement de l'entrée. La taille maximale est `passeport.cache-max-size`.
- Plusieurs requêtes simultanées du même utilisateur n'entraînent qu'un seul appel à l'API.
- Le cache est vide après un redémarrage du coordinator.

### Délai de prise en compte d'un changement d'habilitation

Un ajout, un retrait ou un changement de périmètre côté Passeport est visible au plus tard à l'expiration du TTL (15 minutes par défaut) après le dernier chargement de l'utilisateur. Pour un délai plus court, réduire `passeport.cache-ttl` (au prix de plus d'appels à l'API), ou redémarrer le coordinator. Il n'existe pas de purge à la demande dans cette version.

### Fail closed

- Une erreur de l'API (code différent de 200, délai dépassé, API injoignable, TLS en échec, réponse de structure inattendue) **fait échouer la requête** de l'utilisateur concerné : il ne reçoit ni groupe ni périmètre « par défaut ». Les autres utilisateurs, dont le cache est valide, ne sont pas affectés.
- L'échec n'est **pas mis en cache** : la requête suivante réinterroge l'API.
- Une configuration obligatoire manquante, un truststore illisible ou une valeur invalide empêchent le démarrage du group provider.
- Si l'instance du group provider n'est pas disponible là où `passeport_perimetre()` est évaluée, la fonction renvoie un tableau vide (aucune ligne n'est visible). Voir le point d'attention de la section 9.

### Logs

- Les logs du plugin utilisent le logger `io.starburst.cnam.passeport`.
- Niveaux : `WARNING` pour un code HTTP inattendu ou un élément ignoré (autre application), `SEVERE` (avec trace) pour un échec d'appel, `DEBUG` pour le détail d'un appel (« Fetching Passeport data for user »).
- Activer le niveau DEBUG dans `etc/log.properties` du coordinator :

```properties
io.starburst.cnam.passeport=DEBUG
```

  Ne laisser DEBUG actif que le temps du diagnostic.
- Au démarrage, les valeurs des propriétés `passeport.*` sont écrites dans les logs ; le mot de passe du truststore y est masqué.

### Données personnelles

L'**UPN de l'utilisateur apparaît dans les logs** (niveaux DEBUG, WARNING et SEVERE) et dans les messages d'erreur renvoyés au client lorsque la récupération échoue. Le périmètre et les groupes ne sont pas journalisés. Appliquer à ces logs la politique de rétention et d'accès en vigueur pour les données personnelles.

---

## 9. Recette

### Chargement du plugin

Dans les logs de démarrage du coordinator, vérifier :

1. le chargement du plugin depuis `plugin/passeport` et l'enregistrement du group provider `passeport` (libellés exacts selon la version de SEP) ;
2. le chargement du group provider `passeport` sans erreur ;
3. l'affichage des propriétés `passeport.*` avec les valeurs attendues ;
4. l'absence d'erreur `UnsupportedClassVersionError`, `NoSuchMethodError` ou `NoClassDefFoundError`.

Sur un worker : l'absence d'erreur au chargement du plugin.

### Contrôles de base (connecté avec l'utilisateur de test, via le SSO)

```sql
SHOW FUNCTIONS LIKE 'passeport%';      -- passeport_perimetre doit apparaître
SELECT current_groups();               -- groupes de l'utilisateur courant (fonction à vérifier dans votre version de SEP)
SELECT passeport_perimetre();          -- périmètre de l'utilisateur courant
```

Si `current_groups()` n'est pas disponible, utiliser l'interface de BIAC ou le niveau DEBUG des logs pour observer les groupes. `passeport_perimetre()` exige le `GRANT EXECUTE` de la section 7 : sans lui, `PERMISSION_DENIED` est le résultat attendu.

Point d'attention à vérifier : l'appel `passeport_perimetre()` doit renvoyer le périmètre attendu, et non un tableau vide, depuis une requête qui lit une table filtrée (row filter). Un tableau vide inattendu, alors que `SELECT passeport_perimetre()` seul est correct, indique que la fonction est évaluée sur un nœud sans group provider configuré (worker). À signaler dans ce cas.

### T01 : affectation simple

1. Utilisateur de test sans aucun groupe Passeport : `SELECT current_groups()` ne renvoie aucun groupe ; l'accès aux objets protégés est refusé.
2. Affecter l'utilisateur à **un seul** profil Passeport correspondant à un rôle BIAC. Attendre la propagation (au plus le TTL, section 8), ou ouvrir une nouvelle session après expiration.
3. Résultat attendu : le groupe unique apparaît ; `SELECT` réussit sur les objets accordés au rôle ; l'accès à tout objet hors du rôle est refusé ; `passeport_perimetre()` renvoie le périmètre du profil.

### T02 : cas combinatoire

1. Reproduire pour un utilisateur de test l'affectation multi-profils fournie par la CNAM (plusieurs profils, plusieurs domaines, plusieurs caisses, y compris un `perimetre` multiple du type `"011,717"`).
2. Résultat attendu : les groupes sont l'ensemble des profils ; `passeport_perimetre()` renvoie l'union des valeurs sans doublon, dans l'ordre de la réponse de l'API (une valeur `"011,717"` donne deux éléments) ; les lignes visibles correspondent exactement au périmètre attendu par les équipes métier, sans droit en excès.

### T03 : correspondance 1:1

1. Lister les profils Passeport du périmètre du test et les rôles BIAC correspondants.
2. Résultat attendu : chaque `code_pa` correspond à exactement un rôle de même nom ; chaque rôle est visé par un seul `code_pa` ; aucune exception, ou les exceptions sont documentées. Un `code_pa` sans rôle BIAC homonyme ne donne aucun privilège.

### Tests d'erreur (facultatifs, environnement de test)

- API coupée : la requête d'un utilisateur dont le cache est vide échoue ; elle réussit de nouveau dès que l'API revient, sans redémarrage.
- Propriété obligatoire retirée : le coordinator ne démarre pas, avec le message de la section 5.

---

## 10. Retour arrière

1. Restaurer la version précédente du plugin : revenir à l'archive précédente (option A : ancienne URL et ancien SHA-256 dans les values ; option B : ancienne image).
2. Restaurer le fichier `group-provider.properties` précédent si les propriétés ont changé (les propriétés `passeport.cache-ttl` et `passeport.cache-max-size` sont nouvelles et ne sont pas reconnues par une version antérieure à 3.0.22 : les retirer). Attention : les versions antérieures disposaient de valeurs par défaut pour `passeport.api-url` et `passeport.code-application`.
3. Redémarrer le coordinator puis les workers.
4. Rejouer les contrôles de base de la section 9.

Pour désactiver seulement le group provider, retirer `etc/group-provider.properties` du coordinator et le redémarrer : plus aucun groupe n'est fourni par Passeport, et les rôles BIAC liés à ces groupes ne s'appliquent plus. Les `GRANT EXECUTE` accordés dans BIAC peuvent rester en place.

---

## 11. Points à confirmer par la CNAM

1. **Format exact du périmètre** : confirmer le séparateur (la virgule est gérée), la signification de `TOUS` et l'éventuelle existence d'autres valeurs spéciales ; définir la règle métier correspondante pour les row filters.
2. **`code_application`** du projet, et sa casse exacte telle que renvoyée par l'API dans `app.code_app`.
3. **Attribut UPN** : confirmer que le nom d'utilisateur vu par SEP après le SSO est l'UPN attendu par l'API, et son format exact (casse, domaine).
4. **Authentification de l'API** : confirmer qu'aucun en-tête, jeton ou certificat client n'est requis (le plugin n'en envoie pas).
5. **Comportement de l'API** : réponse pour un utilisateur inconnu ou sans profil (`200` avec `[]` attendu ; un `404` ferait échouer la requête), éventuelle limitation de débit.
6. **TTL souhaité** : délai maximal acceptable de prise en compte d'un changement d'habilitation (défaut 15 minutes).
7. **Versions SEP et Java** de l'environnement cible, et validation sur la 480-e le cas échéant.
8. **Injection du secret** du truststore par `${ENV:...}` : confirmation sur la version de SEP cible (section 5).
9. **Association groupe vers rôle BIAC** et octroi du `EXECUTE` sur fonction : procédure exacte dans la version de SEP cible (section 7).
