# Architecture : Plugin Passeport pour Starburst Enterprise

Ce document décrit l'architecture finale et le flux d'intégration du plugin `passeport-group-provider` (version 2.0.0) au sein de l'environnement Starburst Enterprise sur AWS EKS (`fr-ai-workshop`).

## Diagramme d'Intégration (Cible : Starburst 482-e)

```mermaid
sequenceDiagram
    autonumber
    actor User as Utilisateur / DBeaver
    participant EKS as InitContainer (AWS EKS)
    participant GitHub as GitHub Releases
    participant SEP as Starburst Coordinator
    participant Plugin as Plugin Passeport
    participant Cache as In-Memory Cache
    participant API as API REST Passeport

    Note over EKS, GitHub: Phase de déploiement (Helm Upgrade)
    EKS->>GitHub: Télécharge passeport-group-provider-2.0.0.zip
    GitHub-->>EKS: Archive ZIP
    EKS->>SEP: Extrait dans /usr/lib/starburst/plugin/cnam-passeport
    SEP->>SEP: Démarre (SERVER STARTED)

    Note over User, API: Phase de requête SQL
    User->>SEP: Authentification (Mot de passe) + Requête SQL
    SEP->>Plugin: getGroups(username)
    Plugin->>Cache: Vérifie présence des groupes
    
    alt Cache Miss (Non trouvé)
        Plugin->>API: Appel HTTP (https://api.passeport.ramage/...)
        API-->>Plugin: Retourne les groupes (JSON)
        Plugin->>Cache: Stocke le résultat
    else Cache Hit (Trouvé)
        Cache-->>Plugin: Retourne les groupes en mémoire
    end
    
    Plugin-->>SEP: Set<String> (Groupes de l'utilisateur)
    SEP->>SEP: Applique le contrôle d'accès basé sur les groupes
    SEP-->>User: Résultat de la requête SQL (HTTP 200)
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