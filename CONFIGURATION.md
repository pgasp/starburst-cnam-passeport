# Guide de Configuration — Plugin CNAM Passeport (v1.1.0)

Ce document explique comment déployer et configurer le plugin `cnam-passeport` sur un cluster Starburst Enterprise (SEP) / Trino.

Depuis la version 1.1.0, ce plugin offre deux fonctionnalités principales :
1. **Group Provider** : Résolution dynamique des groupes (codes PA) depuis l'API REST Passeport.
2. **System Access Control** : Application dynamique de filtres de lignes (Row-Level Security) basés sur le périmètre de l'utilisateur.

---

## 1. Installation

1. Téléchargez l'archive `.zip` depuis la [page des releases GitHub](https://github.com/pgasp/starburst-cnam-passeport/releases).
2. Décompressez l'archive dans le répertoire des plugins de **tous les nœuds** du cluster (Coordinateur et Workers) :
   ```bash
   unzip passeport-group-provider-1.1.0.zip -d /path/to/trino/plugin/cnam-passeport
   ```
3. Redémarrez le cluster après avoir appliqué les configurations ci-dessous.

---

## 2. Prérequis Base de données (Si RLS activé)

Pour utiliser le filtrage RLS dynamique, le plugin a besoin d'une table technique pour stocker temporairement les périmètres de l'utilisateur connecté.

Créez cette table dans le catalogue de votre choix (ex: `system`, schéma `passeport`) :

```sql
CREATE SCHEMA IF NOT EXISTS system.passeport;

CREATE TABLE system.passeport.user_perimetre (
    upn VARCHAR(255) NOT NULL,
    code_pa VARCHAR(100) NOT NULL,
    perimetre VARCHAR(50) NOT NULL
);
```

**Droits nécessaires** : Le compte de service utilisé par le plugin (ex: `passeport_writer`) doit avoir les droits `SELECT`, `INSERT` et `DELETE` sur cette table.

---

## 3. Configuration du Group Provider

Créez le fichier `etc/group-provider.properties` sur le coordinateur.

### Mode A : Résolution de groupes uniquement (Sans RLS)
Si vous utilisez uniquement BIAC pour la sécurité et n'avez pas besoin du RLS dynamique par code caisse :

```properties
group-provider.name=cnam-passeport
passeport.code-application=MATIS_PROD
passeport.api-url=https://api.passeport.ramage/s1sem/habilitations
passeport.enable-perimetre-write=false

# (Optionnel) TLS Truststore si l'API utilise un certificat privé IGCT
# passeport.trust-store-path=/chemin/vers/truststore.jks
# passeport.trust-store-password=changeit
```

### Mode B : Groupes + RLS Dynamique (Recommandé)
Si vous activez le RLS, le Group Provider doit écrire les périmètres en base à chaque connexion. Pour éviter les dépendances cycliques avec Trino, connectez-vous directement à la base PostgreSQL sous-jacente :

```properties
group-provider.name=cnam-passeport
passeport.code-application=MATIS_PROD
passeport.api-url=https://api.passeport.ramage/s1sem/habilitations

# Activation de l'écriture
passeport.enable-perimetre-write=true

# Configuration JDBC (Connexion DIRECTE à la base PostgreSQL sous-jacente)
passeport.jdbc-url=jdbc:postgresql://postgres-host:5432/votre_base
passeport.jdbc-user=passeport_writer
passeport.jdbc-password=votre_mot_de_passe

# Cible d'écriture (utilisée par Trino pour le RLS, PostgreSQL utilisera uniquement schema.table)
passeport.perimetre-catalog=system
passeport.perimetre-schema=passeport
```

---

## 4. Configuration du System Access Control (RLS)

Pour activer l'application des filtres de sécurité, vous devez configurer le plugin d'accès et indiquer à Trino de l'utiliser en combinaison avec BIAC.

### 4.1. Fichier de configuration du plugin

Créez le fichier `etc/passeport-access-control.properties` :

```properties
access-control.name=passeport

# Mapping des tables à filtrer (Format: catalog.schema.table:nom_colonne_a_filtrer)
# Séparez les tables par des virgules.
passeport.row-filter-mappings=iceberg.coeurdata.vact:caiexe_act,iceberg.coeurdata.vpra:pracai_pra,iceberg.coeurdata.vpij:caiorg_pij

# Cible de lecture (⚠️ Doit être STRICTEMENT identique à la config du Group Provider)
passeport.perimetre-catalog=system
passeport.perimetre-schema=passeport
```

### 4.2. Chaînage avec BIAC (Coordinateur)

Modifiez le fichier `etc/config.properties` de votre coordinateur pour chaîner les contrôles d'accès. Trino appliquera la sémantique *Deny-Wins* (les restrictions de BIAC **et** de Passeport seront appliquées).

```properties
# Ajoutez cette ligne dans etc/config.properties
access-control.config-files=etc/biac.properties,etc/passeport-access-control.properties
```

---

## 5. Comportements et Sécurité (Fail-Closed)

* **Tolérance aux pannes API** : Si l'API Passeport est injoignable, le plugin retourne une liste de groupes vide (l'utilisateur n'aura aucun accès).
* **Tolérance aux pannes JDBC** : Si la base de données est injoignable lors de l'écriture (`enable-perimetre-write=true`), l'erreur est logguée. L'utilisateur récupère ses groupes (pour accéder à ses outils), mais la table `user_perimetre` restera vide. Conséquence : la sous-requête du RLS filtrera *toutes* les lignes des tables de santé. C'est le comportement de sécurité souhaité (Fail-Closed).
* **Performance (Rappel)** : Le RLS dynamique dépend fortement du **Dynamic Filtering** de Trino. Sur les vues fédérées, assurez-vous que chaque source (ex: bases Oracle) comporte un `WHERE` littéral pour garantir que Trino ne scanne pas toutes les bases nationales inutilement.
