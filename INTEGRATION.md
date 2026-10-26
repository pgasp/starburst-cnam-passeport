# Plugin Passeport pour Starburst Enterprise : mode opératoire d'intégration

Version du plugin : 3.0.24. Document destiné aux équipes techniques chargées de l'installation, de la configuration et de la recette.

Ce document couvre le group provider, les fonctions SQL de périmètre (modèle de profils) et la purge du cache, pour les tests T01 à T03 et T07.

---

## 1. Objet et périmètre

### Ce que fait le plugin

- **Group provider `passeport`** : à chaque requête SQL, SEP demande au plugin les groupes de l'utilisateur. Le plugin interroge l'API Passeport avec l'UPN de l'utilisateur et le `code_application` configuré, et renvoie les codes profil (`code_pa`) comme groupes. Dans BIAC, des rôles sont assignés à ces groupes (section 7).
- **Fonction SQL `passeport_perimetre()`** : renvoie, sous forme de `array(varchar)`, le périmètre (par exemple les caisses) **du profil actif** de l'utilisateur, c'est-à-dire du seul groupe Passeport dont le nom est égal au rôle BIAC actif (section 7). Elle sert dans un row filter BIAC. Une surcharge `passeport_perimetre(code_pa)` sert aux row filters écrits par rôle.
- **Fonction SQL `passeport_cache_invalidate()`** : invalide l'entrée de cache de l'utilisateur courant, et d'aucun autre (section 7).
- Les groupes et le périmètre de chaque groupe sont gardés en mémoire (cache) pendant une durée configurable (15 minutes par défaut).
- En cas d'erreur de l'API, la requête échoue (fail closed, voir section 8).

### Ce que le plugin ne couvre pas encore

- Le **chiffrement réversible** : prévu dans une version ultérieure.
- La **purge globale du cache** : volontairement absente, pour qu'aucun utilisateur ne puisse forcer un rafraîchissement massif de l'API. Seule la purge de l'entrée de l'utilisateur courant existe (section 7).

### Autres fonctions présentes dans le plugin

`hash_user_salt` et `pseudonymize` sont présentes mais hors périmètre de ce document. `current_user_biac_roles()` (rôles actifs de la session) sert au diagnostic de la section 9. Ces fonctions sont inactives tant qu'aucun `GRANT EXECUTE` ne les concerne (section 7).

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
- Ouvrir le flux du pod coordinator vers l'hôte et le port de l'API (NetworkPolicy, pare-feu, proxy éventuel). Le plugin ne configure pas de proxy : un éventuel proxy d'entreprise ne serait pris en compte que par les propriétés système standard de la JVM (`https.proxyHost`, `https.proxyPort`, `http.nonProxyHosts`), à fournir dans les options Java du coordinator. Le plus simple est un accès direct.
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

**`passeport.code-application` doit être égal à la valeur `app.code_app` renvoyée par l'API, quelle que soit la casse** (les espaces de bord sont aussi ignorés). Sinon, tous les éléments de la réponse sont ignorés et l'utilisateur n'a aucun groupe (voir section 8, Logs).

### UPN fourni par le SSO

Le plugin interroge l'API avec l'identifiant de l'utilisateur tel que le voit SEP après authentification. Cet identifiant doit être l'**UPN** attendu par l'API Passeport. Si le SSO fournit un autre attribut (adresse e-mail, identifiant court), l'API ne trouvera pas l'utilisateur. Vérifier que l'attribut utilisé comme nom d'utilisateur par SEP est bien l'UPN (section 11).

---

## 3. Contenu et vérification du livrable

Livrable : `passeport-group-provider-3.0.24.zip` **ou** `passeport-group-provider-3.0.24.tar.gz` (même contenu, mêmes jars, même dossier racine ; le tar.gz sert aux images d'init qui n'ont que `curl` et `tar`), chacun accompagné de son empreinte SHA-256 communiquée séparément.

Contenu : un dossier unique `passeport-group-provider-3.0.24/` contenant

| Fichier | Rôle |
|---|---|
| `passeport-group-provider-3.0.24.jar` | code du plugin |
| `passeport-group-provider-3.0.24-services.jar` | descripteur de chargement du plugin |
| `INTEGRATION.md` | ce document |
| `bootstrap-234.jar`, `configuration-234.jar`, `log-234.jar`, `units-1.0.jar` | bibliothèques de configuration (airlift) |
| `guice-7.0.0.jar`, `aopalliance-1.0.jar`, `jakarta.inject-api-2.0.1.jar`, `jakarta.annotation-api-2.1.1.jar` | injection de dépendances |
| `guava-32.0.0-jre.jar`, `failureaccess-1.0.1.jar`, `jsr305-3.0.2.jar`, `checker-qual-3.33.0.jar`, `error_prone_annotations-2.18.0.jar`, `j2objc-annotations-2.8.jar` | Guava (dont le cache) et ses annotations |
| `jackson-databind-2.15.2.jar`, `jackson-core-2.15.2.jar` | lecture du JSON de l'API |
| `hibernate-validator-8.0.0.Final.jar`, `jakarta.validation-api-3.0.2.jar`, `jboss-logging-3.4.1.Final.jar`, `classmate-1.5.1.jar` | validation de la configuration |

Aucun fichier `LICENSE` ni `NOTICE` n'est fourni avec cette version.

Vérification de l'intégrité (à faire avant toute installation) :

```bash
sha256sum passeport-group-provider-3.0.24.zip      # ou le .tar.gz
# comparer à l'empreinte communiquée avec le livrable
unzip -l passeport-group-provider-3.0.24.zip       # ou : tar tzf passeport-group-provider-3.0.24.tar.gz
```

L'archive doit contenir `passeport-group-provider-3.0.24.jar` et `passeport-group-provider-3.0.24-services.jar`, et aucune entrée `.zip`.

---

## 4. Installation sur SEP (Helm / OpenShift)

Le plugin doit être installé sur le **coordinator et sur tous les workers** : la fonction SQL doit être connue de tous les nœuds, même si la configuration du group provider n'est lue que par le coordinator.

Le dossier du plugin doit se trouver sous `/usr/lib/starburst/plugin/` (un sous-dossier par plugin ; tous les jars du dossier sont chargés). Le chargement a lieu au démarrage : un changement de plugin impose un redémarrage des pods.

### Option A : initContainer qui télécharge l'archive depuis le dépôt d'artefacts interne

L'initContainer télécharge le zip depuis le dépôt d'artefacts de la CNAM, vérifie le SHA-256 et décompresse dans un volume `emptyDir` nommé, monté dans l'initContainer. Le conteneur SEP monte le même volume, et son script de démarrage copie le dossier du plugin dans `/usr/lib/starburst/plugin/` avant de lancer SEP.

Extrait de values générique. **Les clés exactes (`initContainers`, `additionalVolumes`, `initFile`, `volumeMounts`) dépendent de la version du chart** : à adapter.

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
          mkdir -p /mnt/plugin-staging
          wget -O /mnt/plugin-staging/passeport.zip "https://<depot-artefacts-interne>/passeport/passeport-group-provider-3.0.24.zip"
          echo "<sha256-communique-avec-le-livrable>  /mnt/plugin-staging/passeport.zip" | sha256sum -c -
          rm -rf /mnt/plugin-staging/passeport
          mkdir -p /mnt/plugin-staging/passeport
          unzip -o /mnt/plugin-staging/passeport.zip -d /mnt/plugin-staging/passeport/
      # Si le chart ne monte pas automatiquement les volumes déclarés dans additionalVolumes
      # dans les initContainers, déclarer le montage explicitement :
      # volumeMounts:
      #   - name: plugin-staging
      #     mountPath: /mnt/plugin-staging
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
          mkdir -p /mnt/plugin-staging
          wget -O /mnt/plugin-staging/passeport.zip "https://<depot-artefacts-interne>/passeport/passeport-group-provider-3.0.24.zip"
          echo "<sha256-communique-avec-le-livrable>  /mnt/plugin-staging/passeport.zip" | sha256sum -c -
          rm -rf /mnt/plugin-staging/passeport
          mkdir -p /mnt/plugin-staging/passeport
          unzip -o /mnt/plugin-staging/passeport.zip -d /mnt/plugin-staging/passeport/
      securityContext:
        runAsNonRoot: true
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]

# emptyDir partagé entre les initContainers et le conteneur SEP (coordinator et workers).
additionalVolumes:
  - path: /mnt/plugin-staging
    volume:
      emptyDir: {}

# Script de démarrage du conteneur SEP (coordinator et workers) : copie du dossier du plugin
# dans plugin/, puis lancement de SEP. Adapter à la façon dont votre chart permet de remplacer
# la commande de démarrage (par exemple initFile).
initFile: |
  #!/bin/bash
  set -eu
  src=/mnt/plugin-staging/passeport
  # le zip contient un dossier racine unique : on copie son contenu à plat dans le dossier du plugin
  sub="$(ls -1A "$src" | head -1)"
  if [ "$(ls -1A "$src" | wc -l)" = "1" ] && [ -d "$src/$sub" ]; then src="$src/$sub"; fi
  rm -rf /usr/lib/starburst/plugin/passeport
  mkdir -p /usr/lib/starburst/plugin/passeport
  cp -r "$src"/. /usr/lib/starburst/plugin/passeport/
  exec /usr/lib/starburst/bin/run-starburst
```

Variante avec le tar.gz, pour une image d'outils qui n'a ni `wget` ni `unzip` (seulement `curl` et `tar`) : remplacer les lignes de téléchargement et de décompression de l'initContainer par

```sh
curl -fsS --cacert <ca-interne.crt> -o /mnt/plugin-staging/passeport.tar.gz "https://<depot-artefacts-interne>/passeport/passeport-group-provider-3.0.24.tar.gz"
echo "<sha256-du-tar-gz>  /mnt/plugin-staging/passeport.tar.gz" | sha256sum -c -
rm -rf /mnt/plugin-staging/passeport
mkdir -p /mnt/plugin-staging/passeport
tar xzf /mnt/plugin-staging/passeport.tar.gz -C /mnt/plugin-staging/passeport/
```

Ne pas désactiver la vérification du certificat (`curl -k`) ni l'empreinte SHA-256 : fournir l'autorité de certification interne (`--cacert`) et garder `sha256sum -c`. L'archive a un dossier racine unique, comme le zip : le script de démarrage ci-dessous s'applique tel quel.

Points d'attention :

- Dans cet extrait, l'initContainer et le conteneur SEP échangent uniquement par l'`emptyDir` : c'est le script de démarrage qui place les fichiers dans `/usr/lib/starburst/plugin/passeport`. Une variante consiste à monter directement l'`emptyDir` dans ce dossier, si le chart le permet.
- Si la vérification du SHA-256 échoue, l'initContainer s'arrête en erreur et le pod ne démarre pas : c'est le comportement voulu.
- Si le chart propose déjà un `initFile` ou une commande de démarrage personnalisée, y insérer la copie plutôt que de le remplacer, et conserver l'appel final à `run-starburst`.
- Sur OpenShift, les pods s'exécutent avec un UID attribué par la plateforme : l'`emptyDir` doit être inscriptible par ce UID (`fsGroup` ou politique SCC en vigueur) et l'image de l'initContainer ne doit pas exiger root.

### Option B : image dérivée

Construire une image à partir de l'image SEP autorisée par la CNAM, avec le plugin décompressé dans `plugin/`, puis l'utiliser pour le coordinator et les workers. Exemple :

```dockerfile
FROM <registre-interne>/<image-sep-autorisee>@sha256:<digest>
# le contexte de build contient le dossier issu de la décompression du zip vérifié
COPY --chown=<uid-sep>:0 passeport-group-provider-3.0.24/ /usr/lib/starburst/plugin/passeport/
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
| `passeport.code-application` | **oui** | aucun | Code de l'application Passeport, ajouté à l'URL. Doit être égal à `app.code_app` renvoyé par l'API (casse indifférente). |
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
passeport.api-url=https://passeport.example.org/api/habilitations
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
- Le corps doit être un tableau JSON. Les blancs de bord et un éventuel BOM sont ignorés. Un corps **entouré d'apostrophes littérales** (`'[{"app":...}]'`, format réellement renvoyé par l'API Passeport) est accepté : si le corps commence **et** finit par `'`, cette unique paire est retirée, une seule fois, avant la lecture ; une apostrophe isolée ou interne n'est jamais tolérée (le parseur reste strict). Un corps JSON **double-encodé** (une chaîne JSON qui contient le tableau, par exemple `"[{\"app\":...}]"`) est aussi accepté : la chaîne est décodée une seule fois, et son contenu doit alors être un tableau. Deux formes sont lues, par élément :

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
- `perimetre` est facultatif. Un `code_pa` sans périmètre (absent, vide ou `null`) donne un groupe au périmètre vide, qui signifie **aucune caisse** (section 7), jamais « toutes ».
- **Périmètre multiple** : une valeur comme `"011,717"` est découpée sur la virgule ; chaque valeur est débarrassée de ses espaces de bord, les valeurs vides sont ignorées. Le périmètre est gardé **par profil** (par `code_pa`), dans l'ordre d'apparition ; les périmètres d'un même `code_pa` répété dans la réponse sont fusionnés, sans doublon. Depuis la 3.0.24, `passeport_perimetre()` ne renvoie plus la réunion des périmètres de tous les profils, mais celui du profil actif (section 7). Une valeur unique comme `TOUS` reste telle quelle. Un périmètre numérique JSON est accepté (converti en texte).
- **Filtre sur l'application** : si un élément porte `app.code_app` et qu'il diffère du `code_application` configuré (sans tenir compte de la casse ni des espaces de bord), l'élément est ignoré et un avertissement est journalisé. Un élément sans `app` (forme plate) n'est pas filtré.
- Un tableau vide `[]` est une réponse valide : l'utilisateur n'a aucun groupe et aucun périmètre.
- Un avertissement (`WARNING`) est journalisé lorsque **tous** les éléments d'un tableau non vide sont ignorés (autre application), ou lorsqu'aucun groupe n'est trouvé alors que le tableau n'est pas vide.
- **Toute autre structure fait échouer la requête** : racine qui n'est pas un tableau (le message indique le type du nœud reçu à la racine, par exemple `STRING` ou `OBJECT`, et pour un objet la liste de ses clés de premier niveau, jamais le contenu ; une chaîne JSON dont le contenu n'est pas un tableau est rejetée de la même façon), élément qui n'est pas un objet, élément sans `code_pa` ni `listepa`, `listepa` qui n'est pas un tableau, `code_pa` absent, vide ou non textuel, `perimetre` qui n'est ni texte ni nombre, JSON invalide.

---

## 7. BIAC : profils, correspondance des groupes et fonctions SQL

### Modèle de profils (depuis la 3.0.24)

- **Le nom du rôle BIAC est égal au nom du groupe Passeport (`code_pa`).** Un rôle par profil. La correspondance se fait sur le nom, sans tenir compte de la casse ni des espaces de bord.
- **Un seul profil actif à la fois.** `passeport_perimetre()` renvoie le périmètre du **seul** groupe dont le nom est égal au rôle actif. Les rôles `public` et `sysadmin` ne comptent pas dans le décompte des rôles actifs.
- Le résultat est un **tableau vide** (aucune ligne visible dans un row filter) dans tous les autres cas : plusieurs rôles actifs, aucun rôle actif, rôle qui ne correspond à aucun groupe Passeport de l'utilisateur, ou deux groupes de l'utilisateur qui ne diffèrent que par la casse (ambiguïté, avertissement dans les logs).
- Un groupe dont le périmètre est vide (aucun `perimetre` dans la réponse de l'API) donne aussi un tableau vide. Cela signifie **aucune caisse**, et **non** « toutes les caisses » : pour « toutes », il faut une valeur explicite et une règle métier (voir `TOUS` plus bas).
- L'objectif est d'obliger l'utilisateur à choisir son profil, pour qu'il ne puisse pas joindre des données de caisses relevant de profils différents. Avant la 3.0.24, la fonction renvoyait la réunion des périmètres de tous les groupes (voir section 10 : changement de comportement).

Conséquences pour les utilisateurs :

- Il faut **choisir son profil** : `SET ROLE <profil>`. Tant qu'aucun profil unique n'est actif, `passeport_perimetre()` renvoie un tableau vide et les tables filtrées apparaissent vides.
- Sur CLI et JDBC, **sans `SET ROLE`**, on s'attend à ce que tous les rôles soient actifs ou qu'aucun ne le soit : dans les deux cas, aucun périmètre. La web UI n'active qu'un rôle à la fois. Ce comportement est **à confirmer en recette** (voir l'hypothèse ci-dessous), ainsi que le comportement des outils clients (DBeaver, Power BI, etc.), qui peuvent ouvrir plusieurs sessions ou ne pas permettre `SET ROLE`.
- Pour changer de profil : un nouveau `SET ROLE`. Le périmètre suit immédiatement le rôle actif (le cache ne retient que les groupes et les périmètres de l'utilisateur, pas le rôle actif).

### Hypothèse sur les rôles actifs

Le plugin lit les rôles actifs de la session dans l'identité (`getEnabledSystemRoles()`, visible en SQL par `current_user_biac_roles()`). Seule mesure disponible : sous `SET ROLE sysadmin`, la valeur est `[sysadmin]`. Ce que renvoie une session **sans `SET ROLE`** et sous **`SET ROLE ALL`** n'a pas pu être mesuré. La logique est écrite pour ne dépendre que de ceci :

1. `public` et `sysadmin` sont retirés de la liste ;
2. s'il reste **exactement un** rôle et qu'il correspond (sans tenir compte de la casse ni des espaces de bord) à un groupe de l'utilisateur, le périmètre de ce groupe est renvoyé ;
3. dans tous les autres cas, un tableau vide.

Si une session sans `SET ROLE` n'exposait qu'un seul rôle (par exemple si les rôles ne sont activés qu'à la demande), ce rôle serait pris pour le profil actif. La « mesure des rôles actifs » de la section 9 lève l'incertitude.

### Correspondance 1:1 entre groupe Passeport et rôle BIAC

SEP n'associe **pas** automatiquement un groupe au rôle BIAC de même nom : dans BIAC, un rôle est **assigné explicitement à un groupe**. Les groupes renvoyés par le group provider (les `code_pa`) sont les noms de groupes auxquels on assigne les rôles (la documentation Starburst indique que `SELECT current_groups()` montre les noms de groupes qui sont mis en correspondance avec les rôles BIAC).

Le principe retenu est un `code_pa` pour un seul rôle, et inversement. **Depuis la 3.0.24, donner au rôle le même nom que le `code_pa` n'est plus une simple convention : `passeport_perimetre()` ne renvoie un périmètre que si le rôle actif porte le nom du groupe.**

Procédure, pour chaque `code_pa` :

1. créer le rôle BIAC de même nom que le `code_pa` ;
2. assigner ce rôle au groupe portant le nom du `code_pa` : interface BIAC (Roles, assignation à un groupe) ou API BIAC ;
3. accorder au rôle ses privilèges (tables, vues, `EXECUTE` sur les fonctions, row filters).

Exemple (noms fictifs) :

| Groupe Passeport (`code_pa`) | Rôle BIAC | Privilèges portés par le rôle |
|---|---|---|
| `APP_RESEAU` | `APP_RESEAU` (assigné au groupe `APP_RESEAU`) | `SELECT` sur `catalog.schema.table` ; `EXECUTE` sur `passeport_perimetre` ; row filter sur `catalog.schema.table` |
| `APP_LOC` | `APP_LOC` (assigné au groupe `APP_LOC`) | idem |

Les privilèges de table peuvent aussi s'écrire en SQL, par exemple `GRANT SELECT ON catalog.schema.table TO ROLE "APP_LOC"`. L'assignation d'un rôle à un groupe se fait dans l'interface ou l'API BIAC ; sa forme exacte dans votre version de SEP est décrite dans la documentation BIAC de cette version : **à confirmer lors de la recette**. Respecter la casse des noms de groupes (ou utiliser `group-provider.group-case`).

Un groupe auquel aucun rôle n'est assigné ne donne aucun privilège.

### `GRANT EXECUTE` sur les fonctions

BIAC traite les fonctions personnalisées comme une entité de privilège (privilège `EXECUTE`). Pour que `passeport_perimetre()` (avec ou sans argument) fonctionne :

- le privilège `EXECUTE` doit être accordé **fonction par fonction** (jamais « toutes les fonctions ») ;
- il n'est **pas hérité** par l'imbrication de rôles : un rôle qui reçoit un autre rôle ne reçoit pas ses `EXECUTE` sur les fonctions. Comme **un seul rôle de profil est actif à la fois**, il faut l'accorder à **chacun des rôles de profil** (`APP_RESEAU`, `APP_LOC`, etc.), y compris à un rôle qui n'est utilisé qu'à travers un row filter. Ce comportement est à confirmer par la recette ;
- sans grant, l'appel échoue avec `PERMISSION_DENIED` (comportement fermé).

L'octroi se fait par l'interface de BIAC (privilèges, catégorie fonctions) ou par son API ; la documentation Starburst ne donne pas de syntaxe SQL pour les privilèges sur fonctions. L'entité à désigner : fonction `passeport_perimetre`, dans le schéma `builtin` du catalogue `system` (à confirmer dans votre version de l'interface, notamment si la surcharge `passeport_perimetre(varchar)` est une entité distincte de la forme sans argument : dans ce cas, accorder les deux).

| Fonction | Qui doit avoir `EXECUTE` |
|---|---|
| `passeport_perimetre` (sans argument et avec argument) | chaque rôle de profil |
| `passeport_cache_invalidate` | seulement les rôles qui doivent pouvoir forcer le rafraîchissement de leur propre entrée (administrateurs, rôle de test) ; **pas** tous les profils |
| `current_user_biac_roles` | rôle de diagnostic ou de test |

### Exemples de row filter

Table avec une colonne `caisse`. Expression à renseigner dans le row filter BIAC de la table.

**Filtre unique pour tous les profils** : il suit le profil actif.

```sql
contains(passeport_perimetre(), caisse)
```

Une alternative équivalente est `caisse = ANY (passeport_perimetre())`. L'utilisateur ne voit que les lignes dont la caisse figure dans le périmètre de son profil actif. Un périmètre vide (profil sans caisse, plusieurs rôles actifs, aucun profil) donne aucune ligne, de même qu'une colonne `caisse` à `NULL`.

**Filtre écrit par rôle** : un filtre par rôle, chacun nommant son groupe.

```sql
contains(passeport_perimetre('APP_LOC'), caisse)
```

La forme avec argument renvoie le périmètre du groupe indiqué **seulement si** l'utilisateur appartient à ce groupe **et** que ce rôle est le profil actif (mêmes règles que ci-dessus : un seul rôle actif, `public` et `sysadmin` ignorés) ; sinon un tableau vide. Elle ne peut donc jamais donner plus que la forme sans argument : elle ne renvoie jamais le périmètre d'un autre profil que celui du filtre, même si le filtre est appliqué par erreur à un autre rôle. Le nom du groupe est comparé sans tenir compte de la casse ni des espaces de bord.

**Valeur `TOUS`** : le plugin ne l'interprète pas. Il renvoie `TOUS` comme n'importe quelle autre valeur. Avec les expressions ci-dessus, un utilisateur dont le périmètre est `["TOUS"]` ne verrait que les lignes dont la caisse vaut littéralement `TOUS`. La signification de `TOUS` (accès à toutes les caisses, ou autre) et l'écriture du filtre correspondante sont une **règle métier à définir par la CNAM** ; le plugin n'en impose aucune.

### Purge du cache de l'utilisateur courant

```sql
SELECT passeport_cache_invalidate();
```

- Invalide l'entrée de cache de **l'utilisateur courant**, et d'aucun autre : la requête suivante de cet utilisateur réinterroge l'API Passeport (un seul appel). Il n'y a **pas** de purge globale, et la fonction n'accepte aucun argument : un utilisateur ne peut pas purger l'entrée d'un autre ni forcer un rafraîchissement massif.
- Résultat : `true` si l'invalidation a été faite sur le coordinator ; `false` si elle n'a pas pu l'être (la fonction a été évaluée sur un nœud sans group provider, ou le nom d'utilisateur est inexploitable). Un `false` signifie que **rien n'a été invalidé**.
- **Limite : le cache vit sur le coordinator.** La fonction est déterministe sans argument : l'optimiseur de Trino l'évalue à la planification, sur le coordinator, avant l'envoi aux workers. Il faut donc l'appeler **seule** (`SELECT passeport_cache_invalidate();`), pas combinée à une autre fonction dont l'ordre d'évaluation importerait. Si elle était malgré tout évaluée sur un worker, le cache n'existant pas là, elle répondrait `false` et journaliserait un avertissement sur ce worker. Avec plusieurs coordinators, seul celui qui planifie la requête est purgé.
- La purge ne concerne que le cache du plugin. Un éventuel cache des groupes côté BIAC n'est pas touché (à vérifier en recette).
- Droit : `EXECUTE` sur `passeport_cache_invalidate`, accordé **par BIAC** (comme ci-dessus, à chaque rôle concerné, sans héritage). À réserver aux rôles qui en ont besoin.
- Un utilisateur qui a `EXECUTE` peut provoquer, pour lui seul, un appel API par requête de purge : à garder pour les administrateurs et les tests.

### Autres fonctions

`hash_user_salt`, `pseudonymize`, `current_user_biac_roles` : `current_user_biac_roles()` ne sert qu'au diagnostic (section 9) ; les autres sont hors périmètre de ce document. Toutes sont inactives sans `GRANT EXECUTE`.

---

## 8. Comportement et exploitation

### Cache et TTL

- Le cache est en mémoire du coordinator, par utilisateur (clé : UPN). Il contient les groupes et le périmètre **de chaque groupe**, issus d'un seul appel API. Le rôle actif n'est pas mis en cache : il est lu dans la session à chaque appel de fonction.
- Durée de vie : `passeport.cache-ttl` (15 minutes par défaut), comptée à partir du chargement de l'entrée. La taille maximale est `passeport.cache-max-size`.
- Plusieurs requêtes simultanées du même utilisateur n'entraînent qu'un seul appel à l'API.
- Le cache est vide après un redémarrage du coordinator.

### Délai de prise en compte d'un changement d'habilitation

Un ajout, un retrait ou un changement de périmètre côté Passeport est visible au plus tard à l'expiration du TTL (15 minutes par défaut) après le dernier chargement de l'utilisateur. Pour un délai plus court : faire purger l'entrée de l'utilisateur concerné par `SELECT passeport_cache_invalidate();` exécuté par cet utilisateur (section 7, droit `EXECUTE` par BIAC), réduire `passeport.cache-ttl` (au prix de plus d'appels à l'API), ou redémarrer le coordinator. Il n'existe pas de purge globale.

### Fail closed

- Une erreur de l'API (code différent de 200, délai dépassé, API injoignable, TLS en échec, réponse de structure inattendue) **fait échouer la requête** de l'utilisateur concerné : il ne reçoit ni groupe ni périmètre « par défaut ». Les autres utilisateurs, dont le cache est valide, ne sont pas affectés.
- L'échec n'est **pas mis en cache** : la requête suivante réinterroge l'API.
- Une configuration obligatoire manquante, un truststore illisible ou une valeur invalide empêchent le démarrage du group provider.
- `passeport_perimetre()` renvoie un tableau vide (aucune ligne n'est visible) quand le profil actif n'est pas déterminé (plusieurs rôles actifs, aucun, aucun groupe de même nom, groupes ambigus), et aussi si l'instance du group provider n'est pas disponible là où elle est évaluée. Voir le point d'attention de la section 9.

### Logs

- Les logs du plugin utilisent le logger `io.starburst.cnam.passeport`.
- Niveaux : `WARNING` pour un code HTTP inattendu, un élément ignoré (autre application), tous les éléments ignorés ou aucun groupe trouvé dans un tableau non vide, deux groupes ambigus pour le rôle actif, une purge évaluée sur un nœud sans group provider, `SEVERE` (avec trace) pour un échec d'appel, `DEBUG` pour le détail d'un appel (« Fetching Passeport data for user ») et pour une purge de cache (« Passeport cache entry invalidated for user »).
- Activer le niveau DEBUG dans `etc/log.properties` du coordinator :

```properties
io.starburst.cnam.passeport=DEBUG
```

  Ne laisser DEBUG actif que le temps du diagnostic.
- Au démarrage, les valeurs des propriétés `passeport.*` sont écrites dans les logs ; le mot de passe du truststore y est masqué.

### Diagnostic d'une réponse inattendue

Message `Unexpected response format from Passeport API: expected a JSON array at the top level, received <TYPE>` : le corps HTTP est du JSON valide mais pas un tableau. Depuis le pod coordinator, afficher le premier caractère de la réponse réelle :

```bash
curl -s -H 'Accept: application/json' '<api-url>/<upn>/<code-application>' | head -c 1
```

| Premier caractère | Cas | Suite |
|---|---|---|
| `[` | Tableau attendu | Le problème est ailleurs : lire le message d'erreur complet. |
| `'` | Tableau entouré d'apostrophes | Cas géré depuis la version 3.0.23 (les apostrophes de bord sont retirées) ; une version antérieure répond « not valid JSON ». |
| `"` | JSON double-encodé | Accepté depuis la version 3.0.23 ; une version antérieure échoue. |
| `{` | Objet | Non accepté : le message liste les clés de premier niveau ; transmettre ces noms de champs à l'équipe du plugin. |

Si l'utilisateur n'obtient aucun groupe sans erreur, chercher dans les logs l'avertissement `All <n> Passeport element(s) were ignored` : `passeport.code-application` diffère de `app.code_app`.

### Données personnelles

L'**UPN de l'utilisateur apparaît dans les logs** (niveaux DEBUG, WARNING et SEVERE ; le DEBUG inclut les purges de cache) et dans les messages d'erreur renvoyés au client lorsque la récupération échoue. Le périmètre et les groupes ne sont pas journalisés. Appliquer à ces logs la politique de rétention et d'accès en vigueur pour les données personnelles.

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
SELECT passeport_perimetre();          -- périmètre du profil actif (vide tant qu'un seul profil n'est pas actif)
SELECT current_user_biac_roles();      -- rôles actifs de la session (diagnostic)
```

Si `current_groups()` n'est pas disponible, utiliser l'interface de BIAC ou le niveau DEBUG des logs pour observer les groupes. `passeport_perimetre()` exige le `GRANT EXECUTE` de la section 7 : sans lui, `PERMISSION_DENIED` est le résultat attendu. Depuis la 3.0.24, un tableau vide est aussi le résultat normal tant que l'utilisateur n'a pas choisi son profil (`SET ROLE <profil>`).

Point d'attention à vérifier : l'appel `passeport_perimetre()` doit renvoyer le périmètre attendu, et non un tableau vide, depuis une requête qui lit une table filtrée (row filter). Un tableau vide inattendu, alors que `SELECT passeport_perimetre()` seul est correct avec le même rôle actif, indique que la fonction est évaluée sur un nœud sans group provider configuré (worker). À signaler dans ce cas.

### Mesure des rôles actifs (à faire en premier : fixe l'hypothèse de la section 7)

Avec un utilisateur de test qui a au moins deux profils (rôles de même nom que ses groupes) et `EXECUTE` sur `current_user_biac_roles` et `passeport_perimetre`, **chaque cas dans une session neuve**, et relever le résultat :

```sql
-- cas 1 : sans SET ROLE
SELECT current_user_biac_roles(), passeport_perimetre();
-- cas 2 : tous les rôles
SET ROLE ALL;
SELECT current_user_biac_roles(), passeport_perimetre();
-- cas 3 : un profil
SET ROLE <profil>;
SELECT current_user_biac_roles(), passeport_perimetre();
```

Résultats attendus : cas 3 = `[<profil>]` (éventuellement avec `public`) et le périmètre de ce seul groupe ; cas 1 et cas 2 = `[]` pour le périmètre si plusieurs rôles (ou aucun) sont actifs. **Si le cas 1 ou le cas 2 ne renvoie qu'un seul rôle de profil, le périmètre de ce profil est renvoyé : relever la valeur exacte de `current_user_biac_roles()` dans chaque cas et la transmettre à l'équipe du plugin.**

### T01 : affectation simple

1. Utilisateur de test sans aucun groupe Passeport : `SELECT current_groups()` ne renvoie aucun groupe ; l'accès aux objets protégés est refusé.
2. Affecter l'utilisateur à **un seul** profil Passeport correspondant à un rôle BIAC de même nom. Attendre la propagation (au plus le TTL, section 8, ou purge de la section 7), ou ouvrir une nouvelle session après expiration.
3. Résultat attendu : le groupe unique apparaît ; avec `SET ROLE <profil>`, `SELECT` réussit sur les objets accordés au rôle ; l'accès à tout objet hors du rôle est refusé ; `passeport_perimetre()` renvoie le périmètre du profil.

### T02 : cas combinatoire (plusieurs profils)

1. Reproduire pour un utilisateur de test l'affectation multi-profils fournie par la CNAM (plusieurs profils, plusieurs domaines, plusieurs caisses, y compris un `perimetre` multiple du type `"011,717"`).
2. Résultat attendu : les groupes sont l'ensemble des profils (`current_groups()` inchangé) ; sous `SET ROLE <profil A>`, `passeport_perimetre()` renvoie le périmètre du profil A **seul** (une valeur `"011,717"` donne deux éléments, sans doublon, dans l'ordre de la réponse de l'API) ; sous `SET ROLE <profil B>`, celui de B seul ; avec deux rôles actifs (`SET ROLE ALL`), un tableau vide ; les lignes visibles correspondent exactement au périmètre du profil actif, sans droit en excès ni cumul entre profils.
3. Vérifier le profil sans périmètre (groupe sans `perimetre`) : aucune ligne visible sous ce rôle.
4. Relever pour chaque cas le résultat de `current_user_biac_roles()` (voir « Mesure des rôles actifs »).

### T03 : correspondance 1:1

1. Lister les profils Passeport du périmètre du test et les rôles BIAC assignés aux groupes correspondants.
2. Résultat attendu : chaque `code_pa` (groupe) a exactement un rôle assigné, **de même nom** ; chaque rôle est assigné à un seul groupe ; aucune exception, ou les exceptions sont documentées. Un groupe sans rôle assigné ne donne aucun privilège. Un rôle de nom différent du groupe ne donne **aucun périmètre**.

### T07 : purge du cache

1. Avec un utilisateur de test qui a `EXECUTE` sur `passeport_cache_invalidate` et `passeport_perimetre`, sous `SET ROLE <profil>`, relever `SELECT passeport_perimetre();`.
2. Côté Passeport (ou dans le simulateur), modifier le périmètre de ce profil pour cet utilisateur.
3. Avant la fin du TTL, relever de nouveau `SELECT passeport_perimetre();` : l'ancienne valeur est attendue (cache).
4. `SELECT passeport_cache_invalidate();` : résultat `true`. Un `false` indique que la fonction n'a pas été évaluée sur le coordinator : relever l'heure et les logs du coordinator et du worker (avertissement « evaluated on a node without Passeport service »).
5. `SELECT passeport_perimetre();` : la nouvelle valeur est attendue, sans attendre le TTL ni redémarrer.
6. Contrôle de portée : un second utilisateur déjà en cache n'est pas affecté (aucun nouvel appel API pour lui dans les logs DEBUG). Sans `EXECUTE`, l'appel donne `PERMISSION_DENIED`.

### Tests d'erreur (facultatifs, environnement de test)

- API coupée : la requête d'un utilisateur dont le cache est vide échoue ; elle réussit de nouveau dès que l'API revient, sans redémarrage.
- Propriété obligatoire retirée : le coordinator ne démarre pas, avec le message de la section 5.

---

## 10. Retour arrière

**Changement de comportement par rapport à la 3.0.23** : jusqu'à la 3.0.23, `passeport_perimetre()` renvoyait la **réunion** des périmètres de tous les groupes de l'utilisateur, quel que soit le rôle actif. Depuis la 3.0.24, elle renvoie le périmètre du **seul** groupe dont le nom est le rôle actif, et un tableau vide dans tous les autres cas. Les effets à attendre, dans les deux sens :

- à la mise à jour vers la 3.0.24 : les row filters qui s'appuient sur `passeport_perimetre()` n'affichent plus rien tant que l'utilisateur n'a pas choisi son profil, et tant que les rôles BIAC ne portent pas exactement les noms des groupes (section 7) ;
- au retour arrière vers la 3.0.23 : les périmètres de tous les profils d'un utilisateur sont de nouveau fusionnés, quel que soit le rôle actif (droits plus larges). Les fonctions `passeport_perimetre(varchar)` et `passeport_cache_invalidate()` disparaissent : retirer les row filters qui les utilisent avant le retour arrière, sinon ces requêtes échouent.

Procédure :

1. Restaurer la version précédente du plugin : revenir à l'archive précédente (option A : ancienne URL et ancien SHA-256 dans les values ; option B : ancienne image ; archive zip ou tar.gz, selon celle qui était utilisée).
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
9. **Assignation d'un rôle BIAC à un groupe** et octroi du `EXECUTE` sur fonction : procédure exacte dans la version de SEP cible (section 7).
10. **Rôles actifs dans une session** : ce que renvoie `current_user_biac_roles()` sans `SET ROLE` et sous `SET ROLE ALL` (section 9, « Mesure des rôles actifs »), et le comportement des outils clients (DBeaver, Power BI, etc.) : détermine l'expérience des utilisateurs sous le modèle de profils.
11. **Validation métier du modèle de profils** : un seul profil actif, nom du rôle égal au nom du groupe, aucun périmètre sans choix de profil.
12. **Groupe sans périmètre** : confirmer qu'il signifie « aucune caisse » (comportement du plugin) et non « toutes les caisses ».
