# 🚀 TODO & Améliorations Futures

Ce document liste les évolutions envisagées pour le plugin `passeport-group-provider` afin d'enrichir ses fonctionnalités et sa robustesse en environnement de production (CNAM).

## 1. Résilience Avancée (Stale Cache)
- **Amélioration du Fail-Closed** : Actuellement, si l'API Passeport est indisponible (Timeout, 500, 503), le plugin retourne 0 groupe pour protéger le système. Il serait intéressant d'implémenter un mécanisme de "Stale Cache" (retourner la dernière valeur connue en cache même si le TTL de 15 minutes est expiré, pendant un délai de grâce) afin d'éviter les coupures de service lors des micro-interruptions de l'API.

## 2. Intégration JMX (Monitoring Ops)
- **Exposition de métriques** : Ajouter des MBeans JMX dans le plugin pour permettre à Starburst et Prometheus (Grafana) de monitorer l'état de santé de l'intégration :
  - Nombre d'appels à l'API Passeport (Requêtes HTTP envoyées).
  - Temps de réponse moyen (Latence de l'API).
  - Nombre d'erreurs HTTP (Timeout, 4xx, 5xx).
  - Taille actuelle du cache Guava et ratio de *Hit/Miss*.

## 3. Gestion Avancée du TrustStore (TLS/HTTPS)
- **Certificats Internes (IGCT)** : Valider en conditions réelles la mécanique d'injection d'un TrustStore personnalisé (`passeport.trust-store-path`) dans le `java.net.http.HttpClient`, ce qui permet d'attaquer une API sécurisée par des certificats d'entreprise sans nécessiter la modification du `cacerts` global de la JVM Starburst.

## 4. Arborescence des Périmètres & System Access Control (Recommandation Majeure)
**Problématique :** Actuellement, le plugin s'appuie sur l'interface BIAC de Starburst via une UDF (`caisse IN (passeport_perimetre())`). Cette approche pose un problème majeur pour la hiérarchie CNAM (ex: un profil National avec le code `000`). Si la règle BIAC reste statique, une requête avec `caisse IN ('000')` ne renverra aucune donnée car les tables contiennent les codes de caisses réels (311, etc.).

**Solution préconisée (RLS Natif) :** 
Transformer le plugin pour qu'il implémente l'interface `SystemAccessControl`. Cela permet d'injecter la Row-Level Security (RLS) directement dans le planificateur du moteur SQL via du code Java, sans configuration manuelle dans BIAC.

**Avantages du SystemAccessControl :**
- **Bypass Automatique (Performance maximale) :** Si le code Java détecte que l'utilisateur possède le périmètre `000`, il peut choisir de ne générer *aucun filtre* (`Optional.empty()`). Le profil National accède à toute la table sans le surcoût de la clause `IN`.
- **Développement de Macros Régionales :** Le code Java peut facilement intercepter un macro-code (ex: `REG_BRETAGNE`) et générer la clause SQL étendue (`caisse IN ('351', '291', '221', '561')`).
- **Administration Zéro :** Plus besoin de configurer manuellement le Row Filter sur des milliers de tables dans l'interface Starburst. L'injection est automatique et infaillible.
- **Fail-Closed Natif :** Si l'utilisateur n'a aucun périmètre, le plugin injecte la condition `false`, garantissant qu'aucune ligne ne sera exposée, même si l'administrateur a oublié de configurer BIAC.