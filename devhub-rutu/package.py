"""Package explicitly selected, successful builds; never publish a latest/unknown build."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from html.parser import HTMLParser

ROOT = Path(__file__).resolve().parent
REPO = 'Rubenvaggelen/apps'

def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/{path}']))

def verify_run(run, build, source):
    if not (run['conclusion'] == 'success' and run['status'] == 'completed'
            and run['head_sha'] == source and run['head_branch'] == 'feature/jade-orders'
            and run['path'] == build['workflow'] and run['event'] in ('push', 'workflow_dispatch')
            and run['head_repository']['full_name'] == REPO):
        raise ValueError('Build identity or successful result does not match manifest')

def verify_hash(path, expected):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f'Checksum mismatch: {path.name}')

class Links(HTMLParser):
    def __init__(self):
        super().__init__(); self.links = []
    def handle_starttag(self, tag, attrs):
        self.links.extend(v for k,v in attrs if k in ('src','href') and v)

def main():
    manifest = json.loads((ROOT/'manifest.json').read_text())
    assert re.fullmatch(r'[a-f0-9]{40}', manifest['source_sha'])
    stage = Path('publish-package')
    stage.mkdir(exist_ok=False)
    shutil.copytree(ROOT/'public', stage, dirs_exist_ok=True)
    downloads = stage/'rutu-downloads'; downloads.mkdir()
    for build in manifest['builds']:
        assert re.fullmatch(r'[A-Za-z0-9._-]+', build['target'])
        assert re.fullmatch(r'[a-f0-9]{64}', build['sha256'])
        verify_run(api(f"actions/runs/{build['run']}"), build, manifest['source_sha'])
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(['gh','run','download',str(build['run']),'-R',REPO,
                            '-n',build['artifact'],'-D',tmp], check=True)
            files = list(Path(tmp).rglob(build['file']))
            if len(files) != 1 or not files[0].is_file() or files[0].is_symlink():
                raise ValueError('Expected exactly one build file')
            verify_hash(files[0],build['sha256'])
            shutil.copyfile(files[0],downloads/build['target'])
    page = (stage/'rutu.html').read_text(encoding='utf-8')
    links = Links(); links.feed(page)
    for link in links.links:
        if link.startswith(('https://','#')) or link == 'index.php': continue
        path = stage/link
        if not path.is_file() or not path.resolve().is_relative_to(stage.resolve()):
            raise ValueError(f'Missing/invalid local link: {link}')
    deployment = {'commit':os.environ['GITHUB_SHA'], 'run':os.environ['GITHUB_RUN_ID'],
                  'app_source':manifest['source_sha']}
    (stage/'rutu-deployment.json').write_text(json.dumps(deployment)+'\n')
    files = sorted(p for p in stage.rglob('*') if p.is_file())
    checksums = ''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(stage).as_posix()+'\n' for p in files)
    (stage/'SHA256SUMS').write_text(checksums)
    with tarfile.open('rutu-package.tar.gz','w:gz') as archive:
        for p in files + [stage/'SHA256SUMS']:
            archive.add(p,arcname=p.relative_to(stage).as_posix(),recursive=False)
    print('Package verified: selected successful builds, checksums, and page links')

if __name__ == '__main__': main()

