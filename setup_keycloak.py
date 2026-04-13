import urllib.request
import urllib.parse
import json
import sys
from urllib.error import HTTPError

KEYCLOAK_URL = "http://localhost:8180"
ADMIN_USER = "admin"
ADMIN_PASS = "REDACTED"

def make_request(url, method="GET", data=None, headers=None, urlencoded=False):
    if headers is None:
        headers = {}
        
    encoded_data = None
    if data is not None:
        if urlencoded:
            encoded_data = urllib.parse.urlencode(data).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            encoded_data = json.dumps(data).encode("utf-8")
            if "Content-Type" not in headers:
                headers["Content-Type"] = "application/json"
                
    req = urllib.request.Request(url, data=encoded_data, headers=headers, method=method)
    
    try:
        with urllib.request.urlopen(req) as response:
            content = response.read()
            if content:
                return response.status, json.loads(content.decode("utf-8"))
            return response.status, None
    except HTTPError as e:
        content = e.read()
        try:
            body = json.loads(content.decode("utf-8"))
        except:
            body = content.decode("utf-8")
        return e.code, body

def get_token():
    print("Getting token...")
    status, data = make_request(
        f"{KEYCLOAK_URL}/realms/master/protocol/openid-connect/token",
        method="POST",
        data={
            "client_id": "admin-cli",
            "username": ADMIN_USER,
            "password": ADMIN_PASS,
            "grant_type": "password"
        },
        urlencoded=True
    )
    if status != 200:
        print("Failed to get token:", data)
        sys.exit(1)
    return data["access_token"]

def main():
    token = get_token()
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }

    realm_name = "insureflow"
    
    # 0. Delete Realm if it exists
    print(f"Deleting existing realm {realm_name} (if any)...")
    make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}", method="DELETE", headers=headers)

    # 1. Create Realm
    print(f"Creating realm {realm_name}...")
    realm_data = {
        "realm": realm_name,
        "enabled": True
    }
    status, data = make_request(f"{KEYCLOAK_URL}/admin/realms", method="POST", headers=headers, data=realm_data)
    if status not in (200, 201):
        print(f"Failed to create realm. Status: {status}, data: {data}")
        sys.exit(1)
    
    # 2. Create Clients
    clients = [
        {
            "clientId": "insureflow-backend",
            "protocol": "openid-connect",
            "publicClient": True,
            "directAccessGrantsEnabled": True
        },
        {
            "clientId": "insureflow-frontend",
            "protocol": "openid-connect",
            "publicClient": True,
            "redirectUris": ["http://localhost:4200/*"],
            "webOrigins": ["http://localhost:4200"],
            "attributes": {
                "post.logout.redirect.uris": "http://localhost:4200"
            },
            "directAccessGrantsEnabled": True
        }
    ]
    
    for client in clients:
        print(f"Creating client {client['clientId']}...")
        status, data = make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/clients", method="POST", headers=headers, data=client)
        if status not in (200, 201):
            print(f"Failed to create client {client['clientId']}. Status: {status}, data: {data}")
            sys.exit(1)

    status, all_clients = make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/clients", headers=headers)
    backend_client_id_internal  = next((c["id"] for c in all_clients if c["clientId"] == "insureflow-backend"), None)
    frontend_client_id_internal = next((c["id"] for c in all_clients if c["clientId"] == "insureflow-frontend"), None)
    
    # 3. Create Roles
    roles = ["CLIENT", "ADMIN"]
    for role in roles:
        print(f"Creating role {role}...")
        status, data = make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/roles", method="POST", headers=headers, data={"name": role})
        if status not in (200, 201):
            print(f"Failed to create role {role}. Status: {status}, data: {data}")
            sys.exit(1)
            
    # Get role definitions for user assignment
    status, all_roles = make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/roles", headers=headers)
    role_map = {r["name"]: {"id": r["id"], "name": r["name"]} for r in all_roles}
    
    # 4. Create Users
    users = [
        {
            "username": "ali.almansouri",
            "email": "ali@insureflow.com",
            "firstName": "Ali",
            "lastName": "Al Mansouri",
            "enabled": True,
            "attributes": {
                "cin": ["05739884"]
            },
            "credentials": [
                {
                    "type": "password",
                    "value": "123456",
                    "temporary": False
                }
            ],
            "_role": "CLIENT"
        },
        {
            "username": "admin.insureflow",
            "email": "admin@insureflow.com",
            "firstName": "Admin",
            "lastName": "InsureFlow",
            "enabled": True,
            "credentials": [
                {
                    "type": "password",
                    "value": "Admin2026!",
                    "temporary": False
                }
            ],
            "_role": "ADMIN"
        }
    ]
    
    for u in users:
        print(f"Creating user {u['username']}...")
        role_to_assign = u.pop("_role")
        status, data = make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/users", method="POST", headers=headers, data=u)
        if status not in (200, 201):
            print(f"Failed to create user {u['username']}. Status: {status}, data: {data}")
            sys.exit(1)
        
        # Get user ID
        status, res = make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/users?username={u['username']}", headers=headers)
        user_id = res[0]["id"]
        
        # Assign role
        print(f"Assigning role {role_to_assign} to {u['username']}...")
        status, _ = make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/users/{user_id}/role-mappings/realm", 
                             method="POST", headers=headers, data=[role_map[role_to_assign]])

    # Step 5: Add protocol mappers
    internal_ids = [backend_client_id_internal, frontend_client_id_internal]
    
    for client_id_internal in internal_ids:
        if not client_id_internal: continue
        
        print(f"Adding mappers to client {client_id_internal}...")
        
        # 1. CIN mapper
        cin_mapper = {
            "protocol": "openid-connect",
            "protocolMapper": "oidc-usermodel-attribute-mapper",
            "name": "cin",
            "config": {
                "user.attribute": "cin",
                "claim.name": "cin",
                "jsonType.label": "String",
                "id.token.claim": "true",
                "access.token.claim": "true",
                "userinfo.token.claim": "true"
            }
        }
        make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/clients/{client_id_internal}/protocol-mappers/models", 
                     method="POST", headers=headers, data=cin_mapper)

        # 2. Roles mapper (realm_access.roles)
        roles_mapper = {
            "protocol": "openid-connect",
            "protocolMapper": "oidc-usermodel-realm-role-mapper",
            "name": "roles",
            "config": {
                "multivalued": "true",
                "userinfo.token.claim": "true",
                "id.token.claim": "true",
                "access.token.claim": "true",
                "claim.name": "realm_access.roles",
                "jsonType.label": "String"
            }
        }
        make_request(f"{KEYCLOAK_URL}/admin/realms/{realm_name}/clients/{client_id_internal}/protocol-mappers/models", 
                     method="POST", headers=headers, data=roles_mapper)
        
    print("Done!")

if __name__ == "__main__":
    main()
