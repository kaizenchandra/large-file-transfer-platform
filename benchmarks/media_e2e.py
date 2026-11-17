import base64,json,os,pathlib,subprocess,time,urllib.request,hashlib,uuid
root=pathlib.Path(__file__).resolve().parents[1];auth='Basic '+base64.b64encode(b'alice:harbor-local-benchmark-password').decode();api='http://localhost:28080/api/v1'
def call(path):
 with urllib.request.urlopen(urllib.request.Request(api+path,headers={'Authorization':auth}),timeout=60) as response:return json.load(response)
report=[]
for kind in ['pdf','mp4']:
 started=time.monotonic();run=str(uuid.uuid4());env=dict(os.environ,DRIVE_API=api,DRIVE_AUTH=auth,DRIVE_NAME='sandbox-'+run+'.'+kind)
 result=subprocess.run(['python3',str(root/'docker/transfer.py'),'/tmp/harbor-media-fixtures/fixture.'+kind,'/tmp/harbor-media-'+run+'.json'],env=env,capture_output=True,text=True,timeout=180)
 if result.returncode:raise RuntimeError(result.stderr)
 node=json.loads(result.stdout);file_id=node.get('node',node['id'])
 for attempt in range(180):
  artifacts=call('/files/'+file_id+'/artifacts')
  if any(v['variant']==('pdf-preview' if kind=='pdf' else 'video-preview') for v in artifacts):break
  jobs=call('/files/'+file_id+'/processing')
  if any(j['state']=='FAILED_TERMINAL' for j in jobs):raise RuntimeError('Media job terminal failure')
  time.sleep(1)
 else:raise TimeoutError('Preview not produced')
 variant='pdf-preview' if kind=='pdf' else 'video-preview';output=call('/files/'+file_id+'/artifacts/'+variant+'/download')
 with urllib.request.urlopen(output['url']) as response:data=response.read(8*1024*1024+1)
 assert data.startswith(b'\x89PNG\r\n\x1a\n') and base64.b64encode(hashlib.sha256(data).digest()).decode()==output['sha256']
 report.append({'kind':kind,'file_id':file_id,'variant':variant,'preview_bytes':len(data),'end_to_end_seconds':time.monotonic()-started,'scan_mode':'REQUIRED','outcome':'passed'})
 (root/'test-results/media-e2e.json').write_text(json.dumps(report,indent=2)+'\n')
