# Guide de Configuration — Plugin CNAM Passeport

Ce document explique comment déployer et configurer le plugin `cnam-passeport` sur un cluster Starburst Enterprise (SEP) / Trino.

Depuis la version 1.1.8, le plugin a été réarchitecturé pour des performances maximales. Il offre deux fonctionnalités principales en s'appuyant sur un cache partagé en mémoire :
1. **Group Provider** : Résolution dynamique des groupes (codes PA) depuis l'API REST Passeport.
2. **System Access Control** : Application dynamique de filtres de lignes (Row-Level Security) basés sur les périmètres mis en cache lors de l'authentification.

---

## 1. Installation

1. Téléchargez l'archive `.zip` depuis la [page des releases GitHub](https://github.com/pgasp/starburst-cnam-passeport/releases).
2. Décompressez l'archive dans le répertoire des plugins de **tous les nœuds** du cluster (Coordinateur et Workers) :
   ```bash
   unzip passeport-group-provider-1.1.8.zip -d /path/to/trino/plugin/cnam-passeport
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

# (Obsolète depuis v1.1.8) La journalisation en base n'est plus requise pour le RLS
passeport.enable-perimetre-write=false

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

# Remarque : Les propriétés passeport.perimetre-catalog et passeport.perimetre-schema sont obsolètes
# depuis la version 1.1.8 car le filtrage s'appuie désormais sur un cache en mémoire extrêmement rapide.
```

### 3.2. Chaînage avec BIAC (Coordinateur)

Modifiez le fichier `etc/config.properties` de votre coordinateur pour chaîner les contrôles d'accès. Trino appliquera la sémantique *Deny-Wins* (les restrictions de BIAC **et** de Passeport seront appliquées).

```properties
# Ajoutez cette ligne dans etc/config.properties
access-control.config-files=etc/biac.properties,etc/passeport-access-control.properties
```

---

## 4. Comportements et Sécurité (Fail-Closed)

* **Tolérance aux pannes API** : Si l'API Passeport est injoignable ou en timeout, le plugin adopte une politique "Fail-Closed". La liste des groupes retournée sera vide, et aucun périmètre ne sera placé en cache.
* **Sécurité RLS (Fail-Closed)** : Lorsqu'un utilisateur exécute une requête sur une table sécurisée, le plugin vérifie son identité dans le cache mémoire. S'il ne le trouve pas (expiration, ou erreur API précédente), le filtre injecté sera strictement `FALSE`. Ainsi, **aucune donnée n'est renvoyée**, interdisant toute fuite d'information.
* **Performances** : Grâce à l'utilisation du cache, le moteur Trino construit la requête SQL avec une clause statique très rapide (ex: `WHERE caiexe_act IN ('01', '02')`) plutôt qu'une sous-requête, allégeant la charge du coordinateur et des workers.
