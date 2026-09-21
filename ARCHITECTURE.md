# Architecture : Plugin Passeport pour Starburst Enterprise

Ce document décrit l'architecture finale et le flux d'intégration du plugin `passeport-group-provider` (version 2.0.0) au sein de l'environnement Starburst Enterprise sur AWS EKS (`fr-ai-workshop`).

## 1. Vue d'Ensemble de l'Architecture (Overview)

Ce diagramme illustre le parcours de la donnée depuis la connexion de l'utilisateur jusqu'à l'accès physique aux tables. Il met en évidence la séparation des responsabilités entre l'authentification (Starburst), la résolution des groupes (notre plugin via l'API Passeport), et l'application des filtres de sécurité sur les données (System Access Control).

```mermaid
graph TD
    Client([Clients SQL<br>DBeaver, UI, Jupyter]) -->|1. Identifiants| Auth[Authentification Starburst<br>Password/LDAP]
    
    subgraph Cluster_EKS [Cluster Starburst Enterprise]
        Auth -->|2. Username validé| PGP(Passeport Group Provider<br>Plugin Java Custom)
        PGP -->|4. Liste des Groupes| SAC(System Access Control<br>Règles & Sécurité)
        SAC -->|5. Application des Filtres<br>Row-Level / Column-Level| Engine[Moteur de requête Trino]
    end

    PGP -->|3. Interrogation API / Cache| API_Passeport((API Passeport<br>Référentiel Externe))
    API_Passeport -->|Retour JSON| PGP
    
    Engine -->|6. Accès Sécurisé| Tables[(Bases de données<br>Iceberg, Postgres...)]

    style PGP fill:#10b981,color:white,stroke:#047857,stroke-width:2px
    style SAC fill:#f59e0b,color:white,stroke:#b45309,stroke-width:2px
    style API_Passeport fill:#3b82f6,color:white,stroke:#1d4ed8,stroke-width:2px
```

## 2. Diagramme de Séquence (Exécution d'une Requête et Filtrage)

Ce diagramme de séquence se concentre spécifiquement sur le cycle de vie d'une requête SQL : de l'authentification jusqu'à l'application des filtres (Row-Level et Column-Level) sur les tables, en passant par l'interrogation de l'API Passeport.

```mermaid
sequenceDiagram
    autonumber
    actor User as Client (DBeaver/UI)
    participant SEP as Moteur Starburst
    participant PGP as Plugin Passeport<br>(Group Provider)
    participant API as API Passeport
    participant SAC as System Access Control
    participant Engine as Moteur d'Exécution<br>& Tables (Iceberg)

    User->>SEP: 1. Envoi requête SQL (ex: SELECT * FROM table_pro) + Credentials
    SEP->>SEP: 2. Validation Authentification (Mot de passe)
    
    SEP->>PGP: 3. getGroups(username)
    
    alt Groupes en Cache local
        PGP-->>SEP: Retourne les groupes (Hit)
    else Groupes non mis en cache (Miss)
        PGP->>API: 4. GET /api/v1/users/{username}/perimetres
        API-->>PGP: 5. Réponse JSON (ex: ["PRO", "RH"])
        PGP-->>SEP: 6. Retourne les groupes
    end
    
    SEP->>SAC: 7. checkCanSelectFromColumns(user, groups, table)
    SAC->>SAC: 8. Analyse des droits basés sur les groupes ["PRO", "RH"]
    SAC-->>SEP: 9. Applique filtres (ex: Row Filter "region='IDF'")
    
    SEP->>Engine: 10. Exécution de la requête réécrite avec les filtres
    Engine-->>SEP: 11. Données brutes filtrées
    SEP-->>User: 12. Résultat final sécurisé
```

## Composants Clés

### 1. Livraison et Déploiement (GitOps)
* Le plugin est développé en **Java 25** pour être compatible avec l'API Trino 482.
* La livraison se fait via des **GitHub Releases**. Les binaires ne sont pas stockés dans l'arbre source Git.
* Sur AWS EKS, un `initContainer` télécharge l'archive au démarrage du pod `coordinator` via `wget`, garantissant un déploiement immuable et sans dépendance à S3.

### 2. Le Plugin : Group Provider
* Le plugin s'enregistre auprès de Starburst sous le nom `passeport` (`group-provider.name=passeport`).
* Il a pour rôle exclusif de résoudre l'identité (à quels groupes appartient l'utilisateur). La vérification du mot de passe reste gérée par Starburst.

### 3. Résilience et Performances (No JDBC)
* **API REST** : L'ancienne connexion directe à la base de données PostgreSQL a été remplacée par des appels à une API REST d'entreprise.
* **Cache en mémoire** : Pour éviter d'introduire de la latence lors de la planification des requêtes SQL, les périmètres sont mis en cache.
* **Tolérance aux pannes** : En cas d'indisponibilité de l'API Passeport, les exceptions sont attrapées (`try/catch`) et un ensemble vide est retourné, évitant ainsi le crash (Erreur 500) du serveur Starburst.