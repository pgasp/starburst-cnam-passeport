# Améliorations de l'implémentation (Piste 1)

## Découplage de l'écriture des périmètres (Opt-In JDBC)

Le plugin a été refactoré pour permettre d'utiliser le `PasseportGroupProvider` soit de manière autonome (seulement la récupération des groupes depuis l'API Passeport), soit couplé avec l'écriture des périmètres RLS en base de données. 

L'écriture dans la table `user_perimetre` via JDBC est désormais strictement opt-in via la propriété :
`passeport.enable-perimetre-write=true`

### Configuration de la résolution de groupes (sans écriture JDBC)

Si vous souhaitez utiliser le plugin uniquement pour résoudre les groupes (ex: utilisation classique pour les rôles BIAC), omettez la configuration JDBC. Le Provider sera actif et les requêtes JDBC ne seront pas tentées :

```properties
# etc/group-provider.properties
group-provider.name=cnam-passeport
passeport.enable-perimetre-write=false
passeport.code-application=MATIS_PROD
passeport.api-url=https://api.passeport.ramage/s1sem/habilitations
```

### Configuration complète (Groupes + RLS avec écriture JDBC)

Si vous utilisez le RLS dynamique (`PasseportSystemAccessControl`), le `GroupProvider` doit être configuré pour injecter les périmètres dans Trino afin que la sous-requête puisse filtrer les tables :

```properties
# etc/group-provider.properties
group-provider.name=cnam-passeport
passeport.enable-perimetre-write=true
passeport.code-application=MATIS_PROD
passeport.api-url=https://api.passeport.ramage/s1sem/habilitations

# Configuration JDBC obligatoire si write=true
passeport.jdbc-url=jdbc:trino://localhost:8080
passeport.jdbc-user=passeport_writer
passeport.jdbc-password=...

# Définition de la table cible (doit correspondre à celle du SystemAccessControl)
passeport.perimetre-catalog=system
passeport.perimetre-schema=passeport
passeport.perimetre-table=user_perimetre
```

**⚠️ Point de vigilance :** La table cible (`catalog`, `schema`, `table`) est configurée *manuellement* dans deux fichiers de propriétés différents s'ils sont séparés (`etc/group-provider.properties` et `etc/passeport-access-control.properties`). Vous devez vous assurer que les trois valeurs correspondent exactement entre les deux configurations.

## Validation Stricte
Si `passeport.enable-perimetre-write=true` est activé, la Fabrique s'assurera au démarrage du coordinateur que `passeport.jdbc-url` et `passeport.jdbc-user` sont renseignés, sous peine de lever une exception `IllegalArgumentException` et d'empêcher le démarrage silencieux d'une configuration défectueuse.
