import urllib.request
import re

url = "https://raw.githubusercontent.com/trinodb/trino/master/docs/src/main/sphinx/develop/functions.md"
req = urllib.request.Request(url)
with urllib.request.urlopen(req) as response:
    content = response.read().decode('utf-8')
    for match in re.finditer(r'(@TypeParameter.*?\{.*?\})', content, re.DOTALL):
        print(match.group(0)[:1000])
        print("-------")
