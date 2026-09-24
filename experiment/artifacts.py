"""Download one exactly named GitHub artifact without forwarding credentials to storage."""
import io
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import zipfile

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def download(name, destination):
    repository = os.environ['GITHUB_REPOSITORY']
    headers = {'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'],
               'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2026-03-10'}
    base = 'https://api.github.com/repos/' + repository + '/actions/artifacts'
    matches = []
    for page in range(1, 11):
        request = urllib.request.Request(base + '?per_page=100&page=' + str(page), headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            items = json.load(response)['artifacts']
        matches.extend(item for item in items if item['name'] == name and not item['expired'])
        if len(items) < 100: break
    if len(matches) != 1: raise RuntimeError('Expected exactly one correlated artifact: ' + name)
    request = urllib.request.Request(matches[0]['archive_download_url'], headers=headers)
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        if error.code != 302: raise
        # Storage is signed by the service; GitHub Authorization stays on api.github.com.
        with urllib.request.urlopen(error.headers['Location'], timeout=60) as response:
            payload = response.read()
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        for entry in archive.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination): raise RuntimeError('Unsafe artifact path')
        archive.extractall(destination)
    return matches[0]['id']

if __name__ == '__main__':
    import sys
    download(sys.argv[1], sys.argv[2])
