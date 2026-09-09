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

## 4. Arborescence des Périmètres (Logique Métier)
- **Gestion de la hiérarchie CNAM** : Ajouter une logique spécifique dans la résolution des périmètres. Par exemple, si l'API retourne le code national (`000`), l'UDF pourrait renvoyer un marqueur spécial (`ALL`) ou générer la liste exhaustive des caisses régionales/locales, simplifiant grandement l'écriture des règles SQL BIAC.

## 5. System Access Control (Row-Level Security Natif)
- **Injection Automatique des Filtres** : Pour aller plus loin qu'un simple *Group Provider*, étendre ce plugin pour qu'il implémente l'interface `SystemAccessControl`. Cela permettrait d'injecter automatiquement la clause de filtrage `caisse IN (x, y)` directement dans le planificateur de requêtes de Starburst sur toutes les tables cibles, s'affranchissant ainsi de la création manuelle de règles BIAC avec l'UDF.