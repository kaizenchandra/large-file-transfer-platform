#!/usr/bin/env python3
"""Run an immutable JAR copy: Maven may rebuild target/ while local services are running."""
import hashlib,pathlib,shutil,subprocess,sys,tempfile
source=pathlib.Path(__file__).resolve().parents[1]/'backend/target/drive-clone-1.0.0-SNAPSHOT.jar'
root=pathlib.Path(tempfile.gettempdir())/'harbor-runtime';root.mkdir(exist_ok=True)
# Hash incrementally; the application package itself need not be buffered.
hash=hashlib.sha256()
with source.open('rb') as f:
 while block:=f.read(131072):hash.update(block)
copy=root/(hash.hexdigest()+'.jar')
if not copy.exists():shutil.copyfile(source,copy)
raise SystemExit(subprocess.call(['java','-Xmx512m','-jar',str(copy),*sys.argv[1:]]))
