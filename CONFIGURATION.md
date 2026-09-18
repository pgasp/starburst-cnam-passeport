# Guide de Configuration — Plugin CNAM Passeport

Ce document explique comment déployer et configurer le plugin `cnam-passeport` sur un cluster Starburst Enterprise (SEP) / Trino.

Depuis la version 1.1.10, le plugin a été réarchitecturé pour des performances maximales en se séparant définitivement du mode persistant JDBC. Il offre deux fonctionnalités principales en s'appuyant sur un cache partagé en mémoire :
1. **Group Provider** : Résolution dynamique des groupes (codes PA) depuis l'API REST Passeport.
2. **System Access Control** : Application dynamique de filtres de lignes (Row-Level Security) basés sur les périmètres mis en cache lors de l'authentification.

---

## 1. Installation

1. Téléchargez l'archive `.zip` depuis la [page des releases GitHub](https://github.com/pgasp/starburst-cnam-passeport/releases).
2. Décompressez l'archive dans le répertoire des plugins de **tous les nœuds** du cluster (Coordinateur et Workers) :
   ```bash
   unzip passeport-group-provider-1.1.10.zip -d /path/to/trino/plugin/cnam-passeport
   ```
3. Redémarrez le cluster après avoir appliqué les configurations ci-dessous.

---

## 2. Configuration du Group Provider

Le Group Provider est responsable d'interroger l'API Passeport et de stocker les résultats dans le cache en mémoire (valable 15 minutes par utilisateur).

Créez le fichier `etc/group-provider.properties` sur le coordinateur.

```properties
group-provider.name=cnam-passeport
passeport.code-application=MATIS_PROD
passeport.api-url=https://api.passeport.ramage/s1sem/habilitations

# (Optionnel) TLS Truststore si l'API utilise un certificat privé IGCT
# passeport.trust-store-path=/chemin/vers/truststore.jks
# passeport.trust-store-password=changeit
```

---

## 3. Configuration du System Access Control (RLS)

Pour activer l'application des filtres de sécurité, vous devez configurer le plugin d'accès et indiquer à Trino de l'utiliser en combinaison avec BIAC.

### 3.1. Fichier de configuration du plugin

Créez le fichier `etc/passeport-access-control.properties` :

```properties
access-control.name=passeport

# Mapping des tables à filtrer (Format: catalog.schema.table:nom_colonne_a_filtrer)
# Séparez les tables par des virgules.
passeport.row-filter-mappings=iceberg.coeurdata.vact:caiexe_act,iceberg.coeurdata.vpra:pracai_pra,iceberg.coeurdata.vpij:caiorg_pij

# (Optionnel) Compte de service pour forcer le Row Filter en mode Definer. 
# Recommandation : laissez vide pour utiliser l'Invoker rights et préserver le contexte BIAC
# passeport.service-account=starburst_service

# (Optionnel) Clé de déchiffrement pour la fonction decrypt_assmac() — voir section 3.3.
# passeport.assmac-encryption-key=${ENV:ERASME_ASSMAC_KEY}
```

### 3.2. Chaînage avec BIAC (Coordinateur)

Modifiez le fichier `etc/config.properties` de votre coordinateur pour chaîner les contrôles d'accès. Trino appliquera la sémantique *Deny-Wins* (les restrictions de BIAC **et** de Passeport seront appliquées).

```properties
# Ajoutez cette ligne dans etc/config.properties
access-control.config-files=etc/biac.properties,etc/passeport-access-control.properties
```

### 3.3. Déchiffrement de colonne — `decrypt_assmac()`

Depuis l'exigence CoeurDATA du 18/09/2026 (voir la note `2026-09-18 - Plan Implementation
Chiffrement AES-256 assmac_act (erasme_5M).md` côté vault), le plugin expose une fonction
scalaire `decrypt_assmac(VARCHAR) -> VARCHAR` qui déchiffre une valeur chiffrée en amont
(à l'ingestion, hors plugin) et retourne le texte en clair aux appelants autorisés.

**Chiffrement (hors plugin)** : la colonne cible (ex: `assmac_act`) doit être chiffrée en
AES-256-GCM avant chargement — IV/nonce aléatoire de 12 octets par valeur, tag GCM (16 octets)
laissé accolé au ciphertext par le chiffreur, aucune AAD. Valeur stockée :
`base64(IV(12) || ciphertext_avec_tag)`. Un exemple d'implémentation Python (`cryptography.AESGCM`)
et Java (`AssmacCipher`, ce même plugin) suit exactement ce contrat.

**Configuration** : ajoutez la clé AES-256 (32 octets bruts, encodée en base64) dans
`etc/passeport-access-control.properties` :

```properties
passeport.assmac-encryption-key=${ENV:ERASME_ASSMAC_KEY}
```

- La clé n'est **jamais** stockée en clair dans un fichier versionné — utilisez la substitution
  `${ENV:...}` de Trino pour l'injecter via une variable d'environnement au démarrage du
  coordinateur.
- Si la propriété est absente ou vide, `decrypt_assmac()` reste utilisable syntaxiquement mais
  échoue systématiquement (fail-closed, voir section 4) faute de clé chargée.
- Si la valeur fournie n'est pas un base64 valide, ou ne décode pas en exactement 32 octets, le
  plugin refuse de démarrer (`IllegalArgumentException` au chargement de la configuration) plutôt
  que de démarrer silencieusement avec une clé invalide.

**Contrôle d'accès BIAC** : `decrypt_assmac()` doit recevoir un grant `EXECUTE` **précis**,
scopé sur `{"category":"FUNCTIONS","catalog":"system","schema":"builtin","function":"decrypt_assmac"}`
— jamais `allEntities:true` (fuite de visibilité catalogue). Ce grant ne s'hérite **pas** via le
nesting de rôles BIAC (bug documenté sur ce projet, cf. note du 15/09) : accordez-le directement
à chaque rôle qui doit déchiffrer, pas seulement au rôle composite parent. Exposition recommandée :
un column mask BIAC (`decrypt_assmac(assmac_act)`) attaché uniquement aux rôles autorisés, plutôt
qu'un appel explicite laissé à la discrétion de chaque requête.

---

## 4. Comportements et Sécurité (Fail-Closed)

* **Tolérance aux pannes API** : Si l'API Passeport est injoignable ou en timeout, le plugin adopte une politique "Fail-Closed". La liste des groupes retournée sera vide, et aucun périmètre ne sera placé en cache.
* **Sécurité RLS (Fail-Closed)** : Lorsqu'un utilisateur exécute une requête sur une table sécurisée, le plugin vérifie son identité dans le cache mémoire. S'il ne le trouve pas (expiration, ou erreur API précédente), le filtre injecté sera strictement `FALSE`. Ainsi, **aucune donnée n'est renvoyée**, interdisant toute fuite d'information.
* **Performances** : Grâce à l'utilisation du cache, le moteur Trino construit la requête SQL avec une clause statique très rapide (ex: `WHERE caiexe_act IN ('01', '02')`) plutôt qu'une sous-requête, allégeant la charge du coordinateur et des workers.
* **Déchiffrement (Fail-Closed)** : `decrypt_assmac()` ne lève jamais d'exception visible côté client et ne renvoie jamais de texte partiel. Toute erreur (clé absente/invalide, ciphertext corrompu, tag GCM invalide) renvoie `NULL` — l'appelant voit une valeur manquante, jamais une donnée partiellement déchiffrée ni un message d'erreur révélant la nature du problème.
