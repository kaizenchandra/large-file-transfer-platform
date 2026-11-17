#!/usr/bin/env python3
"""Measured local transfer driver; 3 workers, bounded buffers, independent streamed download SHA-256.
20 GB means exactly 20,000,000,000 bytes. Scan bypass, when used, must be reported explicitly.
"""
import urllib.error
import argparse,base64,concurrent.futures,hashlib,json,os,pathlib,random,time,urllib.request,uuid,resource
p=argparse.ArgumentParser();p.add_argument('--size',type=int,default=20_000_000_000);p.add_argument('--directory',default='/tmp/harbor-large-transfer');p.add_argument('--api',default='http://localhost:28081/api/v1');p.add_argument('--report',required=True);p.add_argument('--provider',default='localstack-4.10.0');p.add_argument('--expect-review',action='store_true');p.add_argument('--scan-profile',default='local DISABLED; not a security benchmark');a=p.parse_args()
root=pathlib.Path(a.directory);root.mkdir(exist_ok=True);source=root/'source.bin';state=root/'session.json'
auth='Basic '+base64.b64encode(b'alice:harbor-local-benchmark-password').decode()
report={'provider':a.provider,'bytes':a.size,'api':a.api,'upload_concurrency':3,'scan_profile':'REQUIRED segmented signatures, mandatory review' if a.expect_review else a.scan_profile,'fixture':'repeated seeded 1 MiB pseudorandom block; compressible repetition','started_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}
start=time.monotonic()
def save():pathlib.Path(a.report).write_text(json.dumps(report,indent=2)+'\n')
def api(path,body=None,key=None):
 headers={'Authorization':auth,'Content-Type':'application/json'}
 if key:headers['Idempotency-Key']=key
 for attempt in range(4):
  try:
   with urllib.request.urlopen(urllib.request.Request(a.api+path,data=None if body is None else json.dumps(body).encode(),headers=headers),timeout=120) as r:return json.load(r)
  except urllib.error.HTTPError as e:
   if (e.code<500 and e.code!=429) or attempt==3:raise
  except OSError:
   if attempt==3:raise
  time.sleep(2**attempt)
def fingerprint():
 h=hashlib.sha256()
 with source.open('rb') as f:
  while b:=f.read(1024*1024):h.update(b)
 return base64.b64encode(h.digest()).decode()
try:
 t=time.monotonic()
 if not source.exists() or source.stat().st_size!=a.size:
  block=random.Random(42).randbytes(1024*1024)
  with source.open('wb') as f:
   for offset in range(0,a.size,len(block)):f.write(block[:min(len(block),a.size-offset)])
 report['fixture_seconds']=time.monotonic()-t;t=time.monotonic();sha=fingerprint();report['source_sha256']=sha;report['hash_seconds']=time.monotonic()-t;save()
 if state.exists():session=json.loads(state.read_text());assert session['size']==a.size and session['sha256']==sha
 else:
  session=api('/uploads',{'name':'measured-'+str(uuid.uuid4())+'.bin','size':a.size,'sha256':sha,'expected':0},str(uuid.uuid4()));state.write_text(json.dumps(session))
 upload_id=session['id'];status=api('/uploads/'+upload_id);report['resumed_from_state']=status['state'];known={part['number']:part for part in status.get('parts',[])};chunk=session['part_size'];count=max(1,(a.size+chunk-1)//chunk);uploaded=0;retries=0
 def part(number):
  global retries
  with source.open('rb') as f:f.seek((number-1)*chunk);data=f.read(min(chunk,a.size-(number-1)*chunk))
  checksum=base64.b64encode(hashlib.sha256(data).digest()).decode()
  if known.get(number,{}).get('checksum')==checksum:return len(data)
  for attempt in range(4):
   try:
    signed=api('/uploads/'+upload_id+'/parts',{'number':number,'sha256':checksum});headers={k:','.join(v) for k,v in signed['headers'].items() if k.lower()!='host'}
    with urllib.request.urlopen(urllib.request.Request(signed['url'],method='PUT',data=data,headers=headers),timeout=180) as r:assert r.status==200
    return len(data)
   except Exception:
    retries+=1
    if attempt==3:raise
    time.sleep(2**attempt)
 t=time.monotonic()
 if status['state'] not in ('COMPLETING','COMPLETED'):
  with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
   # Only three pending tasks; no unbounded queued file buffers.
   pending={pool.submit(part,n):n for n in range(1,min(3,count)+1)};next_part=min(3,count)+1
   while pending:
    done,_=concurrent.futures.wait(pending,return_when=concurrent.futures.FIRST_COMPLETED)
    for task in done:
     uploaded+=task.result();pending.pop(task)
     if next_part<=count:pending[pool.submit(part,next_part)]=next_part;next_part+=1
    report['uploaded_bytes']=uploaded;report['parts']=count;report['upload_elapsed_seconds']=time.monotonic()-t;save()
 report['parts_seconds']=time.monotonic()-t;report['part_retries']=retries;save();t=time.monotonic();api('/uploads/'+upload_id+'/complete',{})
 for attempt in range(3600):
  status=api('/uploads/'+upload_id)
  if a.expect_review and status['state']=='QUARANTINED':
   report['scan_status']=status['scan_status'];report['scan_evidence']=status.get('scanEvidence',[]);report['completion_seconds']=time.monotonic()-t
   assert status['scan_status']=='REVIEW_REQUIRED' and report['scan_evidence'][0]['covered_bytes']==a.size
   report['outcome']='review-required';report['published']=False
   raise SystemExit(0)
  if status['state']=='COMPLETED':break
  if status['state']!='COMPLETING':raise RuntimeError('Completion '+status['state'])
  if attempt%5==0:report['completion_elapsed_seconds']=time.monotonic()-t;save()
  time.sleep(1)
 else:raise TimeoutError('Completion pending after one hour')
 report['scan_status']=status.get('scan_status');report['completion_state']=status['state'];report['completion_seconds']=time.monotonic()-t;file_id=status['node'];report['file_id']=file_id;save()
 t=time.monotonic();credentials=api('/files/'+file_id+'/download');digest=hashlib.sha256();received=0
 # Parallel bounded ranges with ordered hash verification; no whole-file allocation or disk assembly claim.
 for base in range(0,a.size,3*chunk):
  credentials=api('/files/'+file_id+'/download')
  def fetch_range_once(bounds):
   lo,hi=bounds
   with urllib.request.urlopen(urllib.request.Request(credentials['url'],headers={'Range':f'bytes={lo}-{hi}'}),timeout=180) as r:
    assert r.status==206 and r.headers['Content-Range']==f'bytes {lo}-{hi}/{a.size}'
    data=r.read(hi-lo+2);assert len(data)==hi-lo+1;return data
  def fetch_range(bounds):
   for attempt in range(4):
    try:return fetch_range_once(bounds)
    except (OSError,ValueError):
     if attempt==3:raise
     time.sleep(2**attempt)
  bounds=[(lo,min(a.size,lo+chunk)-1) for lo in range(base,min(a.size,base+3*chunk),chunk)]
  with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
   for data in pool.map(fetch_range,bounds):digest.update(data);received+=len(data)
  report['downloaded_bytes']=received;report['download_elapsed_seconds']=time.monotonic()-t;save()
 report['download_seconds']=time.monotonic()-t;report['download_sha256']=base64.b64encode(digest.digest()).decode();assert received==a.size and report['download_sha256']==sha
 report['verified']=True;report['outcome']='passed';report['upload_mib_s']=uploaded/(1024**2)/report['parts_seconds'] if uploaded else None;report['download_mib_s']=a.size/(1024**2)/report['download_seconds']
except Exception as e:
 report['outcome']='failed';report['error_class']=type(e).__name__;report['error']=str(e)[:160];raise
finally:
 report['total_seconds']=time.monotonic()-start;report['client_max_rss_bytes']=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss;save()
