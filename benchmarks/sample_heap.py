import base64,json,pathlib,time,urllib.request,sys
port=sys.argv[1] if len(sys.argv)>1 else '28081';report=pathlib.Path(__file__).resolve().parents[1]/('test-results/heap-samples-'+port+'.json');auth='Basic '+base64.b64encode(b'alice:harbor-local-benchmark-password').decode();samples=[]
for i in range(180):
 try:
  with urllib.request.urlopen(urllib.request.Request('http://localhost:'+port+'/actuator/prometheus',headers={'Authorization':auth}),timeout=3) as r:text=r.read().decode()
  heap=sum(float(line.rsplit(' ',1)[-1]) for line in text.splitlines() if line.startswith('jvm_memory_used_bytes{') and 'area="heap"' in line)
  samples.append({'epoch':time.time(),'heap_used_bytes':heap});report.write_text(json.dumps({'interval_seconds':5,'samples':samples,'maximum_observed_heap_bytes':max(s['heap_used_bytes'] for s in samples)},indent=2)+'\n')
 except OSError:pass
 time.sleep(5)
