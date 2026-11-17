#!/usr/bin/env python3
import concurrent.futures,json,pathlib,subprocess,time,uuid,statistics,sys
profiles=[(1,1000000000),(4,1000000000),(8,1000000000)];api='http://localhost:28082/api/v1';profile='scan-bypassed MinIO transfer profile';filename='concurrent-transfers.json'
if '--scanner' in sys.argv:profiles=[(1,100000000),(4,100000000),(8,100000000)];api='http://localhost:28083/api/v1';profile='REQUIRED whole-file ClamAV';filename='concurrent-scanner-transfers.json'
if '--eight-only' in sys.argv:profiles=[(8,1000000000)]
label=sys.argv[sys.argv.index('--label')+1] if '--label' in sys.argv else 'exploratory-overlapped'
filename=filename.replace('.json','-'+label+'.json')
root=pathlib.Path(__file__).resolve().parents[1];out=root/'test-results'/filename;report={'profile':profile,'run_phase':label,'results':[]}
for concurrency,size in profiles:
 run=uuid.uuid4().hex;start=time.monotonic()
 def execute(index):
  target=root/'test-results'/f'concurrent-{concurrency}-{index}-{run}.json';directory=f'/tmp/harbor-concurrent-{run}-{index}'
  command=[sys.executable,str(root/'benchmarks/large_transfer.py'),'--size',str(size),'--api',api,'--scan-profile',profile,'--directory',directory,'--provider','minio-concurrent-transfer','--report',str(target)]
  result=subprocess.run(command,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=900)
  data=json.loads(target.read_text());data['exit_code']=result.returncode
  if '--scanner' in sys.argv and data.get('scan_status')!='CLEAN':data['outcome']='failed';data['error']='Expected whole-file CLEAN verdict'
  return data
 with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:records=list(pool.map(execute,range(concurrency)))
 elapsed=time.monotonic()-start;passed=[r for r in records if r.get('verified') and r.get('outcome')=='passed'];durations=sorted(r['total_seconds'] for r in records)
 report['results'].append({'concurrent_files':concurrency,'bytes_per_file':size,'files_passed':len(passed),'files_failed':len(records)-len(passed),'aggregate_verified_bytes':sum(r['bytes'] for r in passed),'wall_seconds':elapsed,'end_to_end_mib_s':sum(r['bytes'] for r in passed)/1048576/elapsed,'median_file_seconds':statistics.median(durations),'maximum_file_seconds':max(durations),'part_retries':sum(r.get('part_retries',0) for r in records),'files':records});out.write_text(json.dumps(report,indent=2)+'\n')
 if len(passed)!=concurrency:raise SystemExit('Concurrent transfer failure; inspect raw reports')
