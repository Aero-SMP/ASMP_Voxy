"""Prepare matched hashes after buildAll; performs no deployment or process control."""
import hashlib,json,zipfile
from pathlib import Path
ROOT=Path('/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates')
paths=[ROOT/'build/libs/ASMP_voxy-0.2.257-beta+1.21.1-neoforge-debug.jar',ROOT/'server/build/libs/voxy-server-0.2.257-beta+1.21.1-neoforge-debug.jar',ROOT/'rust-server/target/release/voxy-rust-server']
artifacts=[dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),bytes=path.stat().st_size) for path in paths]
for path in paths[:2]:
    with zipfile.ZipFile(path) as archive:
        manifest=archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n','\n')
        assert 'Implementation-Version: 0.2.257-beta\n' in manifest, 'JAR version does not match257 staging'
with zipfile.ZipFile(paths[1]) as archive:
    assert hashlib.sha256(archive.read('native/linux-x86_64/voxy-rust-server')).hexdigest()==artifacts[2]['sha256'], 'server JAR/native artifact mismatch'
receipt=ROOT/'project_audit/deployment/staged257.json'
with receipt.open('x') as output: output.write(json.dumps(artifacts,indent=2)+'\n')
print(json.dumps(artifacts,indent=2))
