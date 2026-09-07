# Configuration et Déploiement du Plugin Passeport

Ce plugin (GroupProvider + UDF BIAC) interroge l'API Passeport de la CNAM pour résoudre les rôles (groupes) et le périmètre de données (caisses) d'un utilisateur.

## 1. Prérequis TLS (Trust Store IGCT)

L'API Passeport est exposée en HTTPS avec un certificat émis par l'autorité interne IGCT. Le plugin doit faire confiance à cette autorité.

### Étape 1 : Créer le Trust Store Java (JKS)
Si vous disposez du certificat racine IGCT (`igct-root.crt`), exécutez la commande suivante pour l'importer dans un nouveau keystore :

```bash
keytool -import -alias igct-root -file igct-root.crt -keystore passeport-truststore.jks -storepass changeit
```

### Étape 2 : Déployer le fichier sur le cluster
Montez le fichier `passeport-truststore.jks` sur tous les pods **Coordinator** du cluster Starburst, par exemple dans `/etc/starburst/tls/`.

## 2. Configuration du Plugin dans Starburst (SEP)

Dans le répertoire des configurations Starburst (souvent `/etc/starburst/catalog/` ou via Helm `groupProvider.properties`), ajoutez un fichier de configuration pour le Group Provider, par exemple `group-provider.properties` :

```properties
# Nom enregistré par le plugin Java
group-provider.name=cnam-passeport

# Code application à passer à l'API Passeport
passeport.code-application=MATIS_PROD

# URL de l'API Passeport (qualif ou prod)
passeport.api-url=https://api.passeport.ramage/s1sem/habilitations

# Chemins et mot de passe du TrustStore créé à l'étape 1
passeport.trust-store-path=/etc/starburst/tls/passeport-truststore.jks
passeport.trust-store-password=changeit
```

## 3. Mise à jour du Certificat

En cas de renouvellement du certificat côté CNAM :
1. Obtenir le nouveau certificat racine/intermédiaire.
2. L'ajouter au fichier JKS existant avec `keytool -import -alias new-igct ...`.
3. Redémarrer le Coordinator Starburst pour que le composant `HttpClient` Java prenne en compte le nouveau *trust store*.
