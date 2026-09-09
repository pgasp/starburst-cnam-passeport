# Starburst CNAM Passeport Integration

Ce projet est un plugin sur-mesure pour **Starburst Enterprise (SEP) / Trino** développé pour la CNAM (projet Matis et autres clusters). 

Il permet d'intégrer Starburst au système d'habilitation **Passeport** de la CNAM via son API REST, pour gérer de bout en bout l'authentification et les autorisations (Row-Level Security / Filtrage de données) sans nécessiter de synchronisation d'annuaire (ex: création massive de groupes AD).

## 🧩 Composants du Plugin

Le plugin (`passeport-group-provider`) est packagé dans un seul `.jar` et expose deux points d'extension au moteur Starburst :

1. **GroupProvider (`cnam-passeport`)** : Interroge l'API Passeport au moment de l'activité de l'utilisateur pour récupérer ses rôles (`code_pa`). Si l'API est indisponible, le plugin applique un modèle *Fail-Closed* (retourne 0 groupe, bloquant l'accès sans faire crasher le cluster). Les appels sont optimisés via un cache interne avec un TTL de 15 minutes.
2. **UDFs SQL (BIAC RLS)** : 
   - `passeport_perimetre()` : UDF lisant un cache en mémoire pour renvoyer le périmètre autorisé (liste des caisses) de l'utilisateur courant (via l'identité de session, méthode sécurisée).
   - `passport_biac_roles()` : UDF de débogage et d'audit qui retourne la liste des rôles système (`enabledSystemRoles`) activés pour la session courante (renvoie une chaîne de caractères formatée).
   - `flush_passeport_cache(varchar)` : UDF d'administration permettant de purger le cache d'un utilisateur à chaud.

---

## 🛠️ Build & Compilation

Le projet utilise Maven et nécessite un JDK 17 minimum (compatible avec le bytecode de Trino 480).

```bash
# Compilation et exécution des tests unitaires
mvn clean package
```

Le livrable sera généré sous : `target/passeport-group-provider-1.0-SNAPSHOT.jar`

---

## 🚀 Guide d'Intégration et de Déploiement

### 1. Installation du binaire
Créez un dossier pour le plugin sur tous les nœuds (ou via votre image conteneur / Helm chart) et copiez-y le jar compilé :
```bash
mkdir -p /usr/lib/starburst/plugin/cnam-passeport
cp target/passeport-group-provider-1.0-SNAPSHOT.jar /usr/lib/starburst/plugin/cnam-passeport/
```

### 2. Configuration du Group Provider
Créez le fichier `etc/group-provider.properties` (ou configurez-le via Helm `groupProvider.properties`) avec le contenu suivant :

```properties
# Nom du SPI enregistré par le plugin Java
group-provider.name=cnam-passeport

# [OBLIGATOIRE] Code application Passeport (ex: MATIS_PROD, MATIS_QUALIF...)
passeport.code-application=MATIS_PROD

# [OBLIGATOIRE] URL de l'API Passeport
passeport.api-url=https://api.passeport.ramage/s1sem/habilitations

# [OPTIONNEL] Configuration TLS (Trust Store pour l'autorité IGCT)
# Si non renseigné, le Trust Store standard de la JVM sera utilisé
passeport.trust-store-path=/etc/starburst/tls/passeport-truststore.jks
passeport.trust-store-password=changeit
```
*(Voir le fichier `README-TLS.md` pour le détail de la génération du fichier JKS).*

### 3. Intégration BIAC (Row-Level Security)

Une fois le plugin déployé et le cluster redémarré, les groupes `code_pa` remonteront automatiquement dans Starburst et le périmètre "caisse" sera stocké en mémoire.

Pour appliquer le filtrage dynamique des données (RLS) :

**A. Autoriser l'exécution de l'UDF**
Dans les politiques BIAC, vous devez accorder le privilège `EXECUTE` sur la fonction aux rôles concernés.
- Ressource : `Function` -> `passeport_perimetre`
- Privilège : `EXECUTE` -> `ALLOW`

**B. Créer le Row Filter**
Sur les tables ou vues contenant une colonne `caisse` (ex: `table_invalidite`), créez un Row Filter BIAC avec l'expression SQL suivante :
```sql
caisse IN (passeport_perimetre())
```

Dès lors, chaque requête exécutée par un utilisateur sera dynamiquement filtrée pour ne renvoyer que les lignes correspondant aux caisses autorisées par Passeport pour cet utilisateur.

---

## ⚙️ Administration, Performances et Opérations

### Architecture et Performances (Cache Interne)
Pour garantir de très hautes performances et éviter de surcharger l'API Passeport, le plugin implémente un cache interne très performant basé sur **Google Guava**. 

Étant donné que l'architecture de Starburst est de type "Stateless", chaque requête SQL déclenche une vérification des groupes de l'utilisateur. Le comportement est le suivant :
1. **CACHE MISS :** À la première requête d'un utilisateur, l'API Passeport est appelée (appel synchrone HTTP). Les habilitations sont stockées en mémoire.
2. **CACHE HIT :** Pendant **15 minutes** (TTL configuré), toutes les requêtes suivantes de cet utilisateur utiliseront exclusivement la mémoire vive (0 milliseconde, 0 appel réseau).
3. **Expiration :** Après 15 minutes, l'entrée expire silencieusement. La requête suivante déclenchera à nouveau un CACHE MISS pour actualiser les droits.

Ce modèle garantit que des bombardements de requêtes (ex: tableaux de bord BI) n'impactent jamais l'infrastructure d'authentification de la CNAM.

### Invalidation du cache (Révocation manuelle)
Pour révoquer les droits d'un utilisateur *avant* la fin des 15 minutes du TTL, un administrateur Starburst peut exécuter l'UDF de flush directement depuis son client SQL (DBeaver, Trino CLI, etc.) :

```sql
-- Vider le cache d'un utilisateur spécifique
SELECT flush_passeport_cache('jean.dupont@cnam.fr');

-- Vider le cache de TOUS les utilisateurs
SELECT flush_passeport_cache(NULL);
```
*(L'appel de cette fonction nécessite un privilège `EXECUTE` dans BIAC).*

### Résilience
Si l'API Passeport est hors service (`500`, `Timeout`), la politique de sécurité **Fail-Closed** s'applique : l'utilisateur perd ses groupes Starburst et son périmètre de données est vide. Dès le retour de l'API, le fonctionnement nominal reprend au prochain login SSO.
