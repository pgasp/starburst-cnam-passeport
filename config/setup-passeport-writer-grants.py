"""
Configure les grants BIAC nécessaires au déploiement du plugin SystemAccessControl
(starburst-cnam-passeport) sur pga.

Deux besoins distincts :
1. Le compte de service `passeport_writer` (utilisé par PasseportGroupProvider pour écrire
   dans user_perimetre) a besoin de SELECT/INSERT/DELETE sur la table.
2. Chaque rôle qui interroge les vues gouvernées (poc_profil_limite, poc_profil_libre) a
   besoin de SELECT sur user_perimetre, car ViewExpression.identity() exécute la sous-requête
   du row filter sous l'identité de l'appelant courant, pas d'un compte fixe.

Usage : python3 setup-passeport-writer-grants.py --env ~/Documents/Obsidian/Work/dataproduct/servers/.env.pga
(le fichier .env.pga vit dans le repo Obsidian, pas dans ce repo plugin — chemin à adapter)
"""
import argparse
import base64
import os
import json
import urllib.request


def load_env(path):
    env = {}
    for l in open(path):
        l = l.strip()
        if "=" in l and not l.startswith("#"):
            k, v = l.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def call(host, port, auth, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"https://{host}:{port}{path}", data=data, method=method)
    req.add_header("Authorization", f"Basic {auth}")
    req.add_header("X-Trino-Role", "system=ROLE{sysadmin}")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--env", default=os.path.expanduser(
        "~/Documents/Obsidian/Work/dataproduct/servers/.env.pga"))
    p.add_argument("--writer-role-name", default="passeport_writer")
    p.add_argument("--writer-username", default="passeport_writer",
                    help="Doit correspondre à passeport.jdbc-user dans la config du plugin")
    p.add_argument("--catalog", default="postgres")
    p.add_argument("--schema", default="security_control")
    p.add_argument("--table", default="user_perimetre")
    p.add_argument("--reader-role-ids", default="4,5",
                    help="IDs des rôles BIAC qui interrogent les vues gouvernées "
                         "(poc_profil_libre=4, poc_profil_limite=5 sur cette démo)")
    args = p.parse_args()

    env = load_env(args.env)
    host, port = env["SB_HOST"], env.get("SB_PORT", "443")
    auth = base64.b64encode(f"{env['SB_USER']}:{env['SB_PASSWORD']}".encode()).decode()

    def biac(method, path, body=None):
        return call(host, port, auth, method, path, body)

    # 1. Rôle de service passeport_writer
    print("create role:", biac("POST", "/api/v1/biac/roles", {"name": args.writer_role_name}))
    roles = json.loads(biac("GET", "/api/v1/biac/roles")[1])["result"]
    writer_role = next(r for r in roles if r["name"] == args.writer_role_name)
    writer_role_id = writer_role["id"]
    print("writer_role_id:", writer_role_id)

    table_entity = {
        "category": "TABLES",
        "catalog": args.catalog,
        "schema": args.schema,
        "table": args.table,
    }

    for action in ["SELECT", "INSERT", "DELETE", "SHOW"]:
        print(f"grant {action} to writer:",
              biac("POST", f"/api/v1/biac/roles/{writer_role_id}/grants",
                   {"effect": "ALLOW", "action": action, "entity": table_entity}))

    # CREATE au niveau schéma pour permettre la création de la table si absente
    schema_entity = {"category": "TABLES", "catalog": args.catalog, "schema": args.schema, "table": ""}
    print("grant CREATE (schema) to writer:",
          biac("POST", f"/api/v1/biac/roles/{writer_role_id}/grants",
               {"effect": "ALLOW", "action": "CREATE", "entity": schema_entity}))

    # Assignation utilisateur -> rôle de service
    print("assign user to writer role:",
          biac("POST", f"/api/v1/biac/subjects/users/{args.writer_username}/assignments",
               {"roleId": writer_role_id, "roleAdmin": False}))

    # 2. SELECT sur user_perimetre pour chaque rôle appelant (row filter exécuté en tant qu'invoker)
    for rid in args.reader_role_ids.split(","):
        rid = rid.strip()
        print(f"grant SELECT to role {rid}:",
              biac("POST", f"/api/v1/biac/roles/{rid}/grants",
                   {"effect": "ALLOW", "action": "SELECT", "entity": table_entity}))
        print(f"grant SHOW to role {rid}:",
              biac("POST", f"/api/v1/biac/roles/{rid}/grants",
                   {"effect": "ALLOW", "action": "SHOW", "entity": table_entity}))


if __name__ == "__main__":
    main()
